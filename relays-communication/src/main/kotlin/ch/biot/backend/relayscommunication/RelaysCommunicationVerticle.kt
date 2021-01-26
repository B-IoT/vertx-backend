/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

package ch.biot.backend.relayscommunication

import io.netty.handler.codec.mqtt.MqttConnectReturnCode
import io.netty.handler.codec.mqtt.MqttQoS
import io.reactivex.Completable
import io.reactivex.rxkotlin.subscribeBy
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
import java.net.UnknownHostException
import java.time.Instant


class RelaysCommunicationVerticle : io.vertx.reactivex.core.AbstractVerticle() {

  companion object {
    internal const val RELAYS_COLLECTION = "relays"
    internal const val UPDATE_PARAMETERS_TOPIC = "update.parameters"
    internal const val LAST_CONFIGURATION_TOPIC = "last.configuration"
    internal const val INGESTION_TOPIC = "incoming.update"
    internal const val RELAYS_UPDATE_ADDRESS = "relays.update"

    internal const val MQTT_PORT = 8883

    private val logger = LoggerFactory.getLogger(RelaysCommunicationVerticle::class.java)

    @Throws(UnknownHostException::class)
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
    // TODO update with real configuration

    // Initialize the Kafka producer
    kafkaProducer = KafkaProducer.create(
      vertx, mapOf(
        "bootstrap.servers" to "localhost:9092",
        "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
        "value.serializer" to "io.vertx.kafka.client.serialization.JsonObjectSerializer",
        "acks" to "1"
      )
    )

    // Initialize MongoDB
    mongoClient =
      MongoClient.createShared(vertx, jsonObjectOf("host" to "localhost", "port" to 27017, "db_name" to "clients"))

    val usernameField = "mqttUsername"
    val passwordField = "mqttPassword"
    mongoAuth = MongoAuthentication.create(
      mongoClient, mongoAuthenticationOptionsOf(
        collectionName = RELAYS_COLLECTION,
        passwordCredentialField = passwordField,
        passwordField = passwordField,
        usernameCredentialField = usernameField,
        usernameField = usernameField
      )
    )

    // Add an event bus consumer to handle the JSON received from CRUDVerticle, which needs to be forwarded to the right
    // relay
    vertx.eventBus().consumer<JsonObject>(RELAYS_UPDATE_ADDRESS) { message ->
      val json = message.body()
      logger.info("Received relay update $json on event bus address $RELAYS_UPDATE_ADDRESS, sending it to client...")
      val mqttID: String = json["mqttID"]

      // Remove the mqttID, as the relay already knows it
      val jsonWithoutMqttID = json.copy().apply {
        remove("mqttID")
      }

      // Send the message to the right relay on the MQTT topic "update.parameters"
      clients[mqttID]?.let { client -> sendMessageTo(client, jsonWithoutMqttID, UPDATE_PARAMETERS_TOPIC) }
    }

    // TODO MQTT on TLS
    return MqttServer.create(vertx).endpointHandler(::handleClient).rxListen(MQTT_PORT).ignoreElement()
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
    fun validateJson(json: JsonObject): Boolean {
      val keysToContain = listOf("relayID", "battery", "rssi", "mac", "isPushed")
      return keysToContain.fold(true) { acc, curr ->
        acc && json.containsKey(curr)
      }
    }

    try {
      val message = m.payload().toJsonObject()
      logger.info("Received message $message from client ${client.clientIdentifier()}")

      if (m.qosLevel() == MqttQoS.AT_LEAST_ONCE) {
        // Acknowledge the message
        client.publishAcknowledge(m.messageId())
      }

      if (m.topicName() == INGESTION_TOPIC && validateJson(message)) {
        // The message contains data to ingest and is valid
        val kafkaMessage = message.copy().apply {
          // Add a timestamp to the message to send to Kafka
          put("timestamp", Instant.now())
        }

        val beaconMacAddress: String = message["mac"]

        // Send the message to Kafka on the "incoming.update" topic
        val record = KafkaProducerRecord.create(INGESTION_TOPIC, beaconMacAddress, kafkaMessage)
        kafkaProducer.rxSend(record).subscribeBy(
          onSuccess = {
            logger.info("Sent message $kafkaMessage with key '$beaconMacAddress' on topic '$INGESTION_TOPIC'")
          },
          onError = { error ->
            logger.error(
              "Could not send message $kafkaMessage with key '$beaconMacAddress' on topic '$INGESTION_TOPIC'",
              error
            )
          }
        )
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
          sendMessageTo(client, cleanConfig, LAST_CONFIGURATION_TOPIC)
        }
      },
      onError = { error ->
        logger.error("Could not send last configuration to client ${client.clientIdentifier()}", error)
      }
    )
  }

  /**
   * Sends the given message to the given client on the given MQTT topic.
   */
  private fun sendMessageTo(client: MqttEndpoint, message: JsonObject, topic: String) {
    client.publishAcknowledgeHandler { messageId ->
      logger.info("Received ack for message $messageId")
    }.rxPublish(topic, Buffer.newInstance(message.toBuffer()), MqttQoS.AT_LEAST_ONCE, false, false)
      .subscribeBy(
        onSuccess = { messageId ->
          logger.info("Published message $message with id $messageId to client ${client.clientIdentifier()} on topic $topic")
        },
        onError = { error ->
          logger.error("Could not send message $message on topic $topic", error)
        }
      )
  }
}
