/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.relayscommunication

import io.netty.handler.codec.mqtt.MqttConnectReturnCode
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.*
import io.vertx.core.http.ClientAuth
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.DecodeException
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoAuthentication
import io.vertx.ext.mongo.BulkOperation
import io.vertx.ext.mongo.MongoClient
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.kafka.client.producer.KafkaProducer
import io.vertx.kafka.client.producer.KafkaProducerRecord
import io.vertx.kotlin.core.eventbus.eventBusOptionsOf
import io.vertx.kotlin.core.http.httpServerOptionsOf
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.net.netServerOptionsOf
import io.vertx.kotlin.core.net.pemKeyCertOptionsOf
import io.vertx.kotlin.core.vertxOptionsOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.mqtt.mqttServerOptionsOf
import io.vertx.kotlin.pgclient.pgConnectOptionsOf
import io.vertx.kotlin.sqlclient.poolOptionsOf
import io.vertx.mqtt.MqttEndpoint
import io.vertx.mqtt.MqttServer
import io.vertx.mqtt.messages.MqttPublishMessage
import io.vertx.pgclient.PgPool
import io.vertx.pgclient.SslMode
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit

private val LOGGER = KotlinLogging.logger {}

class RelaysCommunicationVerticle : CoroutineVerticle() {

  companion object {
    internal const val RELAYS_COLLECTION = "relays"
    internal const val UPDATE_PARAMETERS_TOPIC = "update.parameters"
    internal const val RELAYS_MANAGEMENT_TOPIC = "relay.management"
    internal const val INGESTION_TOPIC = "incoming.update"
    internal const val RELAYS_UPDATE_ADDRESS = "relays.update"

    internal const val EMERGENCY_ENDPOINT = "/relays-emergency"
    internal const val CONTENT_TYPE = "Content-Type"
    private const val SERVER_COMPRESSION_LEVEL = 4
    private const val APPLICATION_JSON = "application/json"
    internal const val INTERNAL_SERVER_ERROR_CODE = 500

    private val environment = System.getenv()
    internal val RELAY_REPO_URL = environment.getOrDefault("RELAY_REPO_URL", "git@github.com:B-IoT/relays_biot.git").toString()
    internal val DEFAULT_RELAY_ID = environment.getOrDefault("DEFAULT_RELAY_ID", "relay_0").toString()
    internal val HTTP_PORT = environment.getOrDefault("HTTP_PORT", "8082").toInt()

    internal val MQTT_PORT =
      environment.getOrDefault("MQTT_PORT", "1883").toInt() // Externally (outside the cluster) the port is 443

    private val MONGO_HOST: String = environment.getOrDefault("MONGO_HOST", "localhost")
    internal val MONGO_PORT = environment.getOrDefault("MONGO_PORT", "27017").toInt()

    private val KAFKA_HOST: String = environment.getOrDefault("KAFKA_HOST", "localhost")
    internal val KAFKA_PORT = environment.getOrDefault("KAFKA_PORT", "9092").toInt()

    val TIMESCALE_PORT = environment.getOrDefault("TIMESCALE_PORT", "5432").toInt()
    val TIMESCALE_HOST: String = environment.getOrDefault("TIMESCALE_HOST", "localhost")

    private const val ITEMS_TABLE = "items"

    internal const val LIVENESS_PORT = 1884
    internal const val READINESS_PORT = 1885

    const val UPDATE_CONFIG_INTERVAL_SECONDS: Long = 20

    private lateinit var pgClient: SqlClient

    // It is a field because otherwise, it cannot be used in the lambda of the Handler itself
    private lateinit var periodicUpdateConfig: Handler<Long>

    private val macAddressRegex = "^([a-f0-9]{2}:){5}[a-f0-9]{2}$".toRegex()
    private const val MAX_NUMBER_MAC_MQTT = 1024 // Maximum number of mac addresses in the whitelist in a MQTT message
    private const val CHAR_NUMBER_IN_MAC_ADDRESS = 6 * 2

    @JvmStatic
    fun main(args: Array<String>) {
      val ipv4 = InetAddress.getLocalHost().hostAddress
      val options = vertxOptionsOf(
        clusterManager = HazelcastClusterManager(),
        eventBusOptions = eventBusOptionsOf(host = ipv4, clusterPublicHost = ipv4)
      )

      Vertx.clusteredVertx(options).onSuccess {
        it.deployVerticle(RelaysCommunicationVerticle())
      }.onFailure { error ->
        LOGGER.error(error) { "Could not start" }
      }
    }
  }

