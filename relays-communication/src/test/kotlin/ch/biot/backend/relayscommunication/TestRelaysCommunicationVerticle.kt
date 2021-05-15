/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.relayscommunication

import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.INGESTION_TOPIC
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.KAFKA_PORT
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.MONGO_PORT
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.RELAYS_COLLECTION
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.RELAYS_UPDATE_ADDRESS
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.UPDATE_PARAMETERS_TOPIC
import io.netty.handler.codec.mqtt.MqttQoS
import io.reactivex.Maybe
import io.reactivex.rxkotlin.subscribeBy
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import io.vertx.kotlin.ext.mongo.indexOptionsOf
import io.vertx.kotlin.mqtt.mqttClientOptionsOf
import io.vertx.reactivex.core.RxHelper
import io.vertx.reactivex.core.Vertx
import io.vertx.reactivex.core.buffer.Buffer
import io.vertx.reactivex.ext.auth.mongo.MongoAuthentication
import io.vertx.reactivex.ext.auth.mongo.MongoUserUtil
import io.vertx.reactivex.ext.mongo.MongoClient
import io.vertx.reactivex.kafka.admin.KafkaAdminClient
import io.vertx.reactivex.kafka.client.consumer.KafkaConsumer
import io.vertx.reactivex.mqtt.MqttClient
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import java.io.File
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
@Testcontainers
class TestRelaysCommunicationVerticle {

  private lateinit var kafkaConsumer: KafkaConsumer<String, JsonObject>

  private lateinit var mongoClient: MongoClient
  private lateinit var mongoUserUtil: MongoUserUtil
  private lateinit var mongoAuth: MongoAuthentication
  private lateinit var mqttClient: MqttClient

