/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

package ch.biot.backend.relayscommunication

import io.netty.handler.codec.mqtt.MqttConnectReturnCode
import io.netty.handler.codec.mqtt.MqttQoS
import io.reactivex.Completable
import io.reactivex.rxkotlin.subscribeBy
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.DecodeException
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.eventbus.eventBusOptionsOf
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.vertxOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.reactivex.core.buffer.Buffer
import io.vertx.reactivex.ext.auth.mongo.MongoAuthentication
import io.vertx.reactivex.ext.mongo.MongoClient
import io.vertx.reactivex.kafka.client.producer.KafkaProducer
import io.vertx.reactivex.kafka.client.producer.KafkaProducerRecord
import io.vertx.reactivex.mqtt.MqttEndpoint
import io.vertx.reactivex.mqtt.MqttServer
import io.vertx.reactivex.mqtt.messages.MqttPublishMessage
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.time.Instant

class RelaysCommunicationVerticle : io.vertx.reactivex.core.AbstractVerticle() {

  companion object {
    internal const val RELAYS_COLLECTION = "relays"
    internal const val UPDATE_PARAMETERS_TOPIC = "update.parameters"
    internal const val INGESTION_TOPIC = "incoming.update"
    internal const val RELAYS_UPDATE_ADDRESS = "relays.update"

    internal val MQTT_PORT = System.getenv().getOrDefault("MQTT_PORT", "1883").toInt()

    private val MONGO_HOST: String = System.getenv().getOrDefault("MONGO_HOST", "localhost")
    internal val MONGO_PORT = System.getenv().getOrDefault("MONGO_PORT", "27017").toInt()

    private val KAFKA_HOST: String = System.getenv().getOrDefault("KAFKA_HOST", "localhost")
    internal val KAFKA_PORT = System.getenv().getOrDefault("KAFKA_PORT", "9092").toInt()

    private const val LIVENESS_PORT = 1884
    private const val READINESS_PORT = 1885

    private val logger = LoggerFactory.getLogger(RelaysCommunicationVerticle::class.java)

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
        logger.error("Could not start", error)
      }
    }
  }

  private val clients = mutableMapOf<String, MqttEndpoint>() // map of subscribed clientIdentifier to client

  private lateinit var kafkaProducer: KafkaProducer<String, JsonObject>

  private lateinit var mongoClient: MongoClient
  private lateinit var mongoAuth: MongoAuthentication

  override fun rxStart(): Completable {
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
        "host" to MONGO_HOST, "port" to MONGO_PORT, "db_name" to "clients", "username" to "biot",
        "password" to "biot"
      )
    )

    val usernameField = "mqttUsername"
    val passwordField = "mqttPassword"
    mongoAuth = MongoAuthentication.create(
      mongoClient,
      mongoAuthenticationOptionsOf(
        collectionName = RELAYS_COLLECTION,
        passwordCredentialField = passwordField,
        passwordField = passwordField,
        usernameCredentialField = usernameField,
        usernameField = usernameField
      )
    )

    // Add an event bus consumer to handle the JSON received from CRUDVerticle, which needs to be forwarded to the right
    // relay
    vertx.eventBus().consumer<JsonObject>(RELAYS_UPDATE_ADDRESS)
      .bodyStream()
      .toFlowable()
      .subscribe { json ->
        logger.info("Received relay update $json on event bus address $RELAYS_UPDATE_ADDRESS, sending it to client...")

        val mqttID: String = json["mqttID"]

        // Clean the json from useless fields
        val cleanJson = json.clean()

        // Send the message to the right relay on the MQTT topic "update.parameters"
        clients[mqttID]?.let { client -> sendMessageTo(client, cleanJson) }
      }