  /**
   * Map of subscribed clientIdentifier to Pair<String, client>. The first element of the pair is the company of the client,
   * the second is the MqttEndpoint instance corresponding to the client
   */
  private val clients = mutableMapOf<String, Pair<String, MqttEndpoint>>()

  /**
   * We use this to check whether the config with the whitelist changed or not since last sent, so that we do not overwhelm the relays.
   */
  private val configHashes = mutableMapOf<String, Int>() // map of company to the hash of the config with whitelist

  /**
   * Map from collection name to MongoAuthentication. It is used since there is a collection per company.
   */
  private val mongoAuthRelaysCache: MutableMap<String, MongoAuthentication> = mutableMapOf()

  private lateinit var kafkaProducer: KafkaProducer<String, JsonObject>

  private lateinit var mongoClient: MongoClient

  override suspend fun start() {
    // Initialize the Kafka producer
    kafkaProducer = KafkaProducer.create(
      vertx,
      mapOf(
        "bootstrap.servers" to "$KAFKA_HOST:$KAFKA_PORT",
        "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
        "value.serializer" to "io.vertx.kafka.client.serialization.JsonObjectSerializer",
        "acks" to "1"
      )
    )

    // Initialize MongoDB
    mongoClient = MongoClient.createShared(
      vertx,
      jsonObjectOf(
        "host" to MONGO_HOST, "port" to MONGO_PORT, "db_name" to "clients",
        "username" to "biot",
        "password" to "biot"
      )
    )

    // Add an event bus consumer to handle the JSON received from CRUDVerticle, which needs to be forwarded to the right
    // relay
    vertx.eventBus().consumer<JsonObject>(RELAYS_UPDATE_ADDRESS) { message ->
      val json = message.body()
      LOGGER.info { "Received relay update $json on event bus address $RELAYS_UPDATE_ADDRESS, sending it to client..." }

      val mqttID: String = json["mqttID"]

      // Clean the json from useless fields
      val cleanJson = json.clean()

      // Send the message to the right relay on the MQTT topic "update.parameters"
      clients[mqttID]?.let { client -> launch(vertx.dispatcher()) { sendMessageTo(client.second, cleanJson, UPDATE_PARAMETERS_TOPIC) } }
    }

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

    // Certificate for TLS
    val pemKeyCertOptions = pemKeyCertOptionsOf(certPath = "certificate.pem", keyPath = "certificate_key.pem")
    val netServerOptions = netServerOptionsOf(ssl = true, pemKeyCertOptions = pemKeyCertOptions)
    val mqttServerOptions = mqttServerOptionsOf(
      useWebSocket = TIMESCALE_HOST != "localhost", // SSL is disabled when testing
      ssl = false,
      pemKeyCertOptions = pemKeyCertOptions,
      clientAuth = ClientAuth.REQUEST
    )

    try {
      // TCP server for liveness checks
      vertx.createNetServer(netServerOptions).connectHandler { LOGGER.debug { "Liveness check" } }.listen(LIVENESS_PORT)
        .await()

      // MQTT server
      MqttServer.create(vertx, mqttServerOptions).endpointHandler { client ->
        launch(vertx.dispatcher()) { handleClient(client) }
      }.listen(MQTT_PORT).await()

      LOGGER.info { "MQTT server listening on port $MQTT_PORT" }

      // TCP server for readiness checks
      vertx.createNetServer(netServerOptions).connectHandler { LOGGER.debug { "Readiness check complete" } }
        .listen(READINESS_PORT)
        .await()
    } catch (error: Throwable) {
      LOGGER.error(error) { "An error occurred while creating the server" }
    }

    // Periodically send all the config to all relays to keep whiteList consistent with the DB
    // We use Timer to not have any stacking of operation
    periodicUpdateConfig = Handler {
      launch(vertx.dispatcher()) {
        sendConfigToAllRelays()
        checkConnectionAndUpdateDb()
        vertx.setTimer(TimeUnit.SECONDS.toMillis(UPDATE_CONFIG_INTERVAL_SECONDS), periodicUpdateConfig)
      }
    }
    // Schedule the first execution
    vertx.setTimer(TimeUnit.SECONDS.toMillis(UPDATE_CONFIG_INTERVAL_SECONDS), periodicUpdateConfig)


    val router = Router.router(vertx)

    val allowedHeaders =
      setOf(
        "x-requested-with",
        "Access-Control-Allow-Origin",
        "Access-Control-Allow-Credentials",
        "origin",
        CONTENT_TYPE,
        "accept",
        "Authorization"
      )
    val allowedMethods = setOf(HttpMethod.GET)

    router.route().handler(
      CorsHandler.create(".*.").allowCredentials(false).allowedHeaders(allowedHeaders).allowedMethods(allowedMethods)
    )
    router.get(EMERGENCY_ENDPOINT).coroutineHandler(::emergencyHandler)



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
  }

