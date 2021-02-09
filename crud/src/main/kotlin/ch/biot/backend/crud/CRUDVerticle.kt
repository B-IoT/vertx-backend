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
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.*


class CRUDVerticle : AbstractVerticle() {

  companion object {
    private const val RELAYS_COLLECTION = "relays"
    private const val USERS_COLLECTION = "users"

    private const val RELAYS_UPDATE_ADDRESS = "relays.update"

    internal val HTTP_PORT = System.getenv().getOrDefault("HTTP_PORT", "3000").toInt()

    internal val MONGO_PORT = System.getenv().getOrDefault("MONGO_PORT", "27017").toInt()
    private val MONGO_HOST: String = System.getenv().getOrDefault("MONGO_HOST", "localhost")

    internal val TIMESCALE_PORT = System.getenv().getOrDefault("TIMESCALE_PORT", "5432").toInt()
    private val TIMESCALE_HOST: String = System.getenv().getOrDefault("TIMESCALE_HOST", "localhost")

    internal val logger = LoggerFactory.getLogger(CRUDVerticle::class.java)

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
    // Initialize MongoDB
    mongoClient =
      MongoClient.createShared(vertx, jsonObjectOf("host" to MONGO_HOST, "port" to MONGO_PORT, "db_name" to "clients"))

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

    // Initialize TimescaleDB
    val pgConnectOptions =
      pgConnectOptionsOf(
        port = TIMESCALE_PORT,
        host = TIMESCALE_HOST,
        database = "postgres",
        user = "postgres",
        password = "biot",
        cachePreparedStatements = true
      )
    pgPool = PgPool.pool(vertx, pgConnectOptions, poolOptionsOf())

    // Initialize OpenAPI router
    RouterBuilder.create(vertx, "../swagger-api/swagger.yaml").onComplete { ar ->
      if (ar.succeeded()) {
        // Spec loaded with success
        val routerBuilder = ar.result()

        // Relays
        routerBuilder.operation("registerRelay").handler(::registerRelayHandler)
        routerBuilder.operation("getRelays").handler(::getRelaysHandler)
        routerBuilder.operation("getRelay").handler(::getRelayHandler)
        routerBuilder.operation("updateRelay").handler(::updateRelayHandler)
        routerBuilder.operation("deleteRelay").handler(::deleteRelayHandler)

        // Users
        routerBuilder.operation("registerUser").handler(::registerUserHandler)
        routerBuilder.operation("getUsers").handler(::getUsersHandler)
        routerBuilder.operation("getUser").handler(::getUserHandler)
        routerBuilder.operation("updateUser").handler(::updateUserHandler)
        routerBuilder.operation("deleteUser").handler(::deleteUserHandler)
        routerBuilder.operation("authenticate").handler(::authenticateHandler)

        // Items
        routerBuilder.operation("registerItem").handler(::registerItemHandler)
        routerBuilder.operation("getItems").handler(::getItemsHandler)
        routerBuilder.operation("getItem").handler(::getItemHandler)
        routerBuilder.operation("deleteItem").handler(::deleteItemHandler)
        routerBuilder.operation("updateItem").handler(::updateItemHandler)

        val router: Router = routerBuilder.createRouter()
        vertx.createHttpServer().requestHandler(router).listen(HTTP_PORT).onComplete {
          startPromise?.complete()
        }
      } else {
        // Something went wrong during router builder initialization
        logger.error("Could not initialize router builder", ar.cause())
      }
    }
  }

  // Relays handlers

