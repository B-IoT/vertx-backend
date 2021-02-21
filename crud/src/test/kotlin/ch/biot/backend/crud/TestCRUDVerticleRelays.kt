/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

package ch.biot.backend.crud

import io.restassured.builder.RequestSpecBuilder
import io.restassured.filter.log.RequestLoggingFilter
import io.restassured.filter.log.ResponseLoggingFilter
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.specification.RequestSpecification
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoAuthentication
import io.vertx.ext.auth.mongo.MongoUserUtil
import io.vertx.ext.mongo.MongoClient
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import io.vertx.kotlin.ext.mongo.indexOptionsOf
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isTrue
import java.io.File


@ExtendWith(VertxExtension::class)
@Testcontainers
class TestCRUDVerticleRelays {

  private lateinit var mongoClient: MongoClient
  private lateinit var mongoUserUtil: MongoUserUtil
  private lateinit var mongoAuth: MongoAuthentication

  private val mqttPassword = "password"
  private val existingRelay = jsonObjectOf(
    "mqttID" to "testRelay",
    "mqttUsername" to "testRelay",
    "relayID" to "testRelay",
    "ledStatus" to false,
    "latitude" to 0.1,
    "longitude" to 0.3,
    "floor" to 1,
    "wifi" to jsonObjectOf(
      "ssid" to "ssid",
      "password" to "pass"
    )
  )
  private val newRelay = jsonObjectOf(
    "mqttID" to "testRelay2",
    "mqttUsername" to "testRelay2",
    "relayID" to "testRelay2",
    "mqttPassword" to mqttPassword,
    "ledStatus" to false,
    "latitude" to 0.1,
    "longitude" to 0.3,
    "floor" to 1,
    "wifi" to jsonObjectOf(
      "ssid" to "ssid",
      "password" to "pass"
    )
  )

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext) {
    mongoClient =
      MongoClient.createShared(
        vertx,
        jsonObjectOf("host" to "localhost", "port" to CRUDVerticle.MONGO_PORT, "db_name" to "clients")
      )

    val usernameField = "mqttUsername"
    val passwordField = "mqttPassword"
    val mongoAuthOptions = mongoAuthenticationOptionsOf(
      collectionName = "relays",
      passwordCredentialField = passwordField,
      passwordField = passwordField,
      usernameCredentialField = usernameField,
      usernameField = usernameField
    )

    mongoUserUtil = MongoUserUtil.create(
      mongoClient, mongoAuthOptions, mongoAuthorizationOptionsOf()
    )
    mongoAuth = MongoAuthentication.create(mongoClient, mongoAuthOptions)

    mongoClient.createIndexWithOptions("relays", jsonObjectOf("relayID" to 1), indexOptionsOf().unique(true))
      .compose {
        mongoClient.createIndexWithOptions("relays", jsonObjectOf("mqttID" to 1), indexOptionsOf().unique(true))
      }.compose {
        mongoClient.createIndexWithOptions("relays", jsonObjectOf("mqttUsername" to 1), indexOptionsOf().unique(true))
      }.compose {
        dropAllRelays()
      }.compose {
        insertRelay()
      }.onSuccess {
        vertx.deployVerticle(CRUDVerticle(), testContext.succeedingThenComplete())
      }.onFailure(testContext::failNow)
  }

  private fun dropAllRelays() = mongoClient.removeDocuments("relays", jsonObjectOf())

  private fun insertRelay(): Future<JsonObject> {
    val hashedPassword = mqttPassword.saltAndHash(mongoAuth)
    return mongoUserUtil.createHashedUser("test", hashedPassword).compose { docID ->
      val query = jsonObjectOf("_id" to docID)
      val extraInfo = jsonObjectOf(
        "\$set" to existingRelay
      )
      mongoClient.findOneAndUpdate("relays", query, extraInfo)
    }
  }

  @AfterEach
  fun cleanup(testContext: VertxTestContext) {
    dropAllRelays().compose {
      mongoClient.close()
    }.onSuccess { testContext.completeNow() }
      .onFailure(testContext::failNow)
  }

  @Test
  @DisplayName("registerRelay correctly registers a new relay")
  fun registerIsCorrect(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(newRelay.encode())
    } When {
      post("/relays")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getRelays correctly retrieves all relays")
  fun getRelaysIsCorrect(testContext: VertxTestContext) {
    val expected = jsonArrayOf(existingRelay.copy().apply { remove("mqttPassword") })

    val response = Buffer.buffer(Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
    } When {
      get("/relays")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonArray()

    testContext.verify {
      val password = response.getJsonObject(0).remove("mqttPassword")
      expectThat(response).isEqualTo(expected)
      expectThat(password).isNotNull()
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getRelay correctly retrieves the desired relay")
  fun getRelayIsCorrect(testContext: VertxTestContext) {
    val expected = existingRelay.copy().apply { remove("mqttPassword") }

    val response = Buffer.buffer(Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
    } When {
      get("/relays/testRelay")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonObject()

    testContext.verify {
      val password = response.remove("mqttPassword")
      expectThat(response).isEqualTo(expected)
      expectThat(password).isNotNull()
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("updateRelay correctly updates the desired relay")
  fun updateRelayIsCorrect(vertx: Vertx, testContext: VertxTestContext) {
    val updateJson = jsonObjectOf(
      "ledStatus" to true,
      "latitude" to 1.0,
      "longitude" to -32.42332,
      "floor" to 2,
      "wifi" to jsonObjectOf(
        "ssid" to "test",
        "password" to "test"
      ),
      "beacon" to jsonObjectOf(
        "mac" to "macAddress",
        "txPower" to 5
      )
    )

    vertx.eventBus().consumer<JsonObject>("relays.update") { message ->
      val json = message.body()
      testContext.verify {
        expectThat(json).isNotNull()
        expect {
          that(json.getBoolean("ledStatus")).isEqualTo(updateJson.getBoolean("ledStatus"))
          that(json.getJsonObject("wifi")).isEqualTo(updateJson.getJsonObject("wifi"))
          that(json.getDouble("latitude")).isEqualTo(updateJson.getDouble("latitude"))
          that(json.getDouble("longitude")).isEqualTo(updateJson.getDouble("longitude"))
          that(json.getInteger("floor")).isEqualTo(updateJson.getInteger("floor"))
          that(json.getJsonObject("beacon")).isEqualTo(updateJson.getJsonObject("beacon"))
          that(json.getString("mqttID")).isEqualTo(existingRelay.getString("mqttID"))
          that(json.getString("relayID")).isEqualTo(existingRelay.getString("relayID"))
          that(json.containsKey("lastModified")).isTrue()
        }
        testContext.completeNow()
      }
    }

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(updateJson.encode())
    } When {
      put("/relays/testRelay")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
    }

    mongoClient.findOne("relays", jsonObjectOf("relayID" to "testRelay"), jsonObjectOf())
      .onSuccess { json ->
        expectThat(json).isNotNull()
        expect {
          that(json.getBoolean("ledStatus")).isEqualTo(updateJson.getBoolean("ledStatus"))
          that(json.getJsonObject("wifi")).isEqualTo(updateJson.getJsonObject("wifi"))
          that(json.getDouble("latitude")).isEqualTo(updateJson.getDouble("latitude"))
          that(json.getDouble("longitude")).isEqualTo(updateJson.getDouble("longitude"))
          that(json.getInteger("floor")).isEqualTo(updateJson.getInteger("floor"))
          that(json.getString("mqttID")).isEqualTo(existingRelay.getString("mqttID"))
          that(json.getString("relayID")).isEqualTo(existingRelay.getString("relayID"))
          that(json.containsKey("lastModified")).isTrue()
        }
        testContext.completeNow()
      }
      .onFailure(testContext::failNow)
  }

  @Test
  @DisplayName("deleteRelay correctly deletes a relay")
  fun deleteIsCorrect(testContext: VertxTestContext) {
    val relayToRemove = jsonObjectOf(
      "mqttID" to "testRelay42",
      "mqttUsername" to "testRelay42",
      "relayID" to "testRelay42",
      "mqttPassword" to mqttPassword,
      "ledStatus" to false,
      "latitude" to 0.1,
      "longitude" to 0.3,
      "floor" to 1,
      "wifi" to jsonObjectOf(
        "ssid" to "ssid",
        "password" to "pass"
      )
    )

    // Register the relay
    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(relayToRemove.encode())
    } When {
      post("/relays")
    } Then {
      statusCode(200)
    }

    // Delete the relay
    val response = Given {
      spec(requestSpecification)
    } When {
      delete("/relays/${relayToRemove.getString("relayID")}")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      testContext.completeNow()
    }
  }

  companion object {

    private val requestSpecification: RequestSpecification = RequestSpecBuilder()
      .addFilters(listOf(ResponseLoggingFilter(), RequestLoggingFilter()))
      .setBaseUri("http://localhost")
      .setPort(CRUDVerticle.HTTP_PORT)
      .build()

    private val instance: KDockerComposeContainer by lazy { defineDockerCompose() }

    class KDockerComposeContainer(file: File) : DockerComposeContainer<KDockerComposeContainer>(file)

    private fun defineDockerCompose() = KDockerComposeContainer(File("../docker-compose.yml")).withExposedService(
      "mongo_1",
      CRUDVerticle.MONGO_PORT
    )

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
