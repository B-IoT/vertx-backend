package ch.biot.backend.ingestion

import io.reactivex.Completable
import io.reactivex.rxkotlin.subscribeBy
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.reactivex.kafka.client.producer.KafkaProducer
import io.vertx.reactivex.kafka.client.producer.KafkaProducerRecord
import io.vertx.reactivex.mqtt.MqttEndpoint
import io.vertx.reactivex.mqtt.MqttServer
import io.vertx.reactivex.mqtt.messages.MqttPublishMessage
import org.slf4j.LoggerFactory


class IngestionVerticle : io.vertx.reactivex.core.AbstractVerticle() {

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

  private fun handleClient(endpoint: MqttEndpoint) {
    logger.info("MQTT client [" + endpoint.clientIdentifier() + "] request to connect, clean session = " + endpoint.isCleanSession)

    //TODO It freezes with this and mosquitto_pub
//        if (endpoint.auth() != null) {
//          println("[username = " + endpoint.auth().username + ", password = " + endpoint.auth().password + "]")
//        }
//        if (endpoint.will() != null) {
//          println(
//            "[will topic = " + endpoint.will().willTopic + " msg = " + String(endpoint.will().willMessageBytes) +
//              " QoS = " + endpoint.will().willQos + " isRetain = " + endpoint.will().isWillRetain + "]"
//          )
//        }

    logger.info("[keep alive timeout = " + endpoint.keepAliveTimeSeconds() + "]")

    // accept connection from the remote client
    endpoint.accept(false).publishHandler(::handleMessage)
  }

  private fun handleMessage(m: MqttPublishMessage) {
    val payload = m.payload().toJsonObject()
    logger.info(payload.toString())
    // TODO check if invalid payload
    val deviceId: String = payload["deviceId"]
    val data = json {
      obj { } // TODO
    }

    val record = KafkaProducerRecord.create("incoming.update", deviceId, data)
    kafkaProducer.rxSend(record).subscribeBy(
      onSuccess = { },
      onError = { logger.error("Error while sending message to Kafka", it) }
    )
  }
}