  /**
   * Handles a registerRelay request.
   */
  private fun registerRelayHandler(ctx: RoutingContext) {
    logger.info("New register request")
    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      // Create the relay
      val password: String = json["mqttPassword"]
      val hashedPassword = password.saltAndHash(mongoAuthRelays)
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

  /**
   * Handles a getRelays request.
   */
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

  /**
   * Handles a getRelay request.
   */
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

  /**
   * Handles an updateRelay request.
   */
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
        // Put the beacon information in the JSON to send to the relay
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

  /**
   * Handles a deleteRelay request.
   */
  private fun deleteRelayHandler(ctx: RoutingContext) {
    val relayID = ctx.pathParam("id")
    logger.info("New deleteRelay request for relay $relayID")
    val query = jsonObjectOf("relayID" to relayID)
    mongoClient.removeDocument(RELAYS_COLLECTION, query).onSuccess {
      ctx.end()
    }.onFailure { error ->
      logger.error("Could not delete relay", error)
      ctx.fail(500, error)
    }
  }

  // Users handlers

  /**
   * Handles a registerUser request.
   */
  private fun registerUserHandler(ctx: RoutingContext) {
    logger.info("New user request")
    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      // Create the user
      val password: String = json["password"]
      val hashedPassword = password.saltAndHash(mongoAuthUsers)
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

  /**
   * Handles a getUsers request.
   */
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

  /**
   * Handles a getUser request.
   */
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

  /**
   * Handles an updateUser request.
   */
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

  /**
   * Handles a deleteUser request.
   */
  private fun deleteUserHandler(ctx: RoutingContext) {
    val userID = ctx.pathParam("id")
    logger.info("New deleteUser request for user $userID")
    val query = jsonObjectOf("userID" to userID)
    mongoClient.removeDocument(USERS_COLLECTION, query).onSuccess {
      ctx.end()
    }.onFailure { error ->
      logger.error("Could not delete user", error)
      ctx.fail(500, error)
    }
  }

  /**
   * Handles an authenticate request.
   */
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

  /**
   * Handles a registerItem request.
   */
  private fun registerItemHandler(ctx: RoutingContext) {
    logger.info("New registerItem request")

    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      // Extract the information from the payload and insert the item in TimescaleDB
      val beacon: String = json["beacon"]
      val category: String = json["category"]
      val service: String = json["service"]
      pgPool.preparedQuery(INSERT_ITEM)
        .execute(Tuple.of(beacon, category, service))
        .onSuccess {
          logger.info("New item registered")
          val row = it.iterator().next()
          ctx.end(row.getInteger("id").toString())
        }.onFailure { error ->
          logger.error("Could not register item", error)
          ctx.fail(500, error)
        }
    }
  }

  /**
   * Handles a getItems request.
   */
  private fun getItemsHandler(ctx: RoutingContext) {
    logger.info("New getItems request")
    pgPool.preparedQuery(GET_ITEMS)
      .execute()
      .onSuccess { res ->
        val result = if (res.size() == 0) listOf() else res.map { it.toItemJson() }

        ctx.response()
          .putHeader("Content-Type", "application/json")
          .end(JsonArray(result).encode())
      }.onFailure { error ->
        logger.error("Could not get items", error)
        ctx.fail(500, error)
      }
  }

  /**
   * Handles a getItem request.
   */
  private fun getItemHandler(ctx: RoutingContext) {
    val itemID = ctx.pathParam("id")
    logger.info("New getItem request for item $itemID")

    pgPool.preparedQuery(GET_ITEM)
      .execute(Tuple.of(itemID.toInt())) // the id needs to be converted to Int, as the DB stores it as an integer
      .onSuccess { res ->
        if (res.size() == 0) {
          // No item found, fail
          ctx.fail(404)
          return@onSuccess
        }

        val result: JsonObject = res.iterator().next().toItemJson()

        ctx.response()
          .putHeader("Content-Type", "application/json")
          .end(result.encode())
      }.onFailure { error ->
        logger.error("Could not get item", error)
        ctx.fail(500, error)
      }
  }

  /**
   * Handles an updateItem request.
   */
  private fun updateItemHandler(ctx: RoutingContext) {
    val itemID = ctx.pathParam("id")
    logger.info("New updateItem request for item $itemID")

    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      // Extract the information from the payload and update the item in TimescaleDB
      val beacon: String = json["beacon"]
      val category: String = json["category"]
      val service: String = json["service"]
      pgPool.preparedQuery(UPDATE_ITEM)
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

  /**
   * Handles a deleteItem request.
   */
  private fun deleteItemHandler(ctx: RoutingContext) {
    val itemID = ctx.pathParam("id")
    logger.info("New deleteItem request for item $itemID")

    pgPool.preparedQuery(DELETE_ITEM)
      .execute(Tuple.of(itemID.toInt())) // the id needs to be converted to Int, as the DB stores it as an integer
      .onSuccess {
        ctx.end()
      }.onFailure { error ->
        logger.error("Could not delete item", error)
        ctx.fail(500, error)
      }
  }
}
