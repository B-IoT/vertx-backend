package ch.biot.backend.crud

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoAuthentication
import io.vertx.ext.auth.mongo.MongoUserUtil
import io.vertx.ext.mongo.MongoClient
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.openapi.RouterBuilder
import io.vertx.kotlin.core.eventbus.eventBusOptionsOf
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.core.vertxOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import io.vertx.kotlin.ext.mongo.findOptionsOf
import io.vertx.kotlin.ext.mongo.updateOptionsOf
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.*


class CRUDVerticle : AbstractVerticle() {

  companion object {
    private const val RELAYS_COLLECTION = "relays"
    private const val RELAYS_UPDATE_ADDRESS = "relays.update"
    private const val PORT = 3000

    private val logger = LoggerFactory.getLogger(CRUDVerticle::class.java)

    @Throws(UnknownHostException::class)
    @JvmStatic
    fun main(args: Array<String>) {
      val ipv4 = InetAddress.getLocalHost().hostAddress
      val options = vertxOptionsOf(
        clusterManager = HazelcastClusterManager(),
        eventBusOptions = eventBusOptionsOf(host = ipv4, clusterPublicHost = ipv4)
      )

      Vertx.clusteredVertx(options).onSuccess {
        it.deployVerticle(CRUDVerticle())
      }.onFailure { error ->
        logger.error("Could not start", error)
      }
    }
  }

  private lateinit var mongoClient: MongoClient
  private lateinit var mongoUserUtil: MongoUserUtil
  private lateinit var mongoAuth: MongoAuthentication

  override fun start(startPromise: Promise<Void>?) {
    mongoClient =
      MongoClient.createShared(vertx, jsonObjectOf("host" to "localhost", "port" to 27017, "db_name" to "clients"))

    val usernameField = "mqttUsername"
    val passwordField = "mqttPassword"
    val mongoAuthOptions = mongoAuthenticationOptionsOf(
      collectionName = RELAYS_COLLECTION,
      passwordCredentialField = passwordField,
      passwordField = passwordField,
      usernameCredentialField = usernameField,
      usernameField = usernameField
    )

    mongoUserUtil = MongoUserUtil.create(
      mongoClient, mongoAuthOptions, mongoAuthorizationOptionsOf()
    )
    mongoAuth = MongoAuthentication.create(mongoClient, mongoAuthOptions)

    RouterBuilder.create(vertx, "../swagger-api/swagger.yaml").onComplete { ar ->
      if (ar.succeeded()) {
        // Spec loaded with success
        val routerBuilder = ar.result()

        routerBuilder.operation("registerRelay").handler(::registerHandler)
        routerBuilder.operation("getRelays").handler(::getRelaysHandler)
        routerBuilder.operation("getRelay").handler(::getRelayHandler)
        routerBuilder.operation("updateRelay").handler(::updateHandler)

        val router: Router = routerBuilder.createRouter()
        vertx.createHttpServer().requestHandler(router).listen(PORT)
        startPromise?.complete()
      } else {
        // Something went wrong during router builder initialization
        logger.error("Could not initialize router builder", ar.cause())
      }
    }
  }

  private fun registerHandler(ctx: RoutingContext) {
    logger.info("New register request")
    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      // Create the users
      val password: String = json["mqttPassword"]
      val hashedPassword = password.saltAndHash()
      mongoUserUtil.createHashedUser(json["mqttUsername"], hashedPassword).compose { docID ->
        // Update the user with the data specified in the HTTP request
        val query = jsonObjectOf("_id" to docID)
        val extraInfo = jsonObjectOf(
          "\$set" to json.copy().apply {
            remove("mqttPassword")
          }
        )
        mongoClient.findOneAndUpdate(RELAYS_COLLECTION, query, extraInfo)
      }.onSuccess {
        logger.info("New relay registered")
        ctx.end()
      }.onFailure { error ->
        logger.error("Could not register relay", error)
        ctx.fail(500, error)
      }
    }
  }

  private fun getRelaysHandler(ctx: RoutingContext) {
    logger.info("New getRelays request")
    // TODO use offset and limit parameters
    mongoClient.find(RELAYS_COLLECTION, jsonObjectOf()).onSuccess { relays ->
      ctx.response()
        .putHeader("Content-Type", "application/json")
        .end(JsonArray(relays.map { it.clean() }).encode())
    }.onFailure { error ->
      logger.error("Could not get relays", error)
      ctx.fail(500, error)
    }
  }

  private fun getRelayHandler(ctx: RoutingContext) {
    val relayID = ctx.pathParam("id")
    logger.info("New getRelay request for relay $relayID")
    val query = jsonObjectOf("relayID" to relayID)
    mongoClient.findOne(RELAYS_COLLECTION, query, jsonObjectOf()).onSuccess { relay ->
      ctx.response()
        .putHeader("Content-Type", "application/json")
        .end(relay.clean().encode())
    }.onFailure { error ->
      logger.error("Could not get relay", error)
      ctx.fail(500, error)
    }
  }

  private fun updateHandler(ctx: RoutingContext) {
    logger.info("New updateRelay request")
    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      // Update MongoDB
      val query = jsonObjectOf("relayID" to ctx.pathParam("id"))
      val update = json {
        obj(
          "\$set" to json.copy().apply {
            remove("beacon")
          },
          "\$currentDate" to obj("lastModified" to true)
        )
      }
      mongoClient.findOneAndUpdateWithOptions(
        RELAYS_COLLECTION,
        query,
        update,
        findOptionsOf(),
        updateOptionsOf(returningNewDocument = true)
      ).onSuccess { result ->
        logger.info("Successfully updated MongoDB collection $RELAYS_COLLECTION with update JSON $update")
        val cleanEntry = result.cleanForRelay().apply {
          put("beacon", json["beacon"])
        }
        // Send to the RelaysCommunicationVerticle the entry to update the relay
        vertx.eventBus().send(RELAYS_UPDATE_ADDRESS, cleanEntry)
        logger.info("Update sent to the event bus address $RELAYS_UPDATE_ADDRESS")
        ctx.end()
      }.onFailure { error ->
        logger.error("Could not update MongoDB collection $RELAYS_COLLECTION with update JSON $update", error)
        ctx.fail(500)
      }
    }
  }

  private fun JsonObject?.validateAndThen(ctx: RoutingContext, block: (JsonObject) -> Unit) {
    when {
      this == null -> {
        logger.warn("Bad request with null body")
        ctx.fail(400)
      }
      this.isEmpty -> {
        logger.warn("Bad request with empty body")
        ctx.fail(400)
      }
      else -> block(this)
    }
  }

  private fun JsonObject.clean(): JsonObject = this.copy().apply {
    remove("_id")
    cleanLastModified()
  }

  private fun JsonObject.cleanForRelay(): JsonObject = this.copy().apply {
    remove("_id")
    remove("mqttUsername")
    remove("mqttPassword")
    remove("latitude")
    remove("longitude")
    cleanLastModified()
  }

  private fun JsonObject.cleanLastModified() {
    if (containsKey("lastModified")) {
      val lastModifiedObject: JsonObject = this["lastModified"]
      put("lastModified", lastModifiedObject["\$date"])
    }
  }

  private fun String.saltAndHash(): String {
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)
    return mongoAuth.hash("pbkdf2", String(Base64.getEncoder().encode(salt)), this)
  }
}
