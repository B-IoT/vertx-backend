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
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoAuthentication
import io.vertx.ext.auth.mongo.MongoUserUtil
import io.vertx.ext.mongo.MongoClient
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kafka.client.consumer.KafkaConsumer
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.coroutines.toChannel
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import io.vertx.kotlin.ext.mongo.indexOptionsOf
import io.vertx.kotlin.mqtt.mqttClientOptionsOf
import io.vertx.mqtt.MqttClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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

  private val configurationAnotherCompany = jsonObjectOf(
    "mqttID" to "mqtt2",
    "mqttUsername" to "test2",
    "relayID" to "relay2",
    "ledStatus" to false,
    "latitude" to 2,
    "longitude" to 3,
    "wifi" to jsonObjectOf(
      "ssid" to "ssid2",
      "password" to "pass2"
    )
  )

  private val anotherCompanyName = "anotherCompany"
  private val anotherCompanyCollection = RELAYS_COLLECTION + "_$anotherCompanyName"


  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext) = runBlocking(vertx.dispatcher()) {
    val kafkaConfig = mapOf(
      "bootstrap.servers" to "localhost:$KAFKA_PORT",
      "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
      "value.deserializer" to "io.vertx.kafka.client.serialization.JsonObjectDeserializer",
      "auto.offset.reset" to "earliest",
      "enable.auto.commit" to "false",
      "group.id" to "ingester-test-${System.currentTimeMillis()}"
    )

    kafkaConsumer = KafkaConsumer.create(vertx, kafkaConfig)

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

    try {
      mongoClient
        .createIndexWithOptions(RELAYS_COLLECTION, jsonObjectOf("relayID" to 1), indexOptionsOf().unique(true)).await()

      mongoClient.createIndexWithOptions(
        RELAYS_COLLECTION,
        jsonObjectOf("mqttID" to 1),
        indexOptionsOf().unique(true)
      ).await()

      mongoClient.createIndexWithOptions(
        RELAYS_COLLECTION, jsonObjectOf("mqttUsername" to 1),
        indexOptionsOf().unique(true)
      ).await()

      mongoClient
        .createIndexWithOptions(anotherCompanyCollection, jsonObjectOf("relayID" to 1), indexOptionsOf().unique(true)).await()

      mongoClient.createIndexWithOptions(
        anotherCompanyCollection,
        jsonObjectOf("mqttID" to 1),
        indexOptionsOf().unique(true)
      ).await()

      mongoClient.createIndexWithOptions(
        anotherCompanyCollection, jsonObjectOf("mqttUsername" to 1),
        indexOptionsOf().unique(true)
      ).await()

      dropAllRelays()
      insertRelays()
      vertx.deployVerticle(RelaysCommunicationVerticle()).await()
      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  private suspend fun dropAllRelays() {
    mongoClient.removeDocuments(RELAYS_COLLECTION, jsonObjectOf()).await()
    mongoClient.removeDocuments(anotherCompanyCollection, jsonObjectOf()).await()
  }

    private suspend fun insertRelays(): JsonObject {
      val salt = ByteArray(16)
      SecureRandom().nextBytes(salt)
      val hashedPassword = mongoAuth.hash("pbkdf2", String(Base64.getEncoder().encode(salt)), mqttPassword)
      val docID = mongoUserUtil.createHashedUser("test", hashedPassword).await()
      val query = jsonObjectOf("_id" to docID)
      val extraInfo = jsonObjectOf(
        "\$set" to configuration
      )
      mongoClient.findOneAndUpdate(RELAYS_COLLECTION, query, extraInfo).await()

      val salt2 = ByteArray(16)
      SecureRandom().nextBytes(salt2)
      val hashedPassword2 = mongoAuth.hash("pbkdf2", String(Base64.getEncoder().encode(salt2)), mqttPassword)
      val docID2 = mongoUserUtil.createHashedUser("test2", hashedPassword2).await()
      val query2 = jsonObjectOf("_id" to docID2)
      val extraInfo2 = jsonObjectOf(
        "\$set" to configurationAnotherCompany
      )
      return mongoClient.findOneAndUpdate(anotherCompanyCollection, query2, extraInfo2).await()

    }

    @AfterEach
    fun cleanup(vertx: Vertx, testContext: VertxTestContext) = runBlocking(vertx.dispatcher()) {
      try {
        dropAllRelays()
        mongoClient.close().await()
        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

    @Test
    @DisplayName("A MQTT client upon subscription receives the last configuration")
    fun clientSubscribesAndReceivesLastConfig(vertx: Vertx, testContext: VertxTestContext): Unit =
      runBlocking(vertx.dispatcher()) {
        try {
          mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
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
          }.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
        } catch (error: Throwable) {
          testContext.failNow(error)
        }
      }

    @Test
    @DisplayName("A MQTT client without authentication is refused connection")
    fun clientWithoutAuthIsRefusedConnection(vertx: Vertx, testContext: VertxTestContext) =
      runBlocking(vertx.dispatcher()) {
        val client = MqttClient.create(vertx)

        try {
          client.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
          testContext.failNow("The client was able to connect without authentication")
        } catch (error: Throwable) {
          testContext.completeNow()
        }
      }

    @Test
    @DisplayName("A MQTT client with a wrong password is refused connection")
    fun clientWithWrongPasswordIsRefusedConnection(vertx: Vertx, testContext: VertxTestContext) =
      runBlocking(vertx.dispatcher()) {
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

        try {
          client.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
          testContext.failNow("The client was able to connect with a wrong password")
        } catch (error: Throwable) {
          testContext.completeNow()
        }
      }

    @Test
    @DisplayName("A MQTT client receives updates")
    fun clientReceivesUpdate(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
      try {
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
        }.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()

        mqttClient.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
        vertx.eventBus().send(RELAYS_UPDATE_ADDRESS, message)
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

    @Test
    @DisplayName("A well-formed MQTT JSON message is ingested and streamed to Kafka")
    fun mqttMessageIsIngested(vertx: Vertx, testContext: VertxTestContext) = runBlocking(vertx.dispatcher()) {
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

      try {
        mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toChannel(vertx)
        for (record in stream) {
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
            stream.cancel()
          }
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

    @ExperimentalCoroutinesApi // for channel.isEmpty
    @Test
    @DisplayName("An invalid MQTT JSON message is not ingested because it is missing fields")
    fun invalidMqttMessageIsNotIngestedWrongFields(vertx: Vertx, testContext: VertxTestContext): Unit =
      runBlocking(vertx.dispatcher()) {
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

        try {
          mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
          mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
          kafkaConsumer.subscribe(INGESTION_TOPIC).await()
          val stream = kafkaConsumer.asStream().toChannel(vertx)
          testContext.verify {
            if (stream.isEmpty) {
              testContext.completeNow()
            } else {
              testContext.failNow("The message was ingested")
            }
          }
        } catch (error: Throwable) {
          testContext.failNow(error)
        }
      }

    @ExperimentalCoroutinesApi
    @Test
    @DisplayName("An invalid MQTT JSON message is not ingested because it is missing fields in the beacons field")
    fun invalidMqttMessageIsNotIngestedWrongBeaconsFields(vertx: Vertx, testContext: VertxTestContext): Unit =
      runBlocking(vertx.dispatcher()) {
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

        try {
          mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
          mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
          kafkaConsumer.subscribe(INGESTION_TOPIC).await()
          val stream = kafkaConsumer.asStream().toChannel(vertx)
          testContext.verify {
            if (stream.isEmpty) {
              testContext.completeNow()
            } else {
              testContext.failNow("The message was ingested")
            }
          }
        } catch (error: Throwable) {
          testContext.failNow(error)
        }
      }

    @ExperimentalCoroutinesApi
    @Test
    @DisplayName("An invalid MQTT JSON message is not ingested because it has zero coordinates")
    fun invalidMqttMessageIsNotIngestedWrongCoordinates(vertx: Vertx, testContext: VertxTestContext): Unit =
      runBlocking(vertx.dispatcher()) {
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

        try {
          mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
          mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
          kafkaConsumer.subscribe(INGESTION_TOPIC).await()
          val stream = kafkaConsumer.asStream().toChannel(vertx)
          testContext.verify {
            if (stream.isEmpty) {
              testContext.completeNow()
            } else {
              testContext.failNow("The message was ingested")
            }
          }
        } catch (error: Throwable) {
          testContext.failNow(error)
        }
      }

    @ExperimentalCoroutinesApi
    @Test
    @DisplayName("An invalid MQTT JSON message is not ingested because it has empty MACs")
    fun invalidMqttMessageIsNotIngestedEmptyMac(vertx: Vertx, testContext: VertxTestContext): Unit =
      runBlocking(vertx.dispatcher()) {
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

        try {
          mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
          mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
          kafkaConsumer.subscribe(INGESTION_TOPIC).await()
          val stream = kafkaConsumer.asStream().toChannel(vertx)
          testContext.verify {
            if (stream.isEmpty) {
              testContext.completeNow()
            } else {
              testContext.failNow("The message was ingested")
            }
          }
        } catch (error: Throwable) {
          testContext.failNow(error)
        }
      }

    @ExperimentalCoroutinesApi
    @Test
    @DisplayName("An invalid MQTT JSON message is not ingested because a beacon has a too large battery level")
    fun invalidMqttMessageIsNotIngestedInvalidBatteryTooLarge(vertx: Vertx, testContext: VertxTestContext): Unit =
      runBlocking(vertx.dispatcher()) {
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

        try {
          mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
          mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
          kafkaConsumer.subscribe(INGESTION_TOPIC).await()
          val stream = kafkaConsumer.asStream().toChannel(vertx)
          testContext.verify {
            if (stream.isEmpty) {
              testContext.completeNow()
            } else {
              testContext.failNow("The message was ingested")
            }
          }
        } catch (error: Throwable) {
          testContext.failNow(error)
        }
      }

    @ExperimentalCoroutinesApi
    @Test
    @DisplayName("An invalid MQTT JSON message is not ingested because a beacon has a too small battery level")
    fun invalidMqttMessageIsNotIngestedInvalidBatteryTooSmall(vertx: Vertx, testContext: VertxTestContext): Unit =
      runBlocking(vertx.dispatcher()) {
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

        try {
          mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
          mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
          kafkaConsumer.subscribe(INGESTION_TOPIC).await()
          val stream = kafkaConsumer.asStream().toChannel(vertx)
          testContext.verify {
            if (stream.isEmpty) {
              testContext.completeNow()
            } else {
              testContext.failNow("The message was ingested")
            }
          }
        } catch (error: Throwable) {
          testContext.failNow(error)
        }
      }

    @ExperimentalCoroutinesApi
    @Test
    @DisplayName("An invalid MQTT JSON message is not ingested because a beacon has an invalid status")
    fun invalidMqttMessageIsNotIngestedInvalidStatus(vertx: Vertx, testContext: VertxTestContext): Unit =
      runBlocking(vertx.dispatcher()) {
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

        try {
          mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
          mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
          kafkaConsumer.subscribe(INGESTION_TOPIC).await()
          val stream = kafkaConsumer.asStream().toChannel(vertx)
          testContext.verify {
            if (stream.isEmpty) {
              testContext.completeNow()
            } else {
              testContext.failNow("The message was ingested")
            }
          }
        } catch (error: Throwable) {
          testContext.failNow(error)
        }
      }


  @Test
  @DisplayName("A MQTT client for another company than biot gets the right last config at subscription")
  fun clientFromAnotherCompanyGetsRightConfig(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val client = MqttClient.create(
        vertx,
        mqttClientOptionsOf(
          clientId = configurationAnotherCompany["mqttID"],
          username = configurationAnotherCompany["mqttUsername"],
          password = configurationAnotherCompany["mqttPassword"],
          willFlag = true,
          willMessage = jsonObjectOf("company" to anotherCompanyName).encode()
        )
      )

      try {
        client.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        client.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            testContext.verify {
              val expected = configurationAnotherCompany.copy().apply {
                remove("mqttID")
                remove("mqttUsername")
                remove("ledStatus")
              }
              expectThat(msg.payload().toJsonObject()).isEqualTo(expected)
              testContext.completeNow()
            }
          }
        }.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
      } catch (error: Throwable) {
        testContext.completeNow()
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