  private val mqttPassword = "password"
  private val configuration = jsonObjectOf(
    "mqttID" to "mqtt",
    "mqttUsername" to "test",
    "relayID" to "relay",
    "ledStatus" to false,
    "latitude" to 0.1,
    "longitude" to 0.3,
    "wifi" to jsonObjectOf(
      "ssid" to "ssid",
      "password" to "pass"
    )
  )

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext) {
    val kafkaConfig = mapOf(
      "bootstrap.servers" to "localhost:$KAFKA_PORT",
      "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
      "value.deserializer" to "io.vertx.kafka.client.serialization.JsonObjectDeserializer",
      "auto.offset.reset" to "earliest",
      "enable.auto.commit" to "false",
      "group.id" to "ingester-test-${System.currentTimeMillis()}"
    )

    kafkaConsumer = KafkaConsumer.create(vertx, kafkaConfig)
    val kafkaAdminClient = KafkaAdminClient.create(vertx, kafkaConfig)

    mqttClient = MqttClient.create(
      vertx,
      mqttClientOptionsOf(
        clientId = configuration["mqttID"],
        username = configuration["mqttUsername"],
        password = mqttPassword,
        willFlag = true,
        willMessage = jsonObjectOf("company" to "biot").encode()
      )
    )

    mongoClient =
      MongoClient.createShared(vertx, jsonObjectOf("host" to "localhost", "port" to MONGO_PORT, "db_name" to "clients"))

    val usernameField = "mqttUsername"
    val passwordField = "mqttPassword"
    val mongoAuthOptions = mongoAuthenticationOptionsOf(
      collectionName = RELAYS_COLLECTION,
      passwordCredentialField = passwordField,
      passwordField = passwordField,
      usernameCredentialField = usernameField,
      usernameField = usernameField
    )

    mongoUserUtil = MongoUserUtil.create(
      mongoClient, mongoAuthOptions, mongoAuthorizationOptionsOf()
    )
    mongoAuth = MongoAuthentication.create(mongoClient, mongoAuthOptions)

    mongoClient
      .rxCreateIndexWithOptions(RELAYS_COLLECTION, jsonObjectOf("relayID" to 1), indexOptionsOf().unique(true))
      .andThen(
        mongoClient.rxCreateIndexWithOptions(
          RELAYS_COLLECTION,
          jsonObjectOf("mqttID" to 1),
          indexOptionsOf().unique(true)
        )
      )
      .andThen(
        mongoClient.rxCreateIndexWithOptions(
          RELAYS_COLLECTION, jsonObjectOf("mqttUsername" to 1),
          indexOptionsOf().unique(true)
        )
      )
      .andThen(dropAllRelays())
      .flatMap { insertRelay() }
      .flatMapSingle { vertx.rxDeployVerticle(RelaysCommunicationVerticle()) }
      .delay(500, TimeUnit.MILLISECONDS, RxHelper.scheduler(vertx))
      .flatMapCompletable { kafkaAdminClient.rxDeleteTopics(listOf(INGESTION_TOPIC)) }
      .onErrorComplete()
      .subscribe(testContext::completeNow, testContext::failNow)
  }

  private fun dropAllRelays() = mongoClient.rxRemoveDocuments(RELAYS_COLLECTION, jsonObjectOf())

  private fun insertRelay(): Maybe<JsonObject> {
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)
    val hashedPassword = mongoAuth.hash("pbkdf2", String(Base64.getEncoder().encode(salt)), mqttPassword)
    return mongoUserUtil
      .rxCreateHashedUser("test", hashedPassword)
      .flatMapMaybe { docID ->
        val query = jsonObjectOf("_id" to docID)
        val extraInfo = jsonObjectOf(
          "\$set" to configuration
        )
        mongoClient.rxFindOneAndUpdate(RELAYS_COLLECTION, query, extraInfo)
      }
  }

  @AfterEach
  fun cleanup(testContext: VertxTestContext) {
    dropAllRelays()
      .doFinally { mongoClient.rxClose() }
      .subscribe(
        { testContext.completeNow() },
        testContext::failNow
      )
  }

  @Test
  @DisplayName("A MQTT client upon subscription receives the last configuration")
  fun clientSubscribesAndReceivesLastConfig(testContext: VertxTestContext) {
    mqttClient
      .rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .flatMap {
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            testContext.verify {
              val expected = configuration.copy().apply {
                remove("mqttID")
                remove("mqttUsername")
                remove("ledStatus")
              }
              expectThat(msg.payload().toJsonObject()).isEqualTo(expected)
              testContext.completeNow()
            }
          }
        }.rxSubscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value())
      }.subscribeBy(
        onError = testContext::failNow
      )
  }

  @Test
  @DisplayName("A MQTT client without authentication is refused connection")
  fun clientWithoutAuthIsRefusedConnection(vertx: Vertx, testContext: VertxTestContext) {
    val client = MqttClient.create(vertx)

    client
      .rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .subscribeBy(
        onError = { testContext.completeNow() },
        onSuccess = { testContext.failNow("The client was able to connect without authentication") }
      )
  }

  @Test
  @DisplayName("A MQTT client with a wrong password is refused connection")
  fun clientWithWrongPasswordIsRefusedConnection(vertx: Vertx, testContext: VertxTestContext) {
    val client = MqttClient.create(
      vertx,
      mqttClientOptionsOf(
        clientId = configuration["mqttID"],
        username = configuration["mqttUsername"],
        password = "wrongPassword",
        willFlag = true,
        willMessage = jsonObjectOf("company" to "biot").encode()
      )
    )

    client
      .rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .subscribeBy(
        onError = { testContext.completeNow() },
        onSuccess = { testContext.failNow("The client was able to connect with a wrong password") }
      )
  }

  @Test
  @DisplayName("A MQTT client receives updates")
  fun clientReceivesUpdate(vertx: Vertx, testContext: VertxTestContext) {
    val message = jsonObjectOf("latitude" to 42.3, "mqttID" to "mqtt")
    mqttClient.publishHandler { msg ->
      if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
        val json = msg.payload().toJsonObject()
        if (!json.containsKey("relayID")) { // only handle received message, not the one for the last configuration
          testContext.verify {
            val messageWithoutMqttID = message.copy().apply { remove("mqttID") }
            expectThat(json).isEqualTo(messageWithoutMqttID)
            testContext.completeNow()
          }
        }
      }
    }.rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .flatMap {
        mqttClient.rxSubscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value())
      }
      .subscribeBy(
        onSuccess = {
          vertx.eventBus().send(RELAYS_UPDATE_ADDRESS, message)
        },
        onError = testContext::failNow
      )
  }

  @Test
  @DisplayName("A well-formed MQTT JSON message is ingested and streamed to Kafka")
  fun mqttMessageIsIngested(testContext: VertxTestContext) {
    val message = jsonObjectOf(
      "relayID" to "abc",
      "beacons" to jsonArrayOf(
        jsonObjectOf(
          "mac" to "aa:aa:aa:aa:aa:aa",
          "rssi" to -60.0,
          "battery" to 42,
          "temperature" to 25,
          "status" to 0
        ),
        jsonObjectOf(
          "mac" to "bb:aa:aa:aa:aa:aa",
          "rssi" to -59.0,
          "battery" to 100,
          "temperature" to 20,
          "status" to 1
        )
      ),
      "latitude" to 2.3,
      "longitude" to 2.3,
      "floor" to 1
    )

    mqttClient
      .rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .flatMap {
        mqttClient.rxPublish(
          INGESTION_TOPIC,
          Buffer.newInstance(message.toBuffer()),
          MqttQoS.AT_LEAST_ONCE,
          false,
          false
        )
      }
      .subscribeBy(onError = testContext::failNow)

    kafkaConsumer
      .rxSubscribe(INGESTION_TOPIC)
      .andThen(
        kafkaConsumer.toFlowable()
      )
      .subscribe(
        { record ->
          testContext.verify {
            val relayID = message.getString("relayID")
            expectThat(record.key()).isEqualTo(relayID)
            val json = record.value()
            expect {
              that(json.getString("relayID")).isEqualTo(relayID)
              that(json.getString("timestamp")).isNotNull()
              that(json.getJsonArray("beacons")).isEqualTo(message.getJsonArray("beacons"))
              that(json.getDouble("latitude")).isEqualTo(message.getDouble("latitude"))
              that(json.getDouble("longitude")).isEqualTo(message.getDouble("longitude"))
              that(json.getInteger("floor")).isEqualTo(message.getInteger("floor"))
              that(json.getDouble("temperature")).isEqualTo(message.getDouble("temperature"))
            }
            testContext.completeNow()
          }
        },
        testContext::failNow
      )
  }

  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because it is missing fields")
  fun invalidMqttMessageIsNotIngestedWrongFields(testContext: VertxTestContext) {
    val message = jsonObjectOf(
      "relayID" to "abc",
      "beacons" to jsonArrayOf(
        jsonObjectOf(
          "mac" to "aa:aa:aa:aa:aa:aa",
          "rssi" to -60.0,
          "battery" to 42,
          "temperature" to 25,
          "status" to 0
        ),
        jsonObjectOf(
          "mac" to "bb:aa:aa:aa:aa:aa",
          "rssi" to -59.0,
          "battery" to 100,
          "temperature" to 20,
          "status" to 1
        )
      ),
      "longitude" to 2.3,
      "floor" to 1
    )

    mqttClient.rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .flatMap {
        mqttClient.rxPublish(
          INGESTION_TOPIC,
          Buffer.newInstance(message.toBuffer()),
          MqttQoS.AT_LEAST_ONCE,
          false,
          false
        )
      }.subscribeBy(onError = testContext::failNow)

    kafkaConsumer
      .rxSubscribe(INGESTION_TOPIC).subscribe()

    testContext.verify {
      kafkaConsumer.toFlowable().timeout(5, TimeUnit.SECONDS).firstOrError().subscribeBy(
        onSuccess = { testContext.failNow("The message was ingested") },
        onError = { testContext.completeNow() }
      )
    }
  }

  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because it is missing fields in the beacons field")
  fun invalidMqttMessageIsNotIngestedWrongBeaconsFields(testContext: VertxTestContext) {
    val message = jsonObjectOf(
      "relayID" to "abc",
      "beacons" to jsonArrayOf(
        jsonObjectOf(
          "mac" to "aa:aa:aa:aa:aa:aa",
          "rssi" to -60.0,
          "temperature" to 25,
          "status" to 0
        ),
        jsonObjectOf(
          "mac" to "bb:aa:aa:aa:aa:aa",
          "rssi" to -59.0,
          "battery" to 100,
          "temperature" to 20,
          "status" to 1
        )
      ),
      "longitude" to 2.3,
      "floor" to 1
    )

    mqttClient
      .rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .flatMap {
        mqttClient.rxPublish(
          INGESTION_TOPIC,
          Buffer.newInstance(message.toBuffer()),
          MqttQoS.AT_LEAST_ONCE,
          false,
          false
        )
      }.subscribeBy(onError = testContext::failNow)

    testContext.verify {
      kafkaConsumer
        .rxSubscribe(INGESTION_TOPIC)
        .andThen(kafkaConsumer.toFlowable())
        .timeout(5, TimeUnit.SECONDS)
        .firstOrError()
        .subscribeBy(
          onSuccess = { testContext.failNow("The message was ingested") },
          onError = { testContext.completeNow() }
        )
    }
  }

  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because it has zero coordinates")
  fun invalidMqttMessageIsNotIngestedWrongCoordinates(testContext: VertxTestContext) {
    val message = jsonObjectOf(
      "relayID" to "abc",
      "beacons" to jsonArrayOf(
        jsonObjectOf(
          "mac" to "aa:aa:aa:aa:aa:aa",
          "rssi" to -60.0,
          "battery" to 42,
          "temperature" to 25,
          "status" to 0
        ),
        jsonObjectOf(
          "mac" to "bb:aa:aa:aa:aa:aa",
          "rssi" to -59.0,
          "battery" to 100,
          "temperature" to 20,
          "status" to 1
        )
      ),
      "latitude" to 2.3,
      "longitude" to 0,
      "floor" to 1
    )

    mqttClient.rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .flatMap {
        mqttClient.rxPublish(
          INGESTION_TOPIC,
          Buffer.newInstance(message.toBuffer()),
          MqttQoS.AT_LEAST_ONCE,
          false,
          false
        )
      }.subscribeBy(onError = testContext::failNow)

    kafkaConsumer
      .rxSubscribe(INGESTION_TOPIC).subscribe()

    testContext.verify {
      kafkaConsumer.toFlowable().timeout(5, TimeUnit.SECONDS).firstOrError().subscribeBy(
        onSuccess = { testContext.failNow("The message was ingested") },
        onError = { testContext.completeNow() }
      )
    }
  }

  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because it has empty MACs")
  fun invalidMqttMessageIsNotIngestedEmptyMac(testContext: VertxTestContext) {
    val message = jsonObjectOf(
      "relayID" to "abc",
      "beacons" to jsonArrayOf(
        jsonObjectOf(
          "mac" to "",
          "rssi" to -60.0,
          "battery" to 42,
          "temperature" to 25,
          "status" to 0
        ),
        jsonObjectOf(
          "mac" to "bb:aa:aa:aa:aa:aa",
          "rssi" to -59.0,
          "battery" to 0,
          "temperature" to 20,
          "status" to 1
        )
      ),
      "latitude" to 2.3,
      "longitude" to 2.3,
      "floor" to 1
    )

    mqttClient
      .rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .flatMap {
        mqttClient.rxPublish(
          INGESTION_TOPIC,
          Buffer.newInstance(message.toBuffer()),
          MqttQoS.AT_LEAST_ONCE,
          false,
          false
        )
      }.subscribeBy(onError = testContext::failNow)

    kafkaConsumer
      .rxSubscribe(INGESTION_TOPIC).subscribe()

    testContext.verify {
      kafkaConsumer
        .rxSubscribe(INGESTION_TOPIC)
        .andThen(kafkaConsumer.toFlowable())
        .timeout(5, TimeUnit.SECONDS)
        .firstOrError()
        .subscribeBy(
          onSuccess = { testContext.failNow("The message was ingested") },
          onError = { testContext.completeNow() }
        )
    }
  }

  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because a beacon has a too large battery level")
  fun invalidMqttMessageIsNotIngestedInvalidBatteryTooLarge(testContext: VertxTestContext) {
    val message = jsonObjectOf(
      "relayID" to "abc",
      "beacons" to jsonArrayOf(
        jsonObjectOf(
          "mac" to "aa:aa:aa:aa:aa:aa",
          "rssi" to -60.0,
          "battery" to 101,
          "temperature" to 25,
          "status" to 0
        ),
        jsonObjectOf(
          "mac" to "bb:aa:aa:aa:aa:aa",
          "rssi" to -59.0,
          "battery" to 100,
          "temperature" to 20,
          "status" to 1
        )
      ),
      "latitude" to 2.3,
      "longitude" to 2.3,
      "floor" to 1
    )

    mqttClient
      .rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .flatMap {
        mqttClient.rxPublish(
          INGESTION_TOPIC,
          Buffer.newInstance(message.toBuffer()),
          MqttQoS.AT_LEAST_ONCE,
          false,
          false
        )
      }.subscribeBy(onError = testContext::failNow)

    testContext.verify {
      kafkaConsumer
        .rxSubscribe(INGESTION_TOPIC)
        .andThen(kafkaConsumer.toFlowable())
        .timeout(5, TimeUnit.SECONDS)
        .firstOrError()
        .subscribeBy(
          onSuccess = { testContext.failNow("The message was ingested") },
          onError = { testContext.completeNow() }
        )
    }
  }

  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because a beacon has a too small battery level")
  fun invalidMqttMessageIsNotIngestedInvalidBatteryTooSmall(testContext: VertxTestContext) {
    val message = jsonObjectOf(
      "relayID" to "abc",
      "beacons" to jsonArrayOf(
        jsonObjectOf(
          "mac" to "aa:aa:aa:aa:aa:aa",
          "rssi" to -60.0,
          "battery" to -10,
          "temperature" to 25,
          "status" to 0
        ),
        jsonObjectOf(
          "mac" to "bb:aa:aa:aa:aa:aa",
          "rssi" to -59.0,
          "battery" to 100,
          "temperature" to 20,
          "status" to 1
        )
      ),
      "latitude" to 2.3,
      "longitude" to 2.3,
      "floor" to 1
    )

    mqttClient.rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .flatMap {
        mqttClient.rxPublish(
          INGESTION_TOPIC,
          Buffer.newInstance(message.toBuffer()),
          MqttQoS.AT_LEAST_ONCE,
          false,
          false
        )
      }.subscribeBy(onError = testContext::failNow)

    kafkaConsumer
      .rxSubscribe(INGESTION_TOPIC).subscribe()

    testContext.verify {
      kafkaConsumer.toFlowable().timeout(5, TimeUnit.SECONDS).firstOrError().subscribeBy(
        onSuccess = { testContext.failNow("The message was ingested") },
        onError = { testContext.completeNow() }
      )
    }
  }

  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because a beacon has an invalid status")
  fun invalidMqttMessageIsNotIngestedInvalidStatus(testContext: VertxTestContext) {
    val message = jsonObjectOf(
      "relayID" to "abc",
      "beacons" to jsonArrayOf(
        jsonObjectOf(
          "mac" to "aa:aa:aa:aa:aa:aa",
          "rssi" to -60.0,
          "battery" to -10,
          "temperature" to 25,
          "status" to -1
        ),
        jsonObjectOf(
          "mac" to "bb:aa:aa:aa:aa:aa",
          "rssi" to -59.0,
          "battery" to 100,
          "temperature" to 20,
          "status" to 2
        )
      ),
      "latitude" to 2.3,
      "longitude" to 2.3,
      "floor" to 1
    )

    mqttClient
      .rxConnect(RelaysCommunicationVerticle.MQTT_PORT, "localhost")
      .flatMap {
        mqttClient.rxPublish(
          INGESTION_TOPIC,
          Buffer.newInstance(message.toBuffer()),
          MqttQoS.AT_LEAST_ONCE,
          false,
          false
        )
      }.subscribeBy(onError = testContext::failNow)

    kafkaConsumer
      .rxSubscribe(INGESTION_TOPIC).subscribe()

    testContext.verify {
      kafkaConsumer
        .rxSubscribe(INGESTION_TOPIC)
        .andThen(kafkaConsumer.toFlowable())
        .timeout(5, TimeUnit.SECONDS)
        .firstOrError()
        .subscribeBy(
          onSuccess = { testContext.failNow("The message was ingested") },
          onError = { testContext.completeNow() }
        )
    }
  }

  companion object {

    private val instance: KDockerComposeContainer by lazy { defineDockerCompose() }

    class KDockerComposeContainer(file: File) : DockerComposeContainer<KDockerComposeContainer>(file)

    private fun defineDockerCompose() =
      KDockerComposeContainer(File("../docker-compose.yml")).withExposedService("mongo_1", MONGO_PORT)

    @BeforeAll
    @JvmStatic
    fun beforeAll() {
      instance.start()
    }

    @AfterAll
    @JvmStatic
    fun afterAll() {
      instance.stop()
    }
  }
}
