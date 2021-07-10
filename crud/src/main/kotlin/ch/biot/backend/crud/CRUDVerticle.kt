/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud

import ch.biot.backend.crud.queries.*
import ch.biot.backend.crud.updates.PublishMessageException
import ch.biot.backend.crud.updates.UpdateType
import ch.biot.backend.crud.updates.UpdatesManager
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

    const val INTERNAL_SERVER_ERROR_CODE = 500
    private const val UNAUTHORIZED_CODE = 401
    const val BAD_REQUEST_CODE = 400

    private const val SERVER_COMPRESSION_LEVEL = 4

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
    fun createMongoAuthUtils(collection: String): Pair<MongoUserUtil, MongoAuthentication> {
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
        .end(JsonArray(relays.map { it.clean() }).encode())
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
      mongoClient.removeDocument(collection, query).await()
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

  /**
   * Handles a getUsers request.
   */
  private suspend fun getUsersHandler(ctx: RoutingContext) {
    LOGGER.info { "New getUsers request" }

    executeWithErrorHandling("Could not get users", ctx) {
      val users = mongoClient.find(USERS_COLLECTION, jsonObjectOf()).await()
      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(JsonArray(users.map { it.clean() }).encode())
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
        mongoClient.findOneAndUpdate(USERS_COLLECTION, query, update).await()
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
      mongoClient.removeDocument(USERS_COLLECTION, query).await()
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
      ctx.end(company)
    } catch (error: Throwable) {
      LOGGER.error(error) { "Authentication error" }
      ctx.fail(UNAUTHORIZED_CODE, error)
    }
  }

  // Items

  /**
   * Handles a registerItem request.
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

  /**
   * Handles a getItems request.
   */
  private suspend fun getItemsHandler(ctx: RoutingContext) {
    LOGGER.info { "New getItems request" }
    val params = ctx.queryParams()

    val itemsTable = ctx.getCollection(ITEMS_TABLE)
    val beaconDataTable = ctx.getCollection(BEACON_DATA_TABLE)

    val executedQuery = if (params.contains("category")) {
      pgClient.preparedQuery(getItemsWithCategory(itemsTable, beaconDataTable)).execute(Tuple.of(params["category"]))
    } else {
      pgClient.preparedQuery(getItems(itemsTable, beaconDataTable)).execute()
    }

    executeWithErrorHandling("Could not get items", ctx) {
      val queryResult = executedQuery.await()
      val result = if (queryResult.size() == 0) listOf() else queryResult.map { it.toItemJson() }

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

    val itemsTable = ctx.getCollection(ITEMS_TABLE)
    val beaconDataTable = ctx.getCollection(BEACON_DATA_TABLE)

    val executedQuery = if (params.contains("category")) {
      pgClient.preparedQuery(getClosestItemsWithCategory(itemsTable, beaconDataTable))
        .execute(Tuple.of(params["category"], latitude, longitude))
    } else {
      pgClient.preparedQuery(getClosestItems(itemsTable, beaconDataTable))
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
    executeWithErrorHandling("Could not get item", ctx) {
      val queryResult = pgClient.preparedQuery(getItem(itemsTable, beaconDataTable)).execute(Tuple.of(itemID)).await()
      if (queryResult.size() == 0) {
        // No item found, answer with 404
        ctx.response().statusCode = 404
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
      val table = ctx.getCollection(ITEMS_TABLE)
      executeWithErrorHandling("Could not update item $id", ctx) {
        pgClient.preparedQuery(updateItem(table, info.map { it.first })).execute(Tuple.tuple(data)).await()
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
    executeWithErrorHandling("Could not delete item", ctx) {
      pgClient.preparedQuery(deleteItem(table)).execute(Tuple.of(itemID)).await()
      ctx.end()
    }
  }

  /**
   * Handles a getCategories request.
   */
  private suspend fun getCategoriesHandler(ctx: RoutingContext) {
    LOGGER.info { "New getCategories request" }

    val table = ctx.getCollection(ITEMS_TABLE)
    executeWithErrorHandling("Could not get categories", ctx) {
      val queryResult = pgClient.preparedQuery(getCategories(table)).execute().await()
      val result = if (queryResult.size() == 0) listOf() else queryResult.map { it.getString("category") }

      ctx.response()
        .putHeader(CONTENT_TYPE, APPLICATION_JSON)
        .end(JsonArray(result).encode())
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