  private suspend fun handleClient(client: MqttEndpoint) {
    val clientIdentifier = client.clientIdentifier()
    val isCleanSession = client.isCleanSession
    LOGGER.info { "Client $clientIdentifier request to connect, clean session = $isCleanSession" }

    val mqttAuth = client.auth()
    if (mqttAuth == null) {
      // No auth information, reject
      LOGGER.error { "Client [$clientIdentifier] rejected because no auth specified" }
      client.reject(MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED)
      return
    }

    // Authenticate the client
    val credentialsJson = jsonObjectOf(
      "username" to mqttAuth.username,
      "password" to mqttAuth.password
    )

    val will = client.will()
    if (will == null || will.willMessage == null || will.willMessage.length() == 0) {
      // No will, reject
      LOGGER.error { "Client $clientIdentifier rejected because no will specified" }
      client.reject(MqttConnectReturnCode.CONNECTION_REFUSED_UNSPECIFIED_ERROR)
      return
    }

    val company = client.will().willMessage.toJsonObject().getString("company")
    val collection = if (company != "biot") "${RELAYS_COLLECTION}_$company" else RELAYS_COLLECTION
    val mongoAuthRelays = if (mongoAuthRelaysCache.containsKey(collection)) {
      mongoAuthRelaysCache[collection]!!
    } else {
      val usernameField = "mqttUsername"
      val passwordField = "mqttPassword"
      val mongoAuth = MongoAuthentication.create(
        mongoClient,
        mongoAuthenticationOptionsOf(
          collectionName = collection,
          passwordCredentialField = passwordField,
          passwordField = passwordField,
          usernameCredentialField = usernameField,
          usernameField = usernameField
        )
      )
      mongoAuthRelaysCache[collection] = mongoAuth
      mongoAuth
    }

    try {
      mongoAuthRelays.authenticate(credentialsJson).await()

      LOGGER.info { "Client $clientIdentifier connected" }

      // Accept connection from the remote client
      val sessionPresent = !client.isCleanSession
      client.accept(sessionPresent)
        .disconnectHandler {
          LOGGER.info { "Client $clientIdentifier disconnected" }
          clients.remove(clientIdentifier)
        }.subscribeHandler { subscribe ->
          // Extract the QoS levels to be used to acknowledge
          val grantedQosLevels = subscribe.topicSubscriptions().map { s ->
            LOGGER.info { "Subscription for ${s.topicName()} with QoS ${s.qualityOfService()} by client $clientIdentifier" }
            s.qualityOfService()
          }

          // Ack the subscriptions request
          client.subscribeAcknowledge(subscribe.messageId(), grantedQosLevels)
          clients[clientIdentifier] = Pair(company, client)

          // Send last configuration to client
          launch(vertx.dispatcher()) { sendLastConfiguration(client, company) }
        }.unsubscribeHandler { unsubscribe ->
          unsubscribe.topics().forEach { topic ->
            LOGGER.info { "Unsubscription for $topic by client $clientIdentifier" }
          }
          clients.remove(clientIdentifier)
        }.publishHandler { m ->
          launch(vertx.dispatcher()) { handleMessage(m, client, company) }
        }

      mongoClient.findOneAndUpdate(
        collection,
        jsonObjectOf("mqttID" to clientIdentifier),
        jsonObjectOf("\$set" to jsonObjectOf("connected" to true))
      ).onFailure {
        LOGGER.error { "Relay_Communication: Cannot update mongo DB to set connected = true for relays" }
      }
    } catch (error: Throwable) {
      // Wrong username or password, reject
      LOGGER.error(error) { "Client $clientIdentifier rejected" }
      client.reject(MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD)
    }
  }

