/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.relayscommunication

import io.netty.handler.codec.mqtt.MqttConnectReturnCode
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.DecodeException
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoAuthentication
import io.vertx.ext.mongo.MongoClient
import io.vertx.kafka.client.producer.KafkaProducer
import io.vertx.kafka.client.producer.KafkaProducerRecord
import io.vertx.kotlin.core.eventbus.eventBusOptionsOf
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.vertxOptionsOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.mqtt.MqttEndpoint
import io.vertx.mqtt.MqttServer
import io.vertx.mqtt.messages.MqttPublishMessage
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.net.InetAddress
import java.time.Instant

private val LOGGER = KotlinLogging.logger {}

class RelaysCommunicationVerticle : CoroutineVerticle() {

  companion object {
    internal const val RELAYS_COLLECTION = "relays"
    internal const val UPDATE_PARAMETERS_TOPIC = "update.parameters"
    internal const val INGESTION_TOPIC = "incoming.update"
    internal const val RELAYS_UPDATE_ADDRESS = "relays.update"

    private val environment = System.getenv()
    internal val MQTT_PORT = environment.getOrDefault("MQTT_PORT", "1883").toInt()

    private val MONGO_HOST: String = environment.getOrDefault("MONGO_HOST", "localhost")
    internal val MONGO_PORT = environment.getOrDefault("MONGO_PORT", "27017").toInt()

    private val KAFKA_HOST: String = environment.getOrDefault("KAFKA_HOST", "localhost")
    internal val KAFKA_PORT = environment.getOrDefault("KAFKA_PORT", "9092").toInt()

    private const val LIVENESS_PORT = 1884
    private const val READINESS_PORT = 1885

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

  private val clients = mutableMapOf<String, MqttEndpoint>() // map of subscribed clientIdentifier to client

  // Map from collection name to MongoAuthentication
  // It is used since there is a collection per company
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
      clients[mqttID]?.let { client -> launch(vertx.dispatcher()) { sendMessageTo(client, cleanJson) } }
    }

//    // Certificate for TLS
//    val pemKeyCertOptions = pemKeyCertOptionsOf(certPath = "certificate.pem", keyPath = "certificate_key.pem")
//    val netServerOptions = netServerOptionsOf(ssl = true, pemKeyCertOptions = pemKeyCertOptions)

    try {
      // TCP server for liveness checks
      vertx.createNetServer().connectHandler { LOGGER.debug { "Liveness check" } }.listen(LIVENESS_PORT).await()
      MqttServer.create(vertx).endpointHandler { client ->
        launch(vertx.dispatcher()) { handleClient(client) }
      }.listen(MQTT_PORT).await()

      LOGGER.info { "MQTT server listening on port $MQTT_PORT" }
      vertx.createNetServer().connectHandler { LOGGER.debug { "Readiness check complete" } }.listen(READINESS_PORT)
        .await()
    } catch (error: Throwable) {
      LOGGER.error(error) { "An error occurred while creating the server" }
    }
  }

  private suspend fun handleClient(client: MqttEndpoint) {
    val clientIdentifier = client.clientIdentifier()
    val isCleanSession = client.isCleanSession
    LOGGER.info { "Client $clientIdentifier request to connect, clean session = $isCleanSession" }

    val mqttAuth = client.auth()
    if (mqttAuth == null) {
      // No auth information, reject
      LOGGER.error { "Client [$clientIdentifier] rejected" }
      client.reject(MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED)
      return
    }

    // Authenticate the client
    val credentialsJson = jsonObjectOf(
      "username" to mqttAuth.username,
      "password" to mqttAuth.password
    )
//    val company = client.will().willMessage.toJsonObject().getString("company")

    // TEMP WARNING DEBUG CODE !!!!!! TO REMOVE - SCH - 04.08.2021
    val company = when(clientIdentifier){
      "relay_11", "relay_12", "relay_14", "relay_15", "relay_16", "relay_17", "relay_18", "relay_19" -> "hju"
      else -> client.will().willMessage.toJsonObject().getString("company")
    }
    // TEMP WARNING DEBUG CODE !!!!!! TO REMOVE - SCH - 04.08.2021


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
          clients[clientIdentifier] = client

          // Send last configuration to client
          launch(vertx.dispatcher()) { sendLastConfiguration(client, collection) }
        }.unsubscribeHandler { unsubscribe ->
          unsubscribe.topics().forEach { topic ->
            LOGGER.info { "Unsubscription for $topic by client $clientIdentifier" }
          }
          clients.remove(clientIdentifier)
        }.publishHandler { m ->
          launch(vertx.dispatcher()) { handleMessage(m, client, company) }
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
      LOGGER.info { "Received message $message from client ${client.clientIdentifier()} for company: $company" }

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
  private suspend fun sendLastConfiguration(client: MqttEndpoint, relaysCollection: String) {
    val query = jsonObjectOf("mqttID" to client.clientIdentifier())
    try {
      // Find the last configuration in MongoDB
      val config = mongoClient.findOne(relaysCollection, query, jsonObjectOf()).await()
      LOGGER.info { "Get Last config for relay: ${client.clientIdentifier()} and got: $config from the table $RELAYS_COLLECTION" }
      if (config != null && !config.isEmpty) {
        // The configuration exists
        // Remove useless fields and clean lastModified, then send
        val cleanConfig = config.clean()
        sendMessageTo(client, cleanConfig)
      }
    } catch (error: Throwable) {
      LOGGER.error(error) { "Could not send last configuration to client ${client.clientIdentifier()}" }
    }
  }

  /**
   * Sends the given message to the given client on the "update.parameters" MQTT topic.
   */
  private suspend fun sendMessageTo(client: MqttEndpoint, message: JsonObject) {
    try {
      val messageId = client
        .publishAcknowledgeHandler { messageId -> LOGGER.info("Received ack for message $messageId") }
        .publish(UPDATE_PARAMETERS_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
      LOGGER.info { "Published message $message with id $messageId to client ${client.clientIdentifier()} on topic $UPDATE_PARAMETERS_TOPIC" }
    } catch (error: Throwable) {
      LOGGER.error(error) { "Could not send message $message on topic $UPDATE_PARAMETERS_TOPIC" }
    }
  }
}
