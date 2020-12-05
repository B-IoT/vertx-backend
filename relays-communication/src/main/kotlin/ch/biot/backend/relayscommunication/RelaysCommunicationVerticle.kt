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
    private const val RELAYS_COLLECTION = "relays"
    private const val RELAYS_UPDATE_ADDRESS = "relays.update"
    private const val INGESTION_TOPIC = "incoming.update"

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

    kafkaProducer = KafkaProducer.create(
      vertx, mapOf(
        "bootstrap.servers" to "localhost:9092",
        "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
        "value.serializer" to "io.vertx.kafka.client.serialization.JsonObjectSerializer",
        "acks" to "1"
      )
    )

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

    vertx.eventBus().consumer<JsonObject>(RELAYS_UPDATE_ADDRESS) { message ->
      val json = message.body()
      logger.info("Received relay update $json on event bus address $RELAYS_UPDATE_ADDRESS, sending it to client...")
      val mqttID: String = json["mqttID"]
      val jsonWithoutMqttID = json.copy().apply {
        remove("mqttID")
      }
      clients[mqttID]?.let { client -> sendMessageTo(client, jsonWithoutMqttID, "update.parameters") }
    }

    // TODO MQTT on TLS
    return MqttServer.create(vertx).endpointHandler(::handleClient).rxListen(8883).ignoreElement()
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

    val credentialsJson = jsonObjectOf(
      "username" to mqttAuth.username,
      "password" to mqttAuth.password
    )
    mongoAuth.rxAuthenticate(credentialsJson).subscribeBy(
      onSuccess = {
        // Accept connection from the remote client
        logger.info("Client [$clientIdentifier] connected")

        val sessionPresent = !client.isCleanSession
        client.accept(sessionPresent).disconnectHandler {
          logger.info("Client [$clientIdentifier] disconnected")
          clients.remove(clientIdentifier)
        }.subscribeHandler { subscribe ->
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
        }.publishHandler { m -> handleMessage(m, client) }
      },
      onError = {
        // Wrong username or password, reject
        logger.info("Client [$clientIdentifier] rejected", it)
        client.reject(MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD)
      }
    )
  }

  private fun handleMessage(m: MqttPublishMessage, client: MqttEndpoint) {
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
        client.publishAcknowledge(m.messageId())
      }

      if (m.topicName() == INGESTION_TOPIC && validateJson(message)) {
        val beaconMacAddress: String = message["mac"]
        val kafkaMessage = message.copy().apply {
          this.put("timestamp", Instant.now())
        }
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

  private fun JsonObject.clean(): JsonObject = this.copy().apply {
    remove("_id")
    remove("mqttID")
    remove("mqttUsername")
    remove("mqttPassword")
    remove("latitude")
    remove("longitude")
    if (containsKey("lastModified")) {
      val lastModifiedObject: JsonObject = this["lastModified"]
      put("lastModified", lastModifiedObject["\$date"])
    }
  }

  private fun sendLastConfiguration(client: MqttEndpoint) {
    val query = jsonObjectOf("mqttID" to client.clientIdentifier())
    mongoClient.rxFindOne(RELAYS_COLLECTION, query, jsonObjectOf()).subscribeBy(
      onSuccess = { config ->
        if (config != null && !config.isEmpty) {
          // Remove useless fields and clean lastModified
          val cleanConfig = config.clean()
          sendMessageTo(client, cleanConfig, "last.configuration")
        }
      },
      onError = { error ->
        logger.error("Could not send last configuration to client ${client.clientIdentifier()}", error)
      }
    )
  }

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
