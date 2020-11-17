package ch.biot.backend.updateparameters

import io.netty.handler.codec.mqtt.MqttQoS
import io.reactivex.Completable
import io.reactivex.rxkotlin.subscribeBy
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.reactivex.core.RxHelper
import io.vertx.reactivex.core.buffer.Buffer
import io.vertx.reactivex.ext.web.Router
import io.vertx.reactivex.ext.web.RoutingContext
import io.vertx.reactivex.ext.web.handler.BodyHandler
import io.vertx.reactivex.mqtt.MqttEndpoint
import io.vertx.reactivex.mqtt.MqttServer
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

// TODO long-term, update multiple clients at once, the route takes a JSON array
class UpdateParametersVerticle : io.vertx.reactivex.core.AbstractVerticle() {

  private val logger = LoggerFactory.getLogger(UpdateParametersVerticle::class.java)
  private val clients = mutableSetOf<MqttEndpoint>() // set of subscribed clients

  // TODO connect to MongoDB

  override fun rxStart(): Completable {
    val router = Router.router(vertx)
    val bodyHandler = BodyHandler.create()
    router.put().handler(bodyHandler)
    router.put("/relays/update").handler(::validateBody).handler(::updateRelays)

    MqttServer.create(vertx).endpointHandler(::handleClient).rxListen(8883).subscribe()

    return vertx.createHttpServer().requestHandler(router).rxListen(3000).ignoreElement()
  }

  private fun validateBody(ctx: RoutingContext) = if (ctx.body.length() == 0) {
    logger.warn("Bad request with empty body")
    ctx.fail(400)
  } else if (!validateJson(ctx.bodyAsJson)) {
    logger.warn("Bad request with body ${ctx.bodyAsJson}")
    ctx.fail(400)
  } else {
    ctx.next()
  }

  private fun updateRelays(ctx: RoutingContext) {
    val json = ctx.bodyAsJson
    json?.let {
      publishMessageToClients(it, ctx)
    } ?: ctx.fail(400)
  }

  private fun handleClient(client: MqttEndpoint) {
    val clientIdentifier = client.clientIdentifier()
    val isCleanSession = client.isCleanSession
    logger.info("Client [$clientIdentifier] request to connect, clean session = $isCleanSession")

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

    // accept connection from the remote client
    logger.info("Client [$clientIdentifier] connected")

    val sessionPresent = !client.isCleanSession
    client.accept(sessionPresent).disconnectHandler {
      logger.info("Client [$clientIdentifier] disconnected")
      clients.remove(client)
    }.subscribeHandler { subscribe ->
      val grantedQosLevels = subscribe.topicSubscriptions().map { s ->
        logger.info("Subscription for [${s.topicName()}] with QoS [${s.qualityOfService()}] by client [$clientIdentifier]")
        s.qualityOfService()
      }

      // ack the subscriptions request
      client.subscribeAcknowledge(subscribe.messageId(), grantedQosLevels)
      clients.add(client)
      // TODO send last configuration to client
    }.unsubscribeHandler { unsubscribe ->
      unsubscribe.topics().forEach { topic ->
        logger.info("Unsubscription for [$topic] by client [$clientIdentifier]")
      }
      clients.remove(client)
    }
  }

  private fun publishMessageToClients(message: JsonObject, ctx: RoutingContext) {
    if (clients.isEmpty()) {
      logger.warn("Trying to publish message but no client subscribed")
      ctx.response().end()
    } else {
      clients.forEach { client ->
        client.publishAcknowledgeHandler { messageId ->
          logger.info("Received ack for message $messageId")
        }.rxPublish("update.parameters", Buffer.newInstance(message.toBuffer()), MqttQoS.AT_LEAST_ONCE, false, false)
          .retryWhen {
            it.delay(1, TimeUnit.SECONDS, RxHelper.scheduler(vertx))
          }
          .subscribeBy(
            onSuccess = { messageId ->
              logger.info("Published message $message with id $messageId to all clients")
              ctx.response().end()
            },
            onError = { error ->
              logger.error("Could not send message $message", error)
              ctx.fail(500)
            }
          )
      }
    }
  }

  private fun validateJson(json: JsonObject): Boolean {
    val keysToContain = listOf("targetID", "ledStatus", "ssid", "password", "mac", "txPower")
    return keysToContain.fold(true) { acc, curr ->
      acc && json.containsKey(curr)
    }
  }

  private fun buildMessage(data: Map<String, Any>): JsonObject {
    return json {
      obj(
        "targetID" to data["targetID"],
        "ledStatus" to data["ledStatus"],
        "wifi" to obj(
          "ssid" to data["ssid"],
          "password" to data["password"]
        ),
        "beacon" to obj(
          "mac" to data["mac"],
          "txPower" to data["txPower"]
        )
      )
    }
  }
}