  /**
   * Handles a MQTT message received by the given client.
   */
  private suspend fun handleMessage(m: MqttPublishMessage, client: MqttEndpoint, company: String) {
    /**
     * Validates the JSON, returning true iff it contains all required fields.
     */
    fun validateJson(json: JsonObject): CompositeFuture {
      fun validateWithFuture(promise: Promise<Boolean>, errorMessage: String, validatingFunction: () -> Boolean) {
        val isValid = validatingFunction()
        if (isValid) promise.complete(isValid) else promise.fail(errorMessage)
      }

      val containsAllKeys = Future.future<Boolean> { promise ->
        val keysToContain = listOf("relayID", "beacons", "latitude", "longitude", "floor")
        val keysToContainBeacons = listOf("mac", "rssi", "battery", "temperature", "status")
        validateWithFuture(promise, "Fields are missing") {
          val areFieldsValid = keysToContain.fold(true) { acc, curr ->
            acc && json.containsKey(curr)
          }
          val areBeaconsFieldValid = keysToContainBeacons.fold(true) { acc, curr ->
            acc && json.getJsonArray("beacons").all {
              val beacon = it as JsonObject
              beacon.containsKey(curr)
            }
          }
          areFieldsValid && areBeaconsFieldValid
        }
      }

      val validCoordinates = Future.future<Boolean> { promise ->
        validateWithFuture(promise, "One or both coordinates are 0") {
          json.getDouble("latitude") != 0.0 && json.getDouble("longitude") != 0.0
        }
      }

      val validBeaconsField = Future.future<Boolean> { promise ->
        validateWithFuture(promise, "One or more fields are not valid in the beacons field") {
          val beacons = json.getJsonArray("beacons")
          beacons.all {
            val beacon = it as JsonObject
            val nonEmptyMac = beacon.getString("mac").isNotEmpty()
            val validBattery = beacon.getInteger("battery") in 0..100
            val validStatus = beacon.getInteger("status") in setOf(0, 1, 2, 3)
            nonEmptyMac && validBattery && validStatus
          }
        }
      }

      return CompositeFuture.all(containsAllKeys, validCoordinates, validBeaconsField)
    }

    try {
      val message = m.payload().toJsonObject()
      LOGGER.info { "Received message $message from client ${client.clientIdentifier()}" }

      if (m.qosLevel() == MqttQoS.AT_LEAST_ONCE) {
        // Acknowledge the message
        client.publishAcknowledge(m.messageId())
      }

      if (m.topicName() == INGESTION_TOPIC) {
        try {
          validateJson(message).await()
          // The message contains data to ingest and is valid

          val kafkaMessage = message.copy().apply {
            // Add a timestamp to the message to send to Kafka
            put("timestamp", Instant.now())
            put("company", company)
          }

          val relayID: String = message["relayID"]
          try {
            // Send the message to Kafka on the "incoming.update" topic
            val record = KafkaProducerRecord.create(INGESTION_TOPIC, relayID, kafkaMessage)
            kafkaProducer.send(record).await()
            LOGGER.info { "Sent message $kafkaMessage with key $relayID on topic $INGESTION_TOPIC" }
          } catch (sendError: Throwable) {
            LOGGER.error(sendError) {
              "Could not send message $kafkaMessage with key $relayID' on topic $INGESTION_TOPIC"
            }
          }
        } catch (invalidJsonError: Throwable) {
          LOGGER.error(invalidJsonError) { "Invalid JSON received" }
        }
      }
    } catch (exception: DecodeException) {
      LOGGER.error(exception) { "Could not decode MQTT message $m" }
    }
  }