//    // Certificate for TLS
//    val pemKeyCertOptions = pemKeyCertOptionsOf(certPath = "certificate.pem", keyPath = "certificate_key.pem")
//    val netServerOptions = netServerOptionsOf(ssl = true, pemKeyCertOptions = pemKeyCertOptions)

    // TCP server for liveness checks
    return vertx.createNetServer().connectHandler {
      logger.info("Liveness check")
    }
      .rxListen(LIVENESS_PORT)
      .flatMap {
        MqttServer.create(vertx)
          .endpointHandler(::handleClient).rxListen(MQTT_PORT)
      }
      .flatMap {
        logger.info("MQTT server listening on port $MQTT_PORT")
        vertx.createNetServer().connectHandler {
          logger.info("Readiness check complete")
        }.rxListen(READINESS_PORT)
      }.ignoreElement()
  }

  private fun handleClient(client: MqttEndpoint) {
    val clientIdentifier = client.clientIdentifier()
    val isCleanSession = client.isCleanSession
    logger.info("Client [$clientIdentifier] request to connect, clean session = $isCleanSession")

    val mqttAuth = client.auth()
    if (mqttAuth == null) {
      // No auth information, reject
      logger.info("Client [$clientIdentifier] rejected")
      client.reject(MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED)
      return
    }

    // Authenticate the client
    val credentialsJson = jsonObjectOf(
      "username" to mqttAuth.username,
      "password" to mqttAuth.password
    )
    mongoAuth.rxAuthenticate(credentialsJson).subscribeBy(
      onSuccess = {
        // Accept connection from the remote client
        logger.info("Client [$clientIdentifier] connected")

        val sessionPresent = !client.isCleanSession
        client.accept(sessionPresent)
          .disconnectHandler {
            logger.info("Client [$clientIdentifier] disconnected")
            clients.remove(clientIdentifier)
          }.subscribeHandler { subscribe ->
            // Extract the QoS levels to be used to acknowledge
            val grantedQosLevels = subscribe.topicSubscriptions().map { s ->
              logger.info("Subscription for [${s.topicName()}] with QoS [${s.qualityOfService()}] by client [$clientIdentifier]")
              s.qualityOfService()
            }

            // Ack the subscriptions request
            client.subscribeAcknowledge(subscribe.messageId(), grantedQosLevels)
            clients[clientIdentifier] = client

            // Send last configuration to client
            sendLastConfiguration(client)
          }.unsubscribeHandler { unsubscribe ->
            unsubscribe.topics().forEach { topic ->
              logger.info("Unsubscription for [$topic] by client [$clientIdentifier]")
            }
            clients.remove(clientIdentifier)
          }.publishHandler { m ->
            handleMessage(m, client)
          }
      },
      onError = {
        // Wrong username or password, reject
        logger.info("Client [$clientIdentifier] rejected", it)
        client.reject(MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD)
      }
    )
  }

  /**
   * Handles a MQTT message received by the given client.
   */
  private fun handleMessage(m: MqttPublishMessage, client: MqttEndpoint) {
    /**
     * Validates the JSON, returning true iff it contains all required fields.
     */
    fun validateJson(json: JsonObject): CompositeFuture {
      fun validateWithFuture(promise: Promise<Boolean>, errorMessage: String, validatingFunction: () -> Boolean) {
        val isValid = validatingFunction()
        if (isValid) promise.complete(isValid) else promise.fail(errorMessage)
      }

      val containsAllKeys = Future.future<Boolean> { promise ->
        val keysToContain = listOf("relayID", "rssi", "mac", "latitude", "longitude", "floor")
        validateWithFuture(promise, "Fields are missing") {
          keysToContain.fold(true) { acc, curr ->
            acc && json.containsKey(curr)
          }
        }
      }

      val validCoordinates = Future.future<Boolean> { promise ->
        validateWithFuture(promise, "One or both coordinates are 0") {
          json.getDouble("latitude") != 0.0 && json.getDouble("longitude") != 0.0
        }
      }

      val nonEmptyArrays = Future.future<Boolean> { promise ->
        validateWithFuture(promise, "One or both arrays are empty") {
          !json.getJsonArray("rssi").isEmpty && !json.getJsonArray("mac").isEmpty
        }
      }

      val sameLengthArrays = Future.future<Boolean> { promise ->
        validateWithFuture(promise, "The mac and rssi arrays do not have the same length") {
          json.getJsonArray("rssi").size() == json.getJsonArray("mac").size()
        }
      }

      return CompositeFuture.all(containsAllKeys, validCoordinates, nonEmptyArrays, sameLengthArrays)
    }

    try {
      val message = m.payload().toJsonObject()
      logger.info("Received message $message from client ${client.clientIdentifier()}")

      if (m.qosLevel() == MqttQoS.AT_LEAST_ONCE) {
        // Acknowledge the message
        client.publishAcknowledge(m.messageId())
      }

      if (m.topicName() == INGESTION_TOPIC) {
        validateJson(message).onSuccess {
          // The message contains data to ingest and is valid
          val kafkaMessage = message.copy().apply {
            // Add a timestamp to the message to send to Kafka
            put("timestamp", Instant.now())
          }

          val relayID: String = message["relayID"]

          // Send the message to Kafka on the "incoming.update" topic
          val record = KafkaProducerRecord.create(INGESTION_TOPIC, relayID, kafkaMessage)
          kafkaProducer.rxSend(record).subscribeBy(
            onSuccess = {
              logger.info("Sent message $kafkaMessage with key '$relayID' on topic '$INGESTION_TOPIC'")
            },
            onError = { error ->
              logger.error(
                "Could not send message $kafkaMessage with key '$relayID' on topic '$INGESTION_TOPIC'",
                error
              )
            }
          )
        }.onFailure { error ->
          logger.error("Invalid JSON received", error)
        }
      }
    } catch (e: DecodeException) {
      logger.error("Could not decode MQTT message $m", e)
    }
  }

  /**
   * Sends the last relay configuration to the given client.
   */
  private fun sendLastConfiguration(client: MqttEndpoint) {
    val query = jsonObjectOf("mqttID" to client.clientIdentifier())
    // Find the last configuration in MongoDB
    mongoClient.rxFindOne(RELAYS_COLLECTION, query, jsonObjectOf()).subscribeBy(
      onSuccess = { config ->
        if (config != null && !config.isEmpty) {
          // The configuration exists

          // Remove useless fields and clean lastModified, then send
          val cleanConfig = config.clean()
          sendMessageTo(client, cleanConfig)
        }
      },
      onError = { error ->
        logger.error("Could not send last configuration to client ${client.clientIdentifier()}", error)
      }
    )
  }

  /**
   * Sends the given message to the given client on the "update.parameters" MQTT topic.
   */
  private fun sendMessageTo(client: MqttEndpoint, message: JsonObject) {
    client.publishAcknowledgeHandler { messageId ->
      logger.info("Received ack for message $messageId")
    }.rxPublish(UPDATE_PARAMETERS_TOPIC, Buffer.newInstance(message.toBuffer()), MqttQoS.AT_LEAST_ONCE, false, false)
      .subscribeBy(
        onSuccess = { messageId ->
          logger.info("Published message $message with id $messageId to client ${client.clientIdentifier()} on topic $UPDATE_PARAMETERS_TOPIC")
        },
        onError = { error ->
          logger.error("Could not send message $message on topic $UPDATE_PARAMETERS_TOPIC", error)
        }
      )
  }
}
