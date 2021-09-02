/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud

import arrow.fx.coroutines.parZip
import ch.biot.backend.crud.queries.*
import ch.biot.backend.crud.updates.PublishMessageException
import ch.biot.backend.crud.updates.UpdateType
import ch.biot.backend.crud.updates.UpdatesManager
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoAuthentication
import io.vertx.ext.auth.mongo.MongoUserUtil
import io.vertx.ext.mongo.MongoClient
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.openapi.Operation
import io.vertx.ext.web.openapi.RouterBuilder
import io.vertx.ext.web.validation.BadRequestException
import io.vertx.ext.web.validation.BodyProcessorException
import io.vertx.ext.web.validation.ParameterProcessorException
import io.vertx.kotlin.core.eventbus.eventBusOptionsOf
import io.vertx.kotlin.core.http.httpServerOptionsOf
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.core.vertxOptionsOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import io.vertx.kotlin.ext.mongo.findOptionsOf
import io.vertx.kotlin.ext.mongo.updateOptionsOf
import io.vertx.kotlin.pgclient.pgConnectOptionsOf
import io.vertx.kotlin.sqlclient.poolOptionsOf
import io.vertx.pgclient.PgPool
import io.vertx.pgclient.SslMode
import io.vertx.pgclient.pubsub.PgSubscriber
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.net.InetAddress

internal val LOGGER = KotlinLogging.logger {}

class CRUDVerticle : CoroutineVerticle() {

