package ch.biot.backend.updateparameters

import io.netty.handler.codec.mqtt.MqttQoS
import io.reactivex.Maybe
import io.reactivex.rxkotlin.subscribeBy
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import io.vertx.kotlin.ext.mongo.indexOptionsOf
import io.vertx.kotlin.mqtt.mqttClientOptionsOf
import io.vertx.reactivex.core.Vertx
import io.vertx.reactivex.ext.auth.mongo.MongoAuthentication
import io.vertx.reactivex.ext.auth.mongo.MongoUserUtil
import io.vertx.reactivex.ext.mongo.MongoClient
import io.vertx.reactivex.mqtt.MqttClient
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.io.File
import java.security.SecureRandom
import java.util.*


@ExtendWith(VertxExtension::class)
@Testcontainers
class TestUpdateParametersVerticle {

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
    mqttClient = MqttClient.create(
      vertx, mqttClientOptionsOf(
        clientId = configuration["mqttID"],
        username = configuration["mqttUsername"],
        password = mqttPassword
      )
    )

    mongoClient =
      MongoClient.createShared(vertx, jsonObjectOf("host" to "localhost", "port" to 27017, "db_name" to "clients"))

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

    mongoClient.rxCreateIndexWithOptions("relays", jsonObjectOf("relayID" to 1), indexOptionsOf().unique(true))
      .andThen(
        mongoClient.rxCreateIndexWithOptions("relays", jsonObjectOf("mqttID" to 1), indexOptionsOf().unique(true))
      )
      .andThen(
        mongoClient.rxCreateIndexWithOptions(
          "relays", jsonObjectOf("mqttUsername" to 1), indexOptionsOf().unique(
            true
          )
        )
      )
      .andThen(dropAllRelays())
      .flatMap { insertRelay() }
      .flatMapSingle { vertx.rxDeployVerticle(UpdateParametersVerticle()) }
      .subscribe({ testContext.completeNow() }, testContext::failNow)
  }

  private fun dropAllRelays() = mongoClient.rxRemoveDocuments("relays", jsonObjectOf())

  private fun insertRelay(): Maybe<JsonObject> {
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)
    val hashedPassword = mongoAuth.hash("pbkdf2", String(Base64.getEncoder().encode(salt)), mqttPassword)
    return mongoUserUtil.rxCreateHashedUser("test", hashedPassword).flatMapMaybe { docID ->
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
    mqttClient.rxConnect(8883, "localhost")
      .flatMap {
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == "last.configuration") {
            testContext.verify {
              val expected = configuration.copy().apply {
                remove("mqttID")
                remove("mqttUsername")
                remove("latitude")
                remove("longitude")
              }
              expectThat(msg.payload().toJsonObject()).isEqualTo(expected)
              testContext.completeNow()
            }
          }
        }.rxSubscribe("last.configuration", MqttQoS.AT_LEAST_ONCE.value())
      }.subscribeBy(
        onError = testContext::failNow
      )
  }

  @Test
  @DisplayName("A MQTT client receives updates")
  fun clientReceivesUpdate(vertx: Vertx, testContext: VertxTestContext) {
    val message = jsonObjectOf("ledStatus" to true, "mqttID" to "mqtt")
    mqttClient.rxConnect(8883, "localhost")
      .flatMap {
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == "update.parameters") {
            testContext.verify {
              val messageWithoutMqttID = message.copy().apply { remove("mqttID") }
              expectThat(msg.payload().toJsonObject()).isEqualTo(messageWithoutMqttID)
              testContext.completeNow()
            }
          }
        }.rxSubscribe("update.parameters", MqttQoS.AT_LEAST_ONCE.value())
      }.subscribeBy(
        onSuccess = {
          vertx.eventBus().send("relays.update", message)
        },
        onError = testContext::failNow
      )
  }

  companion object {

    private const val RELAYS_COLLECTION = "relays"

    private val instance: KDockerComposeContainer by lazy { defineDockerCompose() }

    class KDockerComposeContainer(file: File) : DockerComposeContainer<KDockerComposeContainer>(file)

    private fun defineDockerCompose() =
      KDockerComposeContainer(File("../docker-compose.yml")).withExposedService("mongo_1", 27017)

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
