package ch.biot.backend.updateparameters

import io.netty.handler.codec.mqtt.MqttQoS
import io.reactivex.Completable
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.rxkotlin.toFlowable
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.reactivex.core.RxHelper
import io.vertx.reactivex.core.buffer.Buffer
import io.vertx.reactivex.kafka.client.producer.KafkaProducer
import io.vertx.reactivex.kafka.client.producer.KafkaProducerRecord
import io.vertx.reactivex.mqtt.MqttEndpoint
import io.vertx.reactivex.mqtt.MqttServer
import io.vertx.reactivex.mqtt.messages.MqttPublishMessage
import io.vertx.reactivex.mqtt.messages.MqttSubscribeMessage
import org.slf4j.LoggerFactory


class UpdateParametersVerticle : io.vertx.reactivex.core.AbstractVerticle() {

  private val logger = LoggerFactory.getLogger(UpdateParametersVerticle::class.java)
  private val clients = mutableSetOf<MqttEndpoint>()

  override fun rxStart(): Completable {

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
    endpoint.accept(false).subscribeHandler { subscribe ->
      val grantedQosLevels = subscribe.topicSubscriptions().map { s ->
        logger.info("Subscription for ${s.topicName()} with QoS ${s.qualityOfService()}")
        s.qualityOfService()
      }

      // ack the subscriptions request
      endpoint.subscribeAcknowledge(subscribe.messageId(), grantedQosLevels)
    }

    clients.add(endpoint)
  }

  private fun publishMessageToClients(message: JsonObject) {
    clients.toFlowable().map { client ->
      client.rxPublish("update.parameters", Buffer.newInstance(message.toBuffer()), MqttQoS.AT_LEAST_ONCE, false, false)
        

    }
  }
}
