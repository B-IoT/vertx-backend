package ch.biot.backend.relays

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoUserUtil
import io.vertx.ext.mongo.MongoClient
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.openapi.RouterBuilder
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import org.slf4j.LoggerFactory


class RelaysVerticle : AbstractVerticle() {

  companion object {
    private const val RELAYS_COLLECTION = "relays"
    private const val PORT = 3000
  }

  private val logger = LoggerFactory.getLogger(RelaysVerticle::class.java)
  private lateinit var mongoClient: MongoClient
  private lateinit var mongoAuthUtil: MongoUserUtil

  override fun start(startPromise: Promise<Void>?) {
    mongoClient =
      MongoClient.createShared(vertx, jsonObjectOf("host" to "localhost", "port" to 27017, "db_name" to "clients"))

    val usernameField = "mqttUsername"
    val passwordField = "mqttPassword"
    mongoAuthUtil = MongoUserUtil.create(
      mongoClient, mongoAuthenticationOptionsOf(
        collectionName = RELAYS_COLLECTION,
        passwordCredentialField = passwordField,
        passwordField = passwordField,
        usernameCredentialField = usernameField,
        usernameField = usernameField
      ), mongoAuthorizationOptionsOf()
    )

    RouterBuilder.create(vertx, "../swagger-api/swagger.yaml").onComplete { ar ->
      if (ar.succeeded()) {
        // Spec loaded with success
        val routerBuilder = ar.result()

        routerBuilder.operation("registerRelay").handler(::registerHandler)
        routerBuilder.operation("getRelays").handler(::getRelaysHandler)
        routerBuilder.operation("getRelay").handler(::getRelayHandler)

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
    json?.let {
      // Create the user
      mongoAuthUtil.createUser(it["mqttUsername"], it["mqttPassword"]).onSuccess { docID ->
        // Update the user with the data specified in the HTTP request
        val query = jsonObjectOf("_id" to docID)
        val extraInfo = jsonObjectOf(
          "\$set" to json
        )
        mongoClient.findOneAndUpdate(RELAYS_COLLECTION, query, extraInfo).onSuccess {
          logger.info("New relay $json registered")
          ctx.end()
        }.onFailure { error ->
          logger.error("Could not register relay with data $json", error)
          ctx.fail(400, error)
        }
      }.onFailure { error ->
        logger.error("Could not register relay with data $json", error)
        ctx.fail(400, error)
      }
    } ?: ctx.fail(400)
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
      ctx.fail(400, error)
    }
  }

  private fun getRelayHandler(ctx: RoutingContext) {
    logger.info("New getRelay request")
    // TODO MongoDBs
    ctx.end()
  }

  private fun JsonObject.clean(): JsonObject = this.copy().apply {
    remove("_id")
    if (containsKey("lastModified")) {
      val lastModifiedObject: JsonObject = this["lastModified"]
      put("lastModified", lastModifiedObject["\$date"])
    }
  }
}