  companion object {
    private const val APPLICATION_JSON = "application/json"
    private const val CONTENT_TYPE = "Content-Type"

    private const val RELAYS_COLLECTION = "relays"
    private const val USERS_COLLECTION = "users"

    private const val ITEMS_TABLE = "items"
    private const val BEACON_DATA_TABLE = "beacon_data"

    private const val RELAYS_UPDATE_ADDRESS = "relays.update"

    private const val ITEM_UNDER_CREATION = "Under creation"
    private const val ITEM_CREATED = "Created"

    const val MAX_ACCESS_CONTROL_STRING_LENGTH = 2048

    const val INTERNAL_SERVER_ERROR_CODE = 500
    private const val UNAUTHORIZED_CODE = 401
    internal const val FORBIDDEN_CODE = 403
    const val BAD_REQUEST_CODE = 400
    internal const val NOT_FOUND_CODE = 404

    private const val SERVER_COMPRESSION_LEVEL = 4

    // User initially inserted in the DB. Public visibility for testing only
    val INITIAL_USER = jsonObjectOf(
      "userID" to "biot_biot",
      "username" to "biot",
      "password" to "biot",
      "accessControlString" to "biot",
      "company" to "biot"
    )

    val INITIAL_RELAY = jsonObjectOf(
      "relayID" to "relay_0",
      "mqttID" to "relay_0",
      "mqttUsername" to "relayBiot_0",
      "mqttPassword" to "relayBiot_0",
      "latitude" to 42,
      "longitude" to 42,
      "floor" to 0,
      "wifi" to jsonObjectOf(
        "ssid" to "Test",
        "password" to "12345678",
        "reset" to false
      ),
      "forceReset" to false,
      "reboot" to false
    )

    val ADMIN_RELAY = jsonObjectOf(
      "relayID" to "relay_biot",
      "mqttID" to "relay_biot",
      "mqttUsername" to "relayBiot_biot",
      "mqttPassword" to "relayBiot_biot"
    )

    private val environment = System.getenv()
    val HTTP_PORT = environment.getOrDefault("HTTP_PORT", "8081").toInt()

    val MONGO_PORT = environment.getOrDefault("MONGO_PORT", "27017").toInt()
    private val MONGO_HOST: String = environment.getOrDefault("MONGO_HOST", "localhost")

    val TIMESCALE_PORT = environment.getOrDefault("TIMESCALE_PORT", "5432").toInt()
    private val TIMESCALE_HOST: String = environment.getOrDefault("TIMESCALE_HOST", "localhost")

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
        LOGGER.error(error) { "Could not start" }
      }
    }
  }

  private lateinit var mongoClient: MongoClient
  private lateinit var mongoUserUtilUsers: MongoUserUtil
  private lateinit var mongoAuthUsers: MongoAuthentication

  // Map from Collection name to Pair of MongoDB authentication utils
  // It is used since there is a collection per company
  private val mongoAuthRelaysCache: MutableMap<String, Pair<MongoUserUtil, MongoAuthentication>> = mutableMapOf()

  private lateinit var pgClient: SqlClient
  private lateinit var pgPool: PgPool // used for transactions only

  override suspend fun start() {
    // Initialize MongoDB
    mongoClient =
      MongoClient.createShared(
        vertx,
        jsonObjectOf(
          "host" to MONGO_HOST,
          "port" to MONGO_PORT,
          "db_name" to "clients",
          "username" to "biot",
          "password" to "biot"
        )
      )

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

    checkInitialUserAndAdd()
    checkInitialRelayAndAdminRelayAndAdd()

    // Initialize TimescaleDB
    val pgConnectOptions =
      pgConnectOptionsOf(
        port = TIMESCALE_PORT,
        host = TIMESCALE_HOST,
        database = "biot",
        user = "biot",
        password = "biot",
        sslMode = if (TIMESCALE_HOST != "localhost") SslMode.REQUIRE else null, // SSL is disabled when testing
        trustAll = true,
        cachePreparedStatements = true
      )
    pgClient = PgPool.client(vertx, pgConnectOptions, poolOptionsOf())
    pgPool = PgPool.pool(vertx, pgConnectOptions, poolOptionsOf())
    val pgSubscriber = PgSubscriber.subscriber(vertx, pgConnectOptions).apply {
      reconnectPolicy { retries ->
        // Reconnect at most 10 times after 1000 ms each
        if (retries < 10) 1000L else -1L
      }
    }

    // Initialize the updates manager
    val updatesManager = UpdatesManager(vertx.eventBus())

    // Subscribe to items' updates
    pgSubscriber.subscribeToItemsUpdates(updatesManager).connect().await()

    try {
      // Initialize OpenAPI router
      val routerBuilder = RouterBuilder.create(vertx, "swagger.yaml").await()
      // Spec loaded with success

      // Relays
      routerBuilder.operation("registerRelay").coroutineHandler(::registerRelayHandler)
      routerBuilder.operation("getRelays").coroutineHandler(::getRelaysHandler)
      routerBuilder.operation("getRelay").coroutineHandler(::getRelayHandler)
      routerBuilder.operation("updateRelay").coroutineHandler(::updateRelayHandler)
      routerBuilder.operation("deleteRelay").coroutineHandler(::deleteRelayHandler)

      // Users
      routerBuilder.operation("registerUser").coroutineHandler(::registerUserHandler)
      routerBuilder.operation("getUsers").coroutineHandler(::getUsersHandler)
      routerBuilder.operation("getUser").coroutineHandler(::getUserHandler)
      routerBuilder.operation("updateUser").coroutineHandler(::updateUserHandler)
      routerBuilder.operation("deleteUser").coroutineHandler(::deleteUserHandler)
      routerBuilder.operation("authenticate").coroutineHandler(::authenticateHandler)

      // Items
      routerBuilder.operation("registerItem").coroutineHandler(::registerItemHandler)
      routerBuilder.operation("getItems").coroutineHandler(::getItemsHandler)
      routerBuilder.operation("getItem").coroutineHandler(::getItemHandler)
      routerBuilder.operation("getClosestItems").coroutineHandler(::getClosestItemsHandler)
      routerBuilder.operation("deleteItem").coroutineHandler(::deleteItemHandler)
      routerBuilder.operation("updateItem").coroutineHandler(::updateItemHandler)

      routerBuilder.operation("getCategories").coroutineHandler(::getCategoriesHandler)
      routerBuilder.operation("getCategory").coroutineHandler(::getCategoryHandler)
      routerBuilder.operation("createCategory").coroutineHandler(::createCategoryHandler)
      routerBuilder.operation("deleteCategory").coroutineHandler(::deleteCategoryHandler)
      routerBuilder.operation("updateCategory").coroutineHandler(::updateCategoryHandler)

      routerBuilder.operation("getSnapshots").coroutineHandler(::getSnapshotsHandler)
      routerBuilder.operation("getSnapshot").coroutineHandler(::getSnapshotHandler)
      routerBuilder.operation("deleteSnapshot").coroutineHandler(::deleteSnapshotHandler)
      routerBuilder.operation("compareSnapshots").coroutineHandler(::compareSnapshotsHandler)
      routerBuilder.operation("createSnapshot").coroutineHandler(::createSnapshotHandler)

      // Analytics
      routerBuilder.operation("analyticsGetStatus").coroutineHandler(::analyticsGetStatusHandler)

      // Health checks and error handling
      val router: Router = routerBuilder.createRouter()
      router.errorHandler(BAD_REQUEST_CODE, ::badRequestErrorHandler)
      router.get("/health/live").handler(::livenessCheckHandler)
      router.get("/health/ready").handler(::readinessCheckHandler)

      try {
        vertx.createHttpServer(
          httpServerOptionsOf(
            compressionSupported = true,
            compressionLevel = SERVER_COMPRESSION_LEVEL,
            decompressionSupported = true
          )
        )
          .requestHandler(router)
          .listen(HTTP_PORT)
          .await()
        LOGGER.info { "HTTP server listening on port $HTTP_PORT" }
      } catch (error: Throwable) {
        LOGGER.error(error) { "Could not start HTTP server" }
      }
    } catch (error: Throwable) {
      // Something went wrong during router builder initialization
      LOGGER.error(error) { "Could not initialize router builder" }
    }
  }

  /**
   * Check that the initial admin BioT user is in the DB, add it if not
   */
  private suspend fun checkInitialUserAndAdd() {
    try {
      val query = jsonObjectOf("userID" to INITIAL_USER["userID"])
      val initialUser = mongoClient.findOne(USERS_COLLECTION, query, jsonObjectOf()).await()
      if (initialUser == null) {
        val password: String = INITIAL_USER["password"]
        val hashedPassword = password.saltAndHash(mongoAuthUsers)
        val docID = mongoUserUtilUsers.createHashedUser(INITIAL_USER["username"], hashedPassword).await()

        val queryInsert = jsonObjectOf("_id" to docID)
        val extraInfo = jsonObjectOf(
          "\$set" to INITIAL_USER.copy().apply {
            remove("password")
          }
        )
        mongoClient.findOneAndUpdate(USERS_COLLECTION, queryInsert, extraInfo).await()
      }
    } catch (error: Throwable) {
      LOGGER.error(error) { "Could not create the initial user in the DB." }
    }
  }

  /**
   * Check that the initial default BioT relay is in the DB, add it if not
   */
  private suspend fun checkInitialRelayAndAdminRelayAndAdd() {
    try {
      val collection = RELAYS_COLLECTION
      val queryFind = jsonObjectOf("relayID" to INITIAL_RELAY["relayID"])
      val initialRelay = mongoClient.findOne(collection, queryFind, jsonObjectOf()).await()
      if (initialRelay == null) {
        val password: String = INITIAL_RELAY["mqttPassword"]
        val helpers = createMongoAuthUtils(collection)
        mongoAuthRelaysCache[collection] = helpers
        val (mongoUserUtilRelays, mongoAuthRelays) = helpers
        val hashedPassword = password.saltAndHash(mongoAuthRelays)
        val docID = mongoUserUtilRelays.createHashedUser(INITIAL_RELAY["mqttUsername"], hashedPassword).await()

        val query = jsonObjectOf("_id" to docID)
        val extraInfo = jsonObjectOf(
          "\$set" to INITIAL_RELAY.copy().apply {
            remove("mqttPassword")
          }
        )
        mongoClient.findOneAndUpdate(collection, query, extraInfo).await()
      }

      val queryFindAdmin = jsonObjectOf("relayID" to ADMIN_RELAY["relayID"])
      val adminRelay = mongoClient.findOne(collection, queryFindAdmin, jsonObjectOf()).await()
      if (adminRelay == null) {
        val password: String = ADMIN_RELAY["mqttPassword"]
        if(mongoAuthRelaysCache[collection] == null){
          mongoAuthRelaysCache[collection] = createMongoAuthUtils(collection)
        }
        val (mongoUserUtilRelays, mongoAuthRelays) = mongoAuthRelaysCache[collection]!!
        val hashedPassword = password.saltAndHash(mongoAuthRelays)
        val docID = mongoUserUtilRelays.createHashedUser(ADMIN_RELAY["mqttUsername"], hashedPassword).await()

        val query = jsonObjectOf("_id" to docID)
        val extraInfo = jsonObjectOf(
          "\$set" to ADMIN_RELAY.copy().apply {
            remove("mqttPassword")
          }
        )
        mongoClient.findOneAndUpdate(collection, query, extraInfo).await()
      }
    } catch (error: Throwable) {
      LOGGER.error(error) { "Could not create the initial relays in the DB." }
    }
  }

  /**
   * Handles a bad request error.
   */
  private fun badRequestErrorHandler(ctx: RoutingContext) {
    val statusCode: Int = ctx.statusCode()
    val response = ctx.response()

    val failure = ctx.failure()
    if (failure is BadRequestException) {
      val errorMessage = when (failure) {
        is ParameterProcessorException -> {
          "Something went wrong while parsing/validating a parameter."
        }
        is BodyProcessorException -> {
          "Something went while parsing/validating the body."
        }
        else -> {
          "A request predicate is unsatisfied."
        }
      }

      LOGGER.error(failure) { errorMessage }
      response.setStatusCode(statusCode).end(errorMessage)
    }
  }

  // Analytics handlers

  /**
   * Handles a getStatus request.
   */
  private suspend fun analyticsGetStatusHandler(ctx: RoutingContext) {
    LOGGER.info { "New analytics get status request" }

    val itemsTable = ctx.getCollection(ITEMS_TABLE)
    val beaconDataTable = ctx.getCollection(BEACON_DATA_TABLE)

    executeWithErrorHandling("Could not get items' status", ctx) {
      val queryResult = pgClient.preparedQuery(getStatus(itemsTable, beaconDataTable)).execute().await()
      val result =
        if (queryResult.size() == 0) jsonObjectOf()
        else {
          // For each service, count the number of available, unavailable and toRepair objects
          queryResult.groupBy { it.getString("service") }.toList().fold(jsonObjectOf()) { json, (service, statuses) ->
            val statusesJson = statuses.fold(jsonObjectOf()) { obj, row ->
              val status = row.getString("status")
              val count = row.getInteger("count")
              if (status != null && count != null) {
                obj.put(status, count)
              }
              obj
            }.apply {
              // Set the missing values to 0
              listOf("available", "unavailable", "toRepair").forEach {
                if (!containsKey(it)) {
                  put(it, 0)
                }
              }
            }
            json.put(service, statusesJson)
          }
        }

      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(result.encode())
    }
  }

  // Health checks handlers

  private fun livenessCheckHandler(ctx: RoutingContext) {
    LOGGER.debug { "Liveness check" }
    ctx.response()
      .putHeader(CONTENT_TYPE, APPLICATION_JSON)
      .end(jsonObjectOf("status" to "UP").encode())
  }

  private fun readinessCheckHandler(ctx: RoutingContext) {
    LOGGER.debug { "Readiness check complete" }
    ctx.response()
      .putHeader(CONTENT_TYPE, APPLICATION_JSON)
      .end(jsonObjectOf("status" to "UP").encode())
  }

  // Relays handlers

  /**
   * Handles a registerRelay request.
   */
  private suspend fun registerRelayHandler(ctx: RoutingContext) {


    LOGGER.info { "New registerRelay request" }
    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      // Get the MongoDB authentication utils associated to the right collection, based on the user's company
      val collection = ctx.getCollection(RELAYS_COLLECTION)
      val (mongoUserUtilRelays, mongoAuthRelays) = if (mongoAuthRelaysCache.containsKey(collection)) {
        mongoAuthRelaysCache[collection]!!
      } else {
        val helpers = createMongoAuthUtils(collection)
        mongoAuthRelaysCache[collection] = helpers
        helpers
      }

      executeWithErrorHandling("Could not register relay", ctx) {
        // Create the relay
        val password: String = json["mqttPassword"]
        val hashedPassword = password.saltAndHash(mongoAuthRelays)
        val docID = mongoUserUtilRelays.createHashedUser(json["mqttUsername"], hashedPassword).await()
        // Update the relay with the data specified in the HTTP request
        val query = jsonObjectOf("_id" to docID)
        val extraInfo = jsonObjectOf(
          "\$set" to json.copy().apply {
            remove("mqttPassword")
          }
        )
        mongoClient.findOneAndUpdate(collection, query, extraInfo).await()
        LOGGER.info { "New relay registered" }
        ctx.end()
      }
    }
  }

  private fun createMongoAuthUtils(collection: String): Pair<MongoUserUtil, MongoAuthentication> {
    val usernameFieldRelays = "mqttUsername"
    val passwordFieldRelays = "mqttPassword"
    val mongoAuthRelaysOptions = mongoAuthenticationOptionsOf(
      collectionName = collection,
      passwordCredentialField = passwordFieldRelays,
      passwordField = passwordFieldRelays,
      usernameCredentialField = usernameFieldRelays,
      usernameField = usernameFieldRelays
    )

    return MongoUserUtil.create(
      mongoClient,
      mongoAuthRelaysOptions,
      mongoAuthorizationOptionsOf()
    ) to MongoAuthentication.create(mongoClient, mongoAuthRelaysOptions)
  }

  /**
   * Handles a getRelays request.
   */
  private suspend fun getRelaysHandler(ctx: RoutingContext) {
    LOGGER.info { "New getRelays request" }

    val collection = ctx.getCollection(RELAYS_COLLECTION)
    executeWithErrorHandling("Could not get relays", ctx) {
      val relays = mongoClient.find(collection, jsonObjectOf()).await()
      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(JsonArray(relays.map(JsonObject::clean)).encode())
    }
  }

  /**
   * Handles a getRelay request.
   */
  private suspend fun getRelayHandler(ctx: RoutingContext) {
    val relayID = ctx.pathParam("id")
    LOGGER.info { "New getRelay request for relay $relayID" }

    val query = jsonObjectOf("relayID" to relayID)
    val collection = ctx.getCollection(RELAYS_COLLECTION)
    executeWithErrorHandling("Could not get relay", ctx) {
      val relay = mongoClient.findOne(collection, query, jsonObjectOf()).await()

      if (relay == null) {
        LOGGER.warn { "Relay $relayID not found" }
        ctx.response().statusCode = NOT_FOUND_CODE
        ctx.end()
        return@executeWithErrorHandling
      }

      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(relay.clean().encode())
    }
  }

  /**
   * Handles an updateRelay request.
   */
  private suspend fun updateRelayHandler(ctx: RoutingContext) {
    val json = ctx.bodyAsJson
    val relayID = ctx.pathParam("id")

    LOGGER.info { "New updateRelay request for relay $relayID" }

    json.validateAndThen(ctx) {
      // Update MongoDB
      val query = jsonObjectOf("relayID" to relayID)
      val update = json {
        obj(
          "\$set" to json.copy().apply {
            remove("beacon")
          },
          "\$currentDate" to obj("lastModified" to true)
        )
      }
      val collection = ctx.getCollection(RELAYS_COLLECTION)
      executeWithErrorHandling("Could not update collection $collection with update JSON $update", ctx) {
        val updatedRelay = mongoClient.findOneAndUpdateWithOptions(
          collection,
          query,
          update,
          findOptionsOf(),
          updateOptionsOf(returningNewDocument = true)
        ).await()

        if (updatedRelay == null) {
          LOGGER.warn { "Relay $relayID not found" }
          ctx.response().statusCode = NOT_FOUND_CODE
          ctx.end()
          return@executeWithErrorHandling
        }

        LOGGER.info { "Successfully updated collection $collection with update JSON $update" }
        // Put the beacon information in the JSON to send to the relay
        val cleanEntry = updatedRelay.cleanForRelay().apply {
          put("beacon", json["beacon"])
        }
        // Send to the RelaysCommunicationVerticle the entry to update the relay
        vertx.eventBus().send(RELAYS_UPDATE_ADDRESS, cleanEntry)
        LOGGER.info { "Update sent to the event bus address $RELAYS_UPDATE_ADDRESS" }
        ctx.end()
      }
    }
  }

  /**
   * Handles a deleteRelay request.
   */
  private suspend fun deleteRelayHandler(ctx: RoutingContext) {
    val relayID = ctx.pathParam("id")
    LOGGER.info { "New deleteRelay request for relay $relayID" }
    val query = jsonObjectOf("relayID" to relayID)
    val collection = ctx.getCollection(RELAYS_COLLECTION)
    executeWithErrorHandling("Could not delete relay", ctx) {
      val result = mongoClient.removeDocument(collection, query).await()

      if (result.removedCount == 0L) {
        LOGGER.warn { "Relay $relayID not found" }
        ctx.response().statusCode = NOT_FOUND_CODE
        ctx.end()
        return@executeWithErrorHandling
      }

      ctx.end()
    }
  }

  // Users handlers

  /**
   * Handles a registerUser request.
   */
  private suspend fun registerUserHandler(ctx: RoutingContext) {
    LOGGER.info { "New registerUser request" }
    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      // Ensures that the company and the accessControlString cannot be empty
      // because json.validateAndThen only checks the format, not the presence
      if (!json.containsKey("company") || !json.containsKey("accessControlString")) {
        ctx.fail(BAD_REQUEST_CODE)
      } else {
        // Create the user
        val password: String = json["password"]
        val hashedPassword = password.saltAndHash(mongoAuthUsers)
        executeWithErrorHandling("Could not register user", ctx) {
          val docID = mongoUserUtilUsers.createHashedUser(json["username"], hashedPassword).await()

          // Update the user with the data specified in the HTTP request
          val query = jsonObjectOf("_id" to docID)
          val extraInfo = jsonObjectOf(
            "\$set" to json.copy().apply {
              remove("password")
            }
          )
          mongoClient.findOneAndUpdate(USERS_COLLECTION, query, extraInfo).await()
          LOGGER.info { "New user registered" }
          ctx.end()
        }
      }

    }
  }

  /**
   * Handles a getUsers request.
   */
  private suspend fun getUsersHandler(ctx: RoutingContext) {
    LOGGER.info { "New getUsers request" }

    executeWithErrorHandling("Could not get users", ctx) {
      val users = mongoClient.find(USERS_COLLECTION, jsonObjectOf()).await()
      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(JsonArray(users.map(JsonObject::clean)).encode())
    }
  }

  /**
   * Handles a getUser request.
   */
  private suspend fun getUserHandler(ctx: RoutingContext) {
    val userID = ctx.pathParam("id")
    LOGGER.info { "New getUser request for user $userID" }

    val query = jsonObjectOf("userID" to userID)
    executeWithErrorHandling("Could not get user", ctx) {
      val user = mongoClient.findOne(USERS_COLLECTION, query, jsonObjectOf()).await()

      if (user == null) {
        LOGGER.warn { "User $userID not found" }
        ctx.response().statusCode = NOT_FOUND_CODE
        ctx.end()
        return@executeWithErrorHandling
      }

      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(user.clean().encode())
    }
  }

  /**
   * Handles an updateUser request.
   */
  private suspend fun updateUserHandler(ctx: RoutingContext) {
    val json = ctx.bodyAsJson
    val userID = ctx.pathParam("id")
    LOGGER.info { "New updateUser request for user $userID" }

    json.validateAndThen(ctx) {
      // Update MongoDB
      val jsonWithSaltedPassword = json.copy().apply {
        put("password", getString("password").saltAndHash(mongoAuthUsers))
      }
      val update = json {
        obj(
          "\$set" to jsonWithSaltedPassword,
          "\$currentDate" to obj("lastModified" to true)
        )
      }

      val query = jsonObjectOf("userID" to userID)
      executeWithErrorHandling("Could not update collection $USERS_COLLECTION with update JSON $update", ctx) {
        val user = mongoClient.findOneAndUpdate(USERS_COLLECTION, query, update).await()

        if (user == null) {
          LOGGER.warn { "User $userID not found" }
          ctx.response().statusCode = NOT_FOUND_CODE
          ctx.end()
          return@executeWithErrorHandling
        }

        LOGGER.info("Successfully updated collection $USERS_COLLECTION with update JSON $update")
        ctx.end()
      }
    }
  }

  /**
   * Handles a deleteUser request.
   */
  private suspend fun deleteUserHandler(ctx: RoutingContext) {
    val userID = ctx.pathParam("id")
    LOGGER.info { "New deleteUser request for user $userID" }
    val query = jsonObjectOf("userID" to userID)
    executeWithErrorHandling("Could not delete user", ctx) {
      val result = mongoClient.removeDocument(USERS_COLLECTION, query).await()

      if (result.removedCount == 0L) {
        LOGGER.warn { "User $userID not found" }
        ctx.response().statusCode = NOT_FOUND_CODE
        ctx.end()
        return@executeWithErrorHandling
      }

      ctx.end()
    }
  }

  /**
   * Handles an authenticate request.
   */
  private suspend fun authenticateHandler(ctx: RoutingContext) {
    LOGGER.info { "New authenticate request" }
    val body = if (ctx.body.length() == 0) {
      jsonObjectOf()
    } else {
      ctx.bodyAsJson
    }

    try {
      val user = mongoAuthUsers.authenticate(body).await()
      val company: String = user["company"]
      val userID: String = user["userID"]

      val json = jsonObjectOf("company" to company, "userID" to userID)
      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(json.encode())
    } catch (error: Throwable) {
      LOGGER.error(error) { "Authentication error" }
      ctx.fail(UNAUTHORIZED_CODE, error)
    }
  }

  // Items

  /**
   * Handles a registerItem request.
   * If the json does not contain an accessControlString, it puts the <company> by default
   * If the json contains a wrongly formatted accessControlString, it sends back a BAD REQUEST CODE
   */
  private suspend fun registerItemHandler(ctx: RoutingContext) {
    LOGGER.info { "New registerItem request" }

    val json = ctx.bodyAsJson
    json.apply {
      if (getString("status") == null) {
        put("status", ITEM_UNDER_CREATION)
      }
    }.validateAndThen(ctx) {
      // Extract the information from the payload and insert the item in TimescaleDB
      val id: Int? = json["id"]
      val company: String = ctx.queryParams()["company"]
      // If no accessControlString is given, put the company
      if (!json.containsKey("accessControlString")) {
        json.put("accessControlString", company)
      }

      // If an invalid accessControlString is given, it sends a Bad request code
      if (!validateAccessControlString(json.getString("accessControlString"), company)) {
        ctx.fail(BAD_REQUEST_CODE)
      } else {
        val info = extractItemInformation(json).map { pair -> pair.second }

        val table = ctx.getCollection(ITEMS_TABLE)

        val executedQuery =
          if (id != null) pgClient.preparedQuery(insertItem(table, true)).execute(Tuple.of(id, *info.toTypedArray()))
          else pgClient.preparedQuery(insertItem(table, false)).execute(Tuple.tuple(info))

        executeWithErrorHandling("Could not register item", ctx) {
          val row = executedQuery.await().iterator().next()
          val itemID = row.getInteger("id").toString()
          LOGGER.info { "New item $itemID registered" }
          ctx.end(itemID)
        }
      }

    }
  }

  /**
   * Handles a getItems request.
   */
  private suspend fun getItemsHandler(ctx: RoutingContext) {
    LOGGER.info { "New getItems request" }

    val params = ctx.queryParams()
    val itemsTable = ctx.getCollection(ITEMS_TABLE)
    val beaconDataTable = ctx.getCollection(BEACON_DATA_TABLE)
    val accessControlString: String = ctx.getAccessControlString()

    val executedQuery = if (params.contains("categoryID")) {
      pgClient.preparedQuery(getItemsWithCategory(itemsTable, beaconDataTable, accessControlString))
        .execute(Tuple.of(params["categoryID"].toInt()))
    } else {
      pgClient.preparedQuery(getItems(itemsTable, beaconDataTable, accessControlString)).execute()
    }

    executeWithErrorHandling("Could not get items", ctx) {
      val queryResult = executedQuery.await()
      val result = if (queryResult.size() == 0) listOf() else queryResult.map(Row::toItemJson)

      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(JsonArray(result).encode())
    }
  }

  private suspend fun getClosestItemsHandler(ctx: RoutingContext) {
    LOGGER.info { "New getClosestItems request" }

    val params = ctx.queryParams()
    val latitude = params["latitude"].toDouble()
    val longitude = params["longitude"].toDouble()
    val accessControlString: String = ctx.getAccessControlString()

    val itemsTable = ctx.getCollection(ITEMS_TABLE)
    val beaconDataTable = ctx.getCollection(BEACON_DATA_TABLE)

    val executedQuery = if (params.contains("categoryID")) {
      pgClient.preparedQuery(
        getClosestItemsWithCategory(
          itemsTable,
          beaconDataTable,
          accessControlString
        )
      )
        .execute(Tuple.of(params["categoryID"].toInt(), latitude, longitude))
    } else {
      pgClient.preparedQuery(getClosestItems(itemsTable, beaconDataTable, accessControlString))
        .execute(Tuple.of(latitude, longitude))
    }

    executeWithErrorHandling("Could not get closest items", ctx) {
      val queryResult = executedQuery.await()
      val result = if (queryResult.size() == 0) jsonObjectOf() else {
        queryResult.fold(jsonObjectOf()) { json, row ->
          val floor = row.getInteger("floor")?.toString() ?: "unknown"
          val closestItems = row.getJsonArray("closest_items")
            .take(5)
            .map {
              (it as JsonObject).apply {
                put("timestamp", getString("time"))
                remove("time")
                remove("mac")
              }
            } // only the 5 closest
          json.put(floor, closestItems)
        }
      }

      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(result.encode())
    }
  }

  /**
   * Handles a getItem request.
   */
  private suspend fun getItemHandler(ctx: RoutingContext) {
    val itemID = ctx.pathParam("id").toInt() // the id needs to be converted to Int, as the DB stores it as an integer
    LOGGER.info { "New getItem request for item $itemID" }

    val itemsTable = ctx.getCollection(ITEMS_TABLE)
    val beaconDataTable = ctx.getCollection(BEACON_DATA_TABLE)

    val accessControlString: String = ctx.getAccessControlString()

    val executedQuery =
      pgClient.preparedQuery(getItem(itemsTable, beaconDataTable, accessControlString)).execute(Tuple.of(itemID))

    executeWithErrorHandling("Could not get item", ctx) {

      val queryResult = executedQuery.await()
      if (queryResult.size() == 0) {
        // No item found
        LOGGER.warn { "Item $itemID not found" }
        ctx.response().statusCode = NOT_FOUND_CODE
        ctx.end()
        return@executeWithErrorHandling
      }

      val result: JsonObject = queryResult.iterator().next().toItemJson()
      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(result.encode())
    }
  }

  /**
   * Handles an updateItem request.
   * Sends back a 403 if the accessControlString passed as query parameters is insufficient
   */
  private suspend fun updateItemHandler(ctx: RoutingContext) {
    val id = ctx.pathParam("id")
    val isScan = ctx.queryParams().get("scan")?.toBoolean()
    LOGGER.info { "New updateItem request for item $id" }

    val json = ctx.bodyAsJson
    json.apply {
      if (isScan == true && getString("status") == ITEM_UNDER_CREATION) {
        put("status", ITEM_CREATED)
      }
    }.validateAndThen(ctx) {
      // Extract the information from the payload and update the item in TimescaleDB
      val info = extractItemInformation(json, keepNulls = false)
      val data = listOf(id.toInt(), *info.map { it.second }.toTypedArray())
      val itemsTable = ctx.getCollection(ITEMS_TABLE)
      val beaconDataTable = ctx.getCollection(BEACON_DATA_TABLE)

      val params = ctx.queryParams()
      val company = params["company"]

      if (info.any { (key, accessControlString) ->
          key == "accesscontrolstring" && !validateAccessControlString(accessControlString as String, company)
        }) {
        // The query tries to update the item's accessControlString with an invalid one --> fails
        ctx.fail(BAD_REQUEST_CODE)
        return@validateAndThen
      }

      val keys = info.map { it.first }
      val accessControlString = ctx.getAccessControlString()

      executeWithErrorHandling("Could not update item $id", ctx) {
        val getQueryResultIterator =
          pgClient.preparedQuery(getItem(itemsTable, beaconDataTable, company)).execute(Tuple.of(id.toInt())).await()
            .iterator()
        if (!getQueryResultIterator.hasNext()) {
          LOGGER.warn { "Item $id not found" }
          ctx.response().statusCode = NOT_FOUND_CODE
          ctx.end()
          return@executeWithErrorHandling
        }

        val item = getQueryResultIterator.next().toItemJson()
        val itemAcString = item.getString("accessControlString")

        // Check that the given accessControlString gives access to the resource
        if (!hasAcStringAccess(accessControlString, itemAcString)) {
          // Access refused
          LOGGER.error { "ACCESS FORBIDDEN updateItem for itemAcString $itemAcString and acString $accessControlString" }
          ctx.fail(FORBIDDEN_CODE)
          return@executeWithErrorHandling
        }

        pgClient.preparedQuery(updateItem(itemsTable, keys, accessControlString)).execute(Tuple.tuple(data)).await()
        LOGGER.info { "Successfully updated item $id" }
        ctx.end()
      }
    }
  }

  /**
   * Handles a deleteItem request.
   */
  private suspend fun deleteItemHandler(ctx: RoutingContext) {
    val itemID = ctx.pathParam("id").toInt() // the id needs to be converted to Int, as the DB stores it as an integer
    LOGGER.info { "New deleteItem request for item $itemID" }

    val table = ctx.getCollection(ITEMS_TABLE)
    val accessControlString: String = ctx.getAccessControlString()

    executeWithErrorHandling("Could not delete item", ctx) {
      val result = pgClient.preparedQuery(deleteItem(table, accessControlString)).execute(Tuple.of(itemID)).await()

      if (result.rowCount() == 0) {
        LOGGER.warn { "Item $itemID not found" }
        ctx.response().statusCode = NOT_FOUND_CODE
        ctx.end()
        return@executeWithErrorHandling
      }

      ctx.end()
    }
  }

  /**
   * Handles a getCategories request.
   */
  private suspend fun getCategoriesHandler(ctx: RoutingContext) {
    LOGGER.info { "New getCategories request" }

    val company = ctx.queryParams()["company"]
    val executedQuery = pgClient.preparedQuery(getCategories()).execute(Tuple.of(company))

    executeWithErrorHandling("Could not get categories", ctx) {
      val queryResult = executedQuery.await()
      val result = if (queryResult.size() == 0) listOf() else queryResult.map { row ->
        jsonObjectOf(
          "id" to row.getInteger("id"),
          "name" to row.getString("name")
        )
      }

      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(JsonArray(result).encode())
    }
  }

  /**
   * Handles a getCategory request.
   */
  private suspend fun getCategoryHandler(ctx: RoutingContext) {
    val categoryID = ctx.pathParam("id").toInt()
    val company = ctx.queryParams().get("company")
    LOGGER.info { "New getCategory request for category $categoryID" }

    executeWithErrorHandling("Could not get category", ctx) {
      val queryResult = pgClient.preparedQuery(getCategories()).execute(Tuple.of(company)).await().map(Row::toJson)
        .filter { it.getInteger("id") == categoryID }
      if (queryResult.isEmpty()) {
        // No category found
        LOGGER.warn { "Category $categoryID not found" }
        ctx.response().statusCode = NOT_FOUND_CODE
        ctx.end()
        return@executeWithErrorHandling
      }

      val category: JsonObject = queryResult[0] // There will be only one item, since the id is unique
      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(category.encode())
    }
  }

  /**
   * Handles an updateCategory request.
   */
  private suspend fun updateCategoryHandler(ctx: RoutingContext) {
    val categoryID = ctx.pathParam("id").toInt()
    val company = ctx.queryParams().get("company")
    LOGGER.info { "New updateCategory request for category $categoryID" }

    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      val name = json.getString("name")

      executeWithErrorHandling("Could not update category", ctx) {
        if (ctx.failIfNoCategoriesIdsInCompany(pgClient, listOf(categoryID), company)) {
          return@executeWithErrorHandling
        }
        pgClient.preparedQuery(updateCategory()).execute(Tuple.of(name, categoryID)).await()

        LOGGER.info { "Successfully updated category $categoryID" }
        ctx.end()
      }
    }
  }

  /**
   * Handles a deleteCategory request.
   */
  private suspend fun deleteCategoryHandler(ctx: RoutingContext) {
    val categoryID = ctx.pathParam("id").toInt()
    val company = ctx.queryParams().get("company")
    LOGGER.info { "New deleteCategory request for category $categoryID" }

    executeWithErrorHandling("Could not delete category", ctx) {
      if (ctx.failIfNoCategoriesIdsInCompany(pgClient, listOf(categoryID), company)) {
        return@executeWithErrorHandling
      }
      pgClient.preparedQuery(deleteCategory()).execute(Tuple.of(categoryID)).await()

      ctx.end()
    }
  }

  /**
   * Handles a createCategory request.
   */
  private suspend fun createCategoryHandler(ctx: RoutingContext) {
    LOGGER.info { "New createCategory request" }

    val json = ctx.bodyAsJson
    json.validateAndThen(ctx) {
      val name = json.getString("name")
      val company = ctx.queryParams()["company"]

      executeWithErrorHandling("Could not create category", ctx) {
        val existingCategories =
          pgClient.preparedQuery(getCategories()).execute(Tuple.of(company)).await().map(Row::toJson)
        if (existingCategories.any { it.getString("name") == name }) {
          ctx.response().statusMessage = "Category with name = $name already exists for company = $company"
          ctx.fail(BAD_REQUEST_CODE)
          return@executeWithErrorHandling
        }

        val categoryID = pgPool.withTransaction { conn ->
          // Insert a new category in the categories table
          conn.preparedQuery(insertCategory()).execute(Tuple.of(name)).compose { res ->
            val id = res.iterator().next().getInteger("id")
            // Add the category to the company_categories table
            conn.preparedQuery(addCategoryToCompany()).execute(Tuple.of(id, company)).compose {
              Future.succeededFuture(id)
            }
          }
        }.await()

        LOGGER.info { "New category $categoryID created" }
        ctx.end(categoryID.toString())
      }
    }
  }

  /**
   * Handles a getSnapshots request.
   */
  private suspend fun getSnapshotsHandler(ctx: RoutingContext) {
    LOGGER.info { "New getSnapshots request" }

    val table = ctx.getCollection(ITEMS_TABLE)
    val accessControlString: String = ctx.getAccessControlString()

    executeWithErrorHandling("Could not get snapshots", ctx) {
      val snapshots =
        pgClient.preparedQuery(getSnapshots(table, accessControlString)).execute().await().toSnapshotsList()

      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(JsonArray(snapshots).encode())
    }
  }

  /**
   * Handles a getSnapshot request.
   */
  private suspend fun getSnapshotHandler(ctx: RoutingContext) {
    val snapshotID = ctx.pathParam("id").toInt()
    LOGGER.info { "New getSnapshot request for snapshot $snapshotID" }

    val table = ctx.getCollection(ITEMS_TABLE)
    val accessControlString: String = ctx.getAccessControlString()

    executeWithErrorHandling("Could not get snapshot", ctx) {
      if (!pgClient.tableExists(getSnapshotTableName(table, snapshotID))) {
        // Snapshot not found
        LOGGER.warn { "Snapshot $snapshotID not found" }
        ctx.response().statusCode = NOT_FOUND_CODE
        ctx.end()
        return@executeWithErrorHandling
      }

      if (ctx.failIfNoRightsToSnapshots(pgClient, table, accessControlString, listOf(snapshotID), "getSnapshot")) {
        return@executeWithErrorHandling
      }

      val queryResult = pgClient.preparedQuery(getSnapshot(table, snapshotID)).execute().await()
      val result =
        if (queryResult.size() == 0) listOf() else queryResult.map { it.toItemJson(includeBeaconData = false) }

      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(JsonArray(result).encode())
    }
  }

  /**
   * Handles a deleteSnapshot request.
   */
  private suspend fun deleteSnapshotHandler(ctx: RoutingContext) {
    val snapshotID = ctx.pathParam("id").toInt()
    LOGGER.info { "New deleteSnapshot request for snapshot $snapshotID" }

    val table = ctx.getCollection(ITEMS_TABLE)
    val accessControlString: String = ctx.getAccessControlString()

    executeWithErrorHandling("Could not delete snapshot", ctx) {
      if (!pgClient.tableExists(getSnapshotTableName(table, snapshotID))) {
        // Snapshot not found
        LOGGER.warn { "Snapshot $snapshotID not found" }
        ctx.response().statusCode = NOT_FOUND_CODE
        ctx.end()
        return@executeWithErrorHandling
      }

      if (ctx.failIfNoRightsToSnapshots(pgClient, table, accessControlString, listOf(snapshotID), "deleteSnapshot")) {
        return@executeWithErrorHandling
      }

      // In a transaction, drop the snapshot table and remove the entry from the snapshots table
      pgPool.withTransaction { conn ->
        conn.preparedQuery(dropSnapshotTable(table, snapshotID)).execute().compose {
          conn.preparedQuery(deleteSnapshot(table, accessControlString)).execute(Tuple.of(snapshotID))
        }
      }.await()

      ctx.end()
    }
  }

  /**
   * Handles a compareSnapshots request.
   */
  private suspend fun compareSnapshotsHandler(ctx: RoutingContext) {
    fun RowSet<Row>.toJsonArray() = JsonArray(if (this.size() == 0) listOf() else this.map {
      it.toItemJson(includeBeaconData = false)
    })

    val queryParams = ctx.queryParams()
    val firstSnapshotID = queryParams["firstSnapshotId"].toInt()
    val secondSnapshotID = queryParams["secondSnapshotId"].toInt()

    LOGGER.info { "New compareSnapshots request between snapshots $firstSnapshotID and $secondSnapshotID" }

    val table = ctx.getCollection(ITEMS_TABLE)
    val accessControlString: String = ctx.getAccessControlString()

    executeWithErrorHandling("Could not compare snapshots", ctx) {
      val (firstSnapshotNotFound, secondSnapshotNotFound) = parZip(
        { !pgClient.tableExists(getSnapshotTableName(table, firstSnapshotID)) },
        { !pgClient.tableExists(getSnapshotTableName(table, secondSnapshotID)) }
      ) { firstSnapshotNotFound, secondSnapshotNotFound -> firstSnapshotNotFound to secondSnapshotNotFound }

      if (firstSnapshotNotFound) {
        // First snapshot not found
        LOGGER.warn { "First snapshot $firstSnapshotID not found" }
        ctx.response().statusCode = NOT_FOUND_CODE
        ctx.end()
        return@executeWithErrorHandling
      }

      if (secondSnapshotNotFound) {
        // Second snapshot not found
        LOGGER.warn { "Second snapshot $secondSnapshotID not found" }
        ctx.response().statusCode = NOT_FOUND_CODE
        ctx.end()
        return@executeWithErrorHandling
      }

      if (ctx.failIfNoRightsToSnapshots(
          pgClient,
          table,
          accessControlString,
          listOf(firstSnapshotID, secondSnapshotID),
          "compareSnapshots"
        )
      ) {
        return@executeWithErrorHandling
      }

      // Compute all the joins in parallel
      parZip(
        vertx.dispatcher(),
        {
          pgClient.preparedQuery(leftOuterJoinFromSnapshots(table, firstSnapshotID, secondSnapshotID)).execute().await()
        },
        {
          pgClient.preparedQuery(rightOuterJoinFromSnapshots(table, firstSnapshotID, secondSnapshotID)).execute()
            .await()
        },
        {
          pgClient.preparedQuery(innerJoinFromSnapshots(table, firstSnapshotID, secondSnapshotID)).execute().await()
        }) { onlyFirstResult, onlySecondResult, inCommonResult ->
        val json = jsonObjectOf(
          "onlyFirst" to onlyFirstResult.toJsonArray(),
          "onlySecond" to onlySecondResult.toJsonArray(),
          "inCommon" to inCommonResult.toJsonArray()
        )

        ctx.end(json.encode())
      }
    }
  }

  /**
   * Handles a createSnapshot request.
   */
  private suspend fun createSnapshotHandler(ctx: RoutingContext) {
    LOGGER.info { "New createSnapshot request" }

    val table = ctx.getCollection(ITEMS_TABLE)
    val accessControlString: String = ctx.getAccessControlString()

    executeWithErrorHandling("Could not create snapshot", ctx) {
      val snapshotID = pgPool.withTransaction { conn ->
        // Insert a new snapshot in the snapshots table
        conn.preparedQuery(createSnapshot(table)).execute(Tuple.of(accessControlString)).compose { res ->
          val snapshotID = res.iterator().next().getInteger("id")
          // Copy the table, creating a new one representing the snapshot
          pgClient.preparedQuery(snapshotTable(table, snapshotID)).execute().compose {
            Future.succeededFuture(snapshotID)
          }
        }
      }.await()

      LOGGER.info { "New snapshot $snapshotID created" }
      ctx.end(snapshotID.toString())
    }
  }

  /**
   * An extension method for simplifying coroutines usage with Vert.x Web routers.
   */
  private fun Operation.coroutineHandler(fn: suspend (RoutingContext) -> Unit): Operation =
    handler { ctx ->
      launch(ctx.vertx().dispatcher()) {
        try {
          fn(ctx)
        } catch (e: Exception) {
          ctx.fail(e)
        }
      }
    }

  /**
   * Subscribes to items updates (through Postgres LISTEN channels) with the given [UpdatesManager].
   */
  private fun PgSubscriber.subscribeToItemsUpdates(updatesManager: UpdatesManager): PgSubscriber {
    fun publishUpdate(json: JsonObject, updateType: UpdateType) {
      LOGGER.debug { "Received $updateType notification with payload $json" }

      val item = json.getJsonObject("data")?.toItemJson()

      val id = if (updateType == UpdateType.DELETE) {
        json.getInteger("id")
      } else {
        // Item is not null for POST and PUT
        item!!.getInteger("id")
      }

      val table = json.remove("table") as String
      val company = if (table == "items") "biot" else table.split("_")[1]

      try {
        updatesManager.publishItemUpdate(updateType, company, id, item)
      } catch (error: PublishMessageException) {
        LOGGER.error(error) { "Failed to publish $updateType item update" }
      }
    }

    // Subscribe to POST, PUT and DELETE updates
    listOf(UpdateType.POST, UpdateType.PUT, UpdateType.DELETE).forEach { type ->
      channel(type.toString()).handler { payload ->
        launch(vertx.dispatcher()) {
          val json = JsonObject(payload)
          publishUpdate(json, type)
        }
      }
    }

    return this
  }
}