  /**
   * Sends the last relay configuration to the given client.
   */
  private suspend fun sendLastConfiguration(client: MqttEndpoint, company: String, paramConfig: JsonObject? = null) {
    try {
      val cleanConfig = paramConfig ?: (getCurrentCleanConfig(company, client.clientIdentifier()) ?: return)
      configHashes[company] = cleanConfig.hashCode()
      // LEGACY BEGIN ----------------------------------------------------------------------
      sendMessageTo(client, cleanConfig, UPDATE_PARAMETERS_TOPIC)
      // LEGACY END ------------------------------------------------------------------------
      sendMessageTo(client, cleanConfig, RELAYS_MANAGEMENT_TOPIC)
    } catch (error: Throwable) {
      LOGGER.error(error) { "Could not send last configuration to client ${client.clientIdentifier()}" }
    }
  }

  /**
   * Sends the given message to the given client on the given MQTT topic.
   */
  private suspend fun sendMessageTo(client: MqttEndpoint, message: JsonObject, topic: String) {
    try {
      val messageId = client
        .publishAcknowledgeHandler { messageId -> LOGGER.info("Received ack for message $messageId") }
        .publish(topic, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
      LOGGER.info { "Published message $message with id $messageId to client ${client.clientIdentifier()} on topic $topic" }
    } catch (error: Throwable) {
      LOGGER.error(error) { "Could not send message $message on topic $topic" }
    }
  }

  private suspend fun sendConfigToAllRelays() {
    clients.forEach { (_, relayClientPair) ->
      val company = relayClientPair.first
      val relayClient = relayClientPair.second
      val currentConfig = getCurrentCleanConfig(company, relayClient.clientIdentifier())

      if (!configHashes.containsKey(company) || currentConfig.hashCode() != configHashes[company]) {
        // The whiteList changed since the last time it was sent to the relays, so we send it again
        LOGGER.info { "Config changed: sending last configuration to relay ${relayClient.clientIdentifier()}" }
        sendLastConfiguration(relayClient, company)
      } else {
        LOGGER.debug { "Skipping sending configuration for the relay ${relayClient.clientIdentifier()}" }
      }

    }
  }

  /**
   * Get the current config for the relay with the given mqttID with the whitelist in it
   * It cleans the config before returning
   * returns null if an error occurred
   */
  private suspend fun getCurrentCleanConfig(company: String, mqttID: String): JsonObject? {
    try {
      val collection = if (company != "biot") "${RELAYS_COLLECTION}_$company" else RELAYS_COLLECTION
      val query = jsonObjectOf("mqttID" to mqttID)
      // Get items mac addresses
      // Find the last configuration in MongoDB
      val config = mongoClient.findOne(collection, query, jsonObjectOf()).await()
      if (config != null && !config.isEmpty) {
        // The configuration exists
        // Remove useless fields and clean lastModified, then send

        val whiteListString = getItemsMacAddressesString(company)
        val cleanConfig = config.clean()
        cleanConfig.put("whiteList", whiteListString)

        return cleanConfig
      }
    } catch (e: Exception){
      LOGGER.error { "An error occurred while retrieving current clean config!" }
    }

    return null
  }

  /**
   * Gets the MAC addresses of beacons associated to items in the DB for the given company and returns them as
   * a semi-colon separated string.
   * The MAC addresses are filtered to have only "valid" MAC addresses.
   * If it cannot get MAC Addresses from the DB, it returns an empty string and logs a message.
   */
  private suspend fun getItemsMacAddressesString(company: String): String {
    val accessControlString = company
    val itemsTable = if (company != "biot") "${ITEMS_TABLE}_$company" else ITEMS_TABLE
    val executedQuery = pgClient.preparedQuery(getItemsMacs(itemsTable, accessControlString)).execute()
    return try {
      val queryResult = executedQuery.await()
      val result = if (queryResult.size() == 0) listOf() else queryResult.map { it.getString("beacon") }

      // Filter result to remove invalid mac addresses
      val res =
        result.filterNotNull().filter { s -> s.matches(macAddressRegex) }.map { s -> s.replace(":", "") }.distinct().joinToString("")
      if (res.length > MAX_NUMBER_MAC_MQTT * CHAR_NUMBER_IN_MAC_ADDRESS) {
        res.substring(0 until (MAX_NUMBER_MAC_MQTT * CHAR_NUMBER_IN_MAC_ADDRESS))
      } else {
        res
      }
    } catch (e: Exception) {
      LOGGER.warn { "Could not get beacons' whitelist: exception: ${e.stackTraceToString()}" }
      ""
    }
  }

  /**
   * Go through the map of mqtt Endpoints and remove those that are not connected
   * It also updates the mongoDB accordingly
   */
  private suspend fun checkConnectionAndUpdateDb() {
    val bulkOperations = hashMapOf<String, ArrayList<BulkOperation>>()

    for (entry in clients.entries) {
      val clientId = entry.key
      val company = entry.value.first
      val client = entry.value.second
      if (!client.isConnected) {
        clients.remove(clientId)
      } else {
        val filter = jsonObjectOf("mqttID" to clientId)
        val update = jsonObjectOf(
          "\$set" to jsonObjectOf("connected" to true)
        )
        if (!bulkOperations.containsKey(company)) {
          bulkOperations[company] = arrayListOf()
        }
        bulkOperations[company]?.add(BulkOperation.createUpdate(filter, update))
      }
    }

    val allFalseUpdate = jsonObjectOf(
      "\$set" to jsonObjectOf("connected" to false)
    )
    for (company in bulkOperations.keys) {
      val relaysCollection = if (company != "biot") "${RELAYS_COLLECTION}_$company" else RELAYS_COLLECTION
      mongoClient.updateCollection(relaysCollection, JsonObject(), allFalseUpdate).await()
      mongoClient.bulkWrite(relaysCollection, bulkOperations[company]).await()
    }
  }

  private suspend fun emergencyHandler(ctx: RoutingContext) {
    val relayID = ctx.queryParams().get("relayID")
    LOGGER.info { "New emergency reset request for $relayID" }

    val forceFlag: Boolean = if(relayID == DEFAULT_RELAY_ID) {
      // Send the url and force flag to true
      true
    } else {
      var flag = true // True by default
      try {
        val allCollections = mongoClient.collections.await()
        val query = jsonObjectOf("relayID" to relayID)
        for (collection in allCollections){
          if(collection.contains("relay")){
            val relay = mongoClient.findOne(collection, query, jsonObjectOf()).await()
            if(relay != null){
              val flagFromDb = relay.getBoolean("forceReset")
              if(flagFromDb != null){
                flag = flagFromDb
                mongoClient.findOneAndUpdate(collection, query, jsonObjectOf("\$set" to jsonObjectOf("forceReset" to false))).await()
              }
              break
            }
          }
        }
      } catch (e: Exception) {
        LOGGER.error { "An error occurred getting the relays from DB." }
      }

      flag
    }

    val result = jsonObjectOf("repoURL" to RELAY_REPO_URL, "forceReset" to forceFlag)

    ctx.response()
      .putHeader(CONTENT_TYPE, APPLICATION_JSON)
      .end(result.encode())
  }

  /**
   * An extension method for simplifying coroutines usage with Vert.x Web routers.
   */
  private fun Route.coroutineHandler(fn: suspend (RoutingContext) -> Unit): Route =
    handler { ctx ->
      launch(ctx.vertx().dispatcher()) {
        try {
          fn(ctx)
        } catch (e: Exception) {
          ctx.fail(e)
        }
      }
    }
}
