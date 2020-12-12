/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

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
import io.vertx.kotlin.pgclient.pgConnectOptionsOf
import io.vertx.kotlin.sqlclient.poolOptionsOf
import io.vertx.pgclient.PgPool
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.security.SecureRandom
import java.util.*


class CRUDVerticle : AbstractVerticle() {

  companion object {
    private const val RELAYS_COLLECTION = "relays"
    private const val USERS_COLLECTION = "users"

    private const val RELAYS_UPDATE_ADDRESS = "relays.update"

    private const val PORT = 3000

    private val logger = LoggerFactory.getLogger(CRUDVerticle::class.java)

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
  private lateinit var mongoUserUtilRelays: MongoUserUtil
  private lateinit var mongoAuthRelays: MongoAuthentication
  private lateinit var mongoUserUtilUsers: MongoUserUtil
  private lateinit var mongoAuthUsers: MongoAuthentication

  private lateinit var pgPool: PgPool

  override fun start(startPromise: Promise<Void>?) {
    mongoClient =
      MongoClient.createShared(vertx, jsonObjectOf("host" to "localhost", "port" to 27017, "db_name" to "clients"))

    val usernameFieldRelays = "mqttUsername"
    val passwordFieldRelays = "mqttPassword"
    val mongoAuthRelaysOptions = mongoAuthenticationOptionsOf(
      collectionName = RELAYS_COLLECTION,
      passwordCredentialField = passwordFieldRelays,
      passwordField = passwordFieldRelays,
      usernameCredentialField = usernameFieldRelays,
      usernameField = usernameFieldRelays
    )

    mongoUserUtilRelays = MongoUserUtil.create(
      mongoClient, mongoAuthRelaysOptions, mongoAuthorizationOptionsOf()
    )
    mongoAuthRelays = MongoAuthentication.create(mongoClient, mongoAuthRelaysOptions)

    val usernameFieldUsers = "username"
    val passwordFieldUsers = "password"
    val mongoAuthUsersOptions = mongoAuthenticationOptionsOf(
      collectionName = USERS_COLLECTION,
      passwordCredentialField = passwordFieldUsers,
      passwordField = passwordFieldUsers,
      usernameCredentialField = usernameFieldUsers,
      usernameField = usernameFieldUsers
    )

    mongoUserUtilUsers = MongoUserUtil.create(
      mongoClient, mongoAuthUsersOptions, mongoAuthorizationOptionsOf()
    )
    mongoAuthUsers = MongoAuthentication.create(mongoClient, mongoAuthUsersOptions)

    val pgConnectOptions =
      pgConnectOptionsOf(
        port = 5432,
        host = "localhost",
        database = "postgres",
        user = "postgres",
        password = "biot",
        cachePreparedStatements = true
      )
    pgPool = PgPool.pool(vertx, pgConnectOptions, poolOptionsOf())

    RouterBuilder.create(vertx, "../swagger-api/swagger.yaml").onComplete { ar ->
      if (ar.succeeded()) {
        // Spec loaded with success
        val routerBuilder = ar.result()

        // Relays
        routerBuilder.operation("registerRelay").handler(::registerRelayHandler)
        routerBuilder.operation("getRelays").handler(::getRelaysHandler)
        routerBuilder.operation("getRelay").handler(::getRelayHandler)
        routerBuilder.operation("updateRelay").handler(::updateRelayHandler)

        // Users
        routerBuilder.operation("registerUser").handler(::registerUserHandler)
        routerBuilder.operation("getUsers").handler(::getUsersHandler)
        routerBuilder.operation("getUser").handler(::getUserHandler)
        routerBuilder.operation("updateUser").handler(::updateUserHandler)
        routerBuilder.operation("authenticate").handler(::authenticateHandler)

        // Items
        routerBuilder.operation("registerItem").handler(::registerItemHandler)
        routerBuilder.operation("getItems").handler(::getItemsHandler)
        routerBuilder.operation("getItem").handler(::getItemHandler)
        routerBuilder.operation("updateItem").handler(::updateItemHandler)

        val router: Router = routerBuilder.createRouter()
        vertx.createHttpServer().requestHandler(router).listen(PORT).onComplete {
          startPromise?.complete()
        }
      } else {
        // Something went wrong during router builder initialization
        logger.error("Could not initialize router builder", ar.cause())
      }
    }
  }

  // Relays handlers

  private fun registerRelayHandler(ctx: RoutingContext) {
    logger.info("New register request")
    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      // Create the relay
      val password: String = json["mqttPassword"]
      val hashedPassword = password.saltAndHash()
      mongoUserUtilRelays.createHashedUser(json["mqttUsername"], hashedPassword).compose { docID ->
        // Update the relay with the data specified in the HTTP request
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

  private fun updateRelayHandler(ctx: RoutingContext) {
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

  // Users handlers

  private fun registerUserHandler(ctx: RoutingContext) {
    logger.info("New user request")
    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      // Create the user
      val password: String = json["password"]
      val hashedPassword = password.saltAndHash()
      mongoUserUtilUsers.createHashedUser(json["username"], hashedPassword).compose { docID ->
        // Update the user with the data specified in the HTTP request
        val query = jsonObjectOf("_id" to docID)
        val extraInfo = jsonObjectOf(
          "\$set" to json.copy().apply {
            remove("password")
          }
        )
        mongoClient.findOneAndUpdate(USERS_COLLECTION, query, extraInfo)
      }.onSuccess {
        logger.info("New user registered")
        ctx.end()
      }.onFailure { error ->
        logger.error("Could not register user", error)
        ctx.fail(500, error)
      }
    }
  }

  private fun getUsersHandler(ctx: RoutingContext) {
    logger.info("New getUsers request")
    // TODO use offset and limit parameters
    mongoClient.find(USERS_COLLECTION, jsonObjectOf())
      .onSuccess { users ->
        ctx.response()
          .putHeader("Content-Type", "application/json")
          .end(JsonArray(users.map { it.clean() }).encode())
      }.onFailure { error ->
        logger.error("Could not get users", error)
        ctx.fail(500, error)
      }
  }

  private fun getUserHandler(ctx: RoutingContext) {
    val userID = ctx.pathParam("id")
    logger.info("New getUser request for relay $userID")
    val query = jsonObjectOf("userID" to userID)
    mongoClient.findOne(USERS_COLLECTION, query, jsonObjectOf()).onSuccess { user ->
      ctx.response()
        .putHeader("Content-Type", "application/json")
        .end(user.clean().encode())
    }.onFailure { error ->
      logger.error("Could not get user", error)
      ctx.fail(500, error)
    }
  }

  private fun updateUserHandler(ctx: RoutingContext) {
    logger.info("New updateUser request")
    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      // Update MongoDB
      val query = jsonObjectOf("userID" to ctx.pathParam("id"))
      val update = json {
        obj(
          "\$set" to json,
          "\$currentDate" to obj("lastModified" to true)
        )
      }
      mongoClient.findOneAndUpdate(
        USERS_COLLECTION,
        query,
        update
      ).onSuccess {
        logger.info("Successfully updated MongoDB collection $USERS_COLLECTION with update JSON $update")
        ctx.end()
      }.onFailure { error ->
        logger.error("Could not update MongoDB collection $USERS_COLLECTION with update JSON $update", error)
        ctx.fail(500)
      }
    }
  }

  private fun authenticateHandler(ctx: RoutingContext) {
    logger.info("New authenticate request")
    val body = if (ctx.body.length() == 0) {
      jsonObjectOf()
    } else {
      ctx.bodyAsJson
    }

    mongoAuthUsers.authenticate(body).onSuccess { user ->
      val company: String = user["company"]
      ctx.end(company)
    }.onFailure { error ->
      logger.error("Authentication error: ", error)
      ctx.fail(401, error)
    }
  }

  // Items

  private fun registerItemHandler(ctx: RoutingContext) {
    logger.info("New registerItem request")

    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      val beacon: String = json["beacon"]
      val category: String = json["category"]
      val service: String = json["service"]
      pgPool.preparedQuery("INSERT INTO items (beacon, category, service) VALUES ($1, $2, $3) RETURNING id")
        .execute(Tuple.of(beacon, category, service))
        .onSuccess {
          logger.info("New item registered")
          val row = it.iterator().next()
          logger.info("Row: $row")
          ctx.end(row.getInteger("id").toString())
        }.onFailure { error ->
          logger.error("Could not register item", error)
          ctx.fail(500, error)
        }
    }
  }

  private fun getItemsHandler(ctx: RoutingContext) {
    logger.info("New getItems request")
    pgPool.preparedQuery("SELECT DISTINCT ON (I.id) * FROM items I LEFT JOIN beacon_data D ON I.beacon = D.mac ORDER BY I.id, D.time DESC")
      .execute()
      .onSuccess { res ->
        val result = res.map { it.buildItemJson() }

        ctx.response()
          .putHeader("Content-Type", "application/json")
          .end(JsonArray(result).encode())
      }.onFailure { error ->
        logger.error("Could not get items", error)
        ctx.fail(500, error)
      }
  }

  private fun getItemHandler(ctx: RoutingContext) {
    val itemID = ctx.pathParam("id")
    logger.info("New getItem request for item $itemID")

    pgPool.preparedQuery("SELECT * FROM items I LEFT JOIN beacon_data D ON I.beacon = D.mac WHERE I.id=$1 ORDER BY D.time DESC LIMIT 1")
      .execute(Tuple.of(itemID.toInt()))
      .onSuccess { res ->
        val result = res.iterator().next()?.buildItemJson() ?: jsonObjectOf()

        ctx.response()
          .putHeader("Content-Type", "application/json")
          .end(result.encode())
      }.onFailure { error ->
        logger.error("Could not get item", error)
        ctx.fail(500, error)
      }
  }

  private fun updateItemHandler(ctx: RoutingContext) {
    val itemID = ctx.pathParam("id")
    logger.info("New updateItem request for item $itemID")

    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      val beacon: String = json["beacon"]
      val category: String = json["category"]
      val service: String = json["service"]
      pgPool.preparedQuery("UPDATE items SET beacon = $1, category = $2, service = $3 WHERE id=$4")
        .execute(Tuple.of(beacon, category, service, itemID.toInt()))
        .onSuccess {
          logger.info("Successfully updated item $itemID")
          ctx.end()
        }
        .onFailure { error ->
          logger.error("Could not update item $itemID", error)
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
    clean()
    remove("mqttUsername")
    remove("mqttPassword")
    remove("latitude")
    remove("longitude")
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
    return mongoAuthRelays.hash("pbkdf2", String(Base64.getEncoder().encode(salt)), this)
  }

  private fun Row.buildItemJson(): JsonObject = jsonObjectOf(
    "id" to getInteger("id"),
    "beacon" to getString("beacon"),
    "category" to getString("category"),
    "service" to getString("service"),
    "timestamp" to getOffsetDateTime("time")?.toString(),
    "battery" to getInteger("battery"),
    "status" to getString("status"),
    "latitude" to getDouble("latitude"),
    "longitude" to getDouble("longitude")
  )
}
