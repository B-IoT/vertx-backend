package ch.biot.backend.ingestion

import io.netty.handler.codec.mqtt.MqttQoS
import io.reactivex.Completable
import io.reactivex.rxkotlin.subscribeBy
import io.vertx.core.json.DecodeException
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get
import io.vertx.reactivex.kafka.client.producer.KafkaProducer
import io.vertx.reactivex.kafka.client.producer.KafkaProducerRecord
import io.vertx.reactivex.mqtt.MqttEndpoint
import io.vertx.reactivex.mqtt.MqttServer
import io.vertx.reactivex.mqtt.messages.MqttPublishMessage
import org.slf4j.LoggerFactory


class IngestionVerticle : io.vertx.reactivex.core.AbstractVerticle() {

  companion object {
    private const val INGESTION_TOPIC = "incoming.update"
  }

  private val logger = LoggerFactory.getLogger(IngestionVerticle::class.java)
  private lateinit var kafkaProducer: KafkaProducer<String, JsonObject>

  override fun rxStart(): Completable {
    kafkaProducer = KafkaProducer.create(
      vertx, mapOf(
        "bootstrap.servers" to "localhost:9092",
        "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
        "value.serializer" to "io.vertx.kafka.client.serialization.JsonObjectSerializer",
        "acks" to "1"
      )
    )

    return MqttServer.create(vertx).endpointHandler(::handleClient).rxListen(8883).ignoreElement()
  }

  private fun handleClient(client: MqttEndpoint) {
    val clientIdentifier = client.clientIdentifier()
    val isCleanSession = client.isCleanSession
    logger.info("Client [$clientIdentifier] request to connect, clean session = $isCleanSession")

    // TODO MQTT auth

    // Accept connection from the remote client
    logger.info("Client [$clientIdentifier] connected")
    val sessionPresent = !client.isCleanSession
    client.accept(sessionPresent).disconnectHandler {
      logger.info("Client [$clientIdentifier] disconnected")
    }.publishHandler { m -> handleMessage(m, client) }
  }

  private fun handleMessage(m: MqttPublishMessage, client: MqttEndpoint) {
    try {
      val message = m.payload().toJsonObject()
      logger.info("Received message $message from client ${client.clientIdentifier()}")

      if (m.qosLevel() == MqttQoS.AT_LEAST_ONCE) {
        client.publishAcknowledge(m.messageId())
      }

      if (m.topicName() == INGESTION_TOPIC && validateJson(message)) {
        val beaconMacAddress: String = message["mac"]

        val record = KafkaProducerRecord.create(INGESTION_TOPIC, beaconMacAddress, message)
        kafkaProducer.rxSend(record).subscribeBy(
          onSuccess = {
            logger.info("Sent message $message with key '$beaconMacAddress' on topic '$INGESTION_TOPIC'")
          },
          onError = { error ->
            logger.error(
              "Could not send message $message with key '$beaconMacAddress' on topic '$INGESTION_TOPIC'",
              error
            )
          }
        )
      }
    } catch (e: DecodeException) {
      logger.error("Could not decode MQTT message $m", e)
    }
  }

  private fun validateJson(json: JsonObject): Boolean {
    val keysToContain = listOf("relayID", "timestamp", "battery", "rssi", "mac", "isPushed")
    return keysToContain.fold(true) { acc, curr ->
      acc && json.containsKey(curr)
    }
  }
}
