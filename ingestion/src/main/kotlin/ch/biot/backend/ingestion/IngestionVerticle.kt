package ch.biot.backend.ingestion

import io.vertx.core.json.JsonObject
import io.vertx.kafka.client.producer.KafkaProducer
import io.vertx.kafka.client.producer.KafkaProducerRecord
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttServer
import io.vertx.mqtt.MqttServerOptions
import io.vertx.mqtt.messages.MqttPublishMessage
import kotlinx.coroutines.launch


class IngestionVerticle : CoroutineVerticle() {

  private lateinit var kafkaProducer: KafkaProducer<String, JsonObject>;

  override suspend fun start() {
    kafkaProducer = KafkaProducer.create(
      vertx, mapOf(
        "bootstrap.servers" to "localhost:9092",
        "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
        "value.serializer" to "io.vertx.kafka.client.serialization.JsonObjectSerializer",
        "acks" to "1"
      )
    )

    try {


      MqttServer.create(vertx, MqttServerOptions().setPort(8882)).endpointHandler { endpoint ->
        // shows main connect info
        println("MQTT client [" + endpoint.clientIdentifier() + "] request to connect, clean session = " + endpoint.isCleanSession)

        // TODO It freezes with this and mosquitto_pub
//        if (endpoint.auth() != null) {
//          println("[username = " + endpoint.auth().username + ", password = " + endpoint.auth().password + "]")
//        }
//        if (endpoint.will() != null) {
//          println(
//            "[will topic = " + endpoint.will().willTopic + " msg = " + String(endpoint.will().willMessageBytes) +
//              " QoS = " + endpoint.will().willQos + " isRetain = " + endpoint.will().isWillRetain + "]"
//          )
//        }

        println("[keep alive timeout = " + endpoint.keepAliveTimeSeconds() + "]")

        // accept connection from the remote client
        endpoint.accept(false)

        endpoint.publishHandler { m ->
          launch(vertx.dispatcher()) {
            messageHandler(m)
          }
        }
      }.listen(8882).await()
    } catch (e: Exception) {
      println(e.message)
    }
  }

  private suspend fun messageHandler(m: MqttPublishMessage) {
    val payload = m.payload().toJsonObject()
    println(payload)
    // TODO check if invalid payload
    val deviceId: String = payload["deviceId"]
    val data = json {
      obj { } // TODO
    }
    val record = KafkaProducerRecord.create("incoming.update", deviceId, data)
    kafkaProducer.send(record).await()
  }
}
