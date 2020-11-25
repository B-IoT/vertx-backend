package ch.biot.backend.updateparameters

import io.netty.handler.codec.mqtt.MqttQoS
import io.reactivex.Completable
import io.reactivex.rxkotlin.subscribeBy
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.ext.mongo.findOptionsOf
import io.vertx.kotlin.ext.mongo.updateOptionsOf
import io.vertx.reactivex.core.buffer.Buffer
import io.vertx.reactivex.ext.mongo.MongoClient
import io.vertx.reactivex.ext.web.Router
import io.vertx.reactivex.ext.web.RoutingContext
import io.vertx.reactivex.ext.web.handler.BodyHandler
import io.vertx.reactivex.mqtt.MqttEndpoint
import io.vertx.reactivex.mqtt.MqttServer
import org.slf4j.LoggerFactory

// TODO long-term, update multiple clients at once, the HTTP route takes a JSON array too
class UpdateParametersVerticle : io.vertx.reactivex.core.AbstractVerticle() {

  companion object {
    private const val RELAYS_COLLECTION = "relays"
  }

  private val logger = LoggerFactory.getLogger(UpdateParametersVerticle::class.java)
  private val clients = mutableMapOf<String, MqttEndpoint>() // map of subscribed clientIdentifier to client

  private lateinit var mongoClient: MongoClient

  override fun rxStart(): Completable {
    // TODO update with real configuration
    mongoClient =
      MongoClient.createShared(vertx, jsonObjectOf("host" to "localhost", "port" to 27017, "db_name" to "clients"))

    val router = Router.router(vertx)
    val bodyHandler = BodyHandler.create()
    router.put().handler(bodyHandler)
    router.put("/relays/update").handler(::validateBody).handler(::updateRelay)

    // TODO MQTT on TLS
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

  private fun updateRelay(ctx: RoutingContext) {
    val json = ctx.bodyAsJson
    json?.let {
      // Update MongoDB
      val query = jsonObjectOf("relayID" to it["targetID"])
      val update = json {
        obj(
          "\$set" to obj(
            "ledStatus" to it["ledStatus"],
            "wifi" to obj(
              "ssid" to it["ssid"],
              "password" to it["password"]
            )
          ),
          "\$currentDate" to obj("lastModified" to true)
        )
      }
      mongoClient.rxFindOneAndUpdateWithOptions(
        RELAYS_COLLECTION,
        query,
        update,
        findOptionsOf(),
        updateOptionsOf(returningNewDocument = true)
      ).subscribeBy(
        onSuccess = { result ->
          logger.info("Successfully updated MongoDB collection 'relays' with update JSON $update")
          // Send update to the right client subscribed via MQTT
          val mqttID: String = result["mqttID"]
          val cleanEntry = cleanMongoEntryForSending(result)
          clients[mqttID]?.let { client -> sendMessageTo(client, cleanEntry, "update.parameters", ctx) }
        },
        onError = { error ->
          logger.error("Could not update MongoDB collection 'relays' with update JSON $update", error)
          ctx.fail(500)
        }
      )
    } ?: ctx.fail(400)
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
    }
  }

  private fun cleanMongoEntryForSending(entry: JsonObject): JsonObject = entry.copy().apply {
    remove("_id")
    val lastModifiedObject: JsonObject = this["lastModified"]
    put("lastModified", lastModifiedObject["\$date"])
  }

  private fun sendLastConfiguration(client: MqttEndpoint) {
    val query = jsonObjectOf("mqttID" to client.clientIdentifier())
    mongoClient.rxFindOne(RELAYS_COLLECTION, query, jsonObjectOf()).subscribeBy(
      onSuccess = { config ->
        if (config != null && !config.isEmpty) {
          // Remove useless id field and clean lastModified
          val cleanConfig = cleanMongoEntryForSending(config)
          sendMessageTo(client, cleanConfig, "last.configuration")
        }
      },
      onError = { error ->
        logger.error("Could not send last configuration to client ${client.clientIdentifier()}", error)
      }
    )
  }

  private fun sendMessageTo(client: MqttEndpoint, message: JsonObject, topic: String, ctx: RoutingContext? = null) {
    client.publishAcknowledgeHandler { messageId ->
      logger.info("Received ack for message $messageId")
    }.rxPublish(topic, Buffer.newInstance(message.toBuffer()), MqttQoS.AT_LEAST_ONCE, false, false)
      .subscribeBy(
        onSuccess = { messageId ->
          logger.info("Published message $message with id $messageId to client ${client.clientIdentifier()} on topic $topic")
          ctx?.response()?.end()
        },
        onError = { error ->
          logger.error("Could not send message $message on topic $topic", error)
          ctx?.fail(500)
        }
      )
  }

  private fun validateJson(json: JsonObject): Boolean {
    val keysToContain = listOf("targetID", "ledStatus", "ssid", "password", "mac", "txPower")
    return keysToContain.fold(true) { acc, curr ->
      acc && json.containsKey(curr)
    }
  }
}
