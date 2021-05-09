/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

package ch.biot.backend.publicapi

import ch.biot.backend.crud.CRUDVerticle
import io.restassured.builder.RequestSpecBuilder
import io.restassured.filter.log.RequestLoggingFilter
import io.restassured.filter.log.ResponseLoggingFilter
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.specification.RequestSpecification
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.*
import java.io.File

@ExtendWith(VertxExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
/**
 * Integration tests; the order matters.
 */
class TestPublicApiVerticle {

  private val user = jsonObjectOf(
    "userID" to "test2",
    "username" to "test2",
    "password" to "password",
    "company" to "biot"
  )

  private val relay = jsonObjectOf(
    "mqttID" to "testRelay2",
    "mqttUsername" to "testRelay2",
    "relayID" to "testRelay2",
    "mqttPassword" to "testRelay2",
    "ledStatus" to false,
    "latitude" to 0.1,
    "longitude" to 0.3,
    "floor" to 1,
    "wifi" to jsonObjectOf(
      "ssid" to "ssid",
      "password" to "pass"
    )
  )

  private val item = jsonObjectOf(
    "beacon" to "ab:ab:ab:ab:ab:ab",
    "category" to "ECG",
    "service" to "Bloc 2"
  )

  private var itemID: Int = 1

  private lateinit var token: String

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext) {
    vertx.deployVerticle(PublicApiVerticle())
      .compose {
        vertx.deployVerticle(CRUDVerticle())
      }
      .onSuccess {
        testContext.completeNow()
      }
      .onFailure(testContext::failNow)
  }

  @Test
  @Order(1)
  @DisplayName("Registering a user succeeds")
  fun registerUserSucceeds(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(user.encode())
    } When {
      post("/oauth/register")
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
  @Order(2)
  @DisplayName("Getting the token for a registered user succeeds")
  fun getTokenSucceeds(testContext: VertxTestContext) {
    val loginInfo = jsonObjectOf(
      "username" to user["username"],
      "password" to user["password"]
    )

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(loginInfo.encode())
    } When {
      post("/oauth/token")
    } Then {
      statusCode(200)
      contentType("application/jwt")
    } Extract {
      asString()
    }

    token = response

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response).isNotBlank()
      testContext.completeNow()
    }
  }

  @Test
  @Order(3)
  @DisplayName("Getting the token with wrong credentials fails")
  fun getTokenWithWrongCredentialsFails(testContext: VertxTestContext) {
    val loginInfo = jsonObjectOf(
      "username" to "wrongUsername",
      "password" to "wrongPassword"
    )

    testContext.verify {
      Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        body(loginInfo.encode())
      } When {
        post("/oauth/token")
      } Then {
        statusCode(401)
      }

      testContext.completeNow()
    }
  }

  @Test
  @Order(4)
  @DisplayName("Getting the users succeeds")
  fun getUsersSucceeds(testContext: VertxTestContext) {
    val expected = jsonArrayOf(user.copy().apply { remove("password") })

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/users")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonArray()

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response.isEmpty).isFalse()

      val password = response.getJsonObject(0).remove("password")
      expectThat(response).isEqualTo(expected)
      expectThat(password).isNotNull()
      testContext.completeNow()
    }
  }

  @Test
  @Order(5)
  @DisplayName("Getting a user succeeds")
  fun getUserSucceeds(testContext: VertxTestContext) {
    val expected = user.copy().apply { remove("password") }

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/users/${user.getString("userID")}")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      val password = response.remove("password")
      expectThat(response).isEqualTo(expected)
      expectThat(password).isNotNull()
      testContext.completeNow()
    }
  }

  @Test
  @Order(6)
  @DisplayName("Updating a user succeeds")
  fun updateUserSucceeds(testContext: VertxTestContext) {
    val updateJson = jsonObjectOf(
      "password" to "newPassword"
    )

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(updateJson.encode())
    } When {
      put("/api/users/${user.getString("userID")}")
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
  @Order(7)
  @DisplayName("Deleting a user succeeds")
  fun deleteUserSucceeds(testContext: VertxTestContext) {
    val userToRemove = jsonObjectOf(
      "userID" to "test42",
      "username" to "test42",
      "password" to "test42",
      "company" to "biot"
    )

    // Register the user
    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(userToRemove.encode())
    } When {
      post("/oauth/register")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    // Delete the user
    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
    } When {
      delete("/api/users/${userToRemove.getString("userID")}")
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
  @Order(8)
  @DisplayName("Registering a relay succeeds")
  fun registerRelaySucceeds(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(relay.encode())
    } When {
      post("/api/relays")
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
  @Order(9)
  @DisplayName("Getting the relays succeeds")
  fun getRelaysSucceeds(testContext: VertxTestContext) {
    val expected = jsonArrayOf(relay.copy().apply { remove("mqttPassword") })

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/relays")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonArray()

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response.isEmpty).isFalse()

      val password = response.getJsonObject(0).remove("mqttPassword")
      expectThat(response).isEqualTo(expected)
      expectThat(password).isNotNull()
      testContext.completeNow()
    }
  }

  @Test
  @Order(10)
  @DisplayName("Getting a relay succeeds")
  fun getRelaySucceeds(testContext: VertxTestContext) {
    val expected = relay.copy().apply { remove("mqttPassword") }

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/relays/${relay.getString("relayID")}")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      val password = response.remove("mqttPassword")
      expectThat(response).isEqualTo(expected)
      expectThat(password).isNotNull()
      testContext.completeNow()
    }
  }

  @Test
  @Order(11)
  @DisplayName("Updating a relay succeeds")
  fun updateRelaySucceeds(testContext: VertxTestContext) {
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

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(updateJson.encode())
    } When {
      put("/api/relays/${relay.getString("relayID")}")
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
  @Order(12)
  @DisplayName("Deleting a relay succeeds")
  fun deleteRelaySucceeds(testContext: VertxTestContext) {
    val relayToRemove = jsonObjectOf(
      "mqttID" to "testRelay42",
      "mqttUsername" to "testRelay42",
      "relayID" to "testRelay42",
      "mqttPassword" to "password",
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
      header("Authorization", "Bearer $token")
      body(relayToRemove.encode())
    } When {
      post("/api/relays")
    } Then {
      statusCode(200)
    }

    // Delete the relay
    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
    } When {
      delete("/api/relays/${relayToRemove.getString("relayID")}")
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
  @Order(13)
  @DisplayName("Registering an item succeeds")
  fun registerItemSucceeds(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(item.encode())
    } When {
      post("/api/items")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isNotEmpty() // it returns the id of the registered item
      itemID = response.toInt()
      testContext.completeNow()
    }
  }

  @Test
  @Order(14)
  @DisplayName("Getting the items succeeds")
  fun getItemsSucceeds(testContext: VertxTestContext) {
    val expected = item.copy()

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/items")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonArray()

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response.isEmpty).isFalse()

      val obj = response.getJsonObject(0)
      val id = obj.remove("id")
      expectThat(id).isEqualTo(itemID)
      expect {
        that(obj.getString("beacon")).isEqualTo(expected.getString("beacon"))
        that(obj.getString("category")).isEqualTo(expected.getString("category"))
        that(obj.getString("service")).isEqualTo(expected.getString("service"))
        that(obj.containsKey("timestamp")).isTrue()
        that(obj.containsKey("battery")).isTrue()
        that(obj.containsKey("status")).isTrue()
        that(obj.containsKey("latitude")).isTrue()
        that(obj.containsKey("longitude")).isTrue()
        that(obj.containsKey("floor")).isTrue()
      }

      testContext.completeNow()
    }
  }

  @Test
  @Order(15)
  @DisplayName("Getting the items (with query parameters) succeeds")
  fun getItemsWithQueryParametersSucceeds(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        queryParam("category", item.getString("category"))
        get("/api/items")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonArray()

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response.isEmpty).isFalse()

      val obj = response.getJsonObject(0)
      val id = obj.remove("id")
      expectThat(id).isEqualTo(itemID)
      expect {
        that(obj.getString("beacon")).isEqualTo(item.getString("beacon"))
        that(obj.getString("category")).isEqualTo(item.getString("category"))
        that(obj.getString("service")).isEqualTo(item.getString("service"))
        that(obj.containsKey("timestamp")).isTrue()
        that(obj.containsKey("battery")).isTrue()
        that(obj.containsKey("status")).isTrue()
        that(obj.containsKey("latitude")).isTrue()
        that(obj.containsKey("longitude")).isTrue()
        that(obj.containsKey("floor")).isTrue()
      }

      testContext.completeNow()
    }
  }

  @Test
  @Order(16)
  @DisplayName("Getting the closest items succeeds")
  fun getClosestItemsSucceeds(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        queryParam("latitude", 42)
        queryParam("longitude", -8)
        get("/api/items/closest")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response.isEmpty).isFalse()
      testContext.completeNow()
    }
  }

  @Test
  @Order(17)
  @DisplayName("Getting an item succeeds")
  fun getItemSucceeds(testContext: VertxTestContext) {
    val expected = item.copy()

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/items/$itemID")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      val id = response.remove("id")
      expectThat(id).isEqualTo(itemID)
      expect {
        that(response.getString("beacon")).isEqualTo(expected.getString("beacon"))
        that(response.getString("category")).isEqualTo(expected.getString("category"))
        that(response.getString("service")).isEqualTo(expected.getString("service"))
        that(response.containsKey("timestamp")).isTrue()
        that(response.containsKey("battery")).isTrue()
        that(response.containsKey("status")).isTrue()
        that(response.containsKey("latitude")).isTrue()
        that(response.containsKey("longitude")).isTrue()
        that(response.containsKey("floor")).isTrue()
      }
      testContext.completeNow()
    }
  }

  @Test
  @Order(18)
  @DisplayName("Getting the categories succeeds")
  fun getCategoriesSucceeds(testContext: VertxTestContext) {
    val expected = JsonArray(listOf(item.getString("category")))

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/items/categories")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonArray()

    testContext.verify {
      expectThat(response).isEqualTo(expected)
      testContext.completeNow()
    }
  }

  @Test
  @Order(19)
  @DisplayName("Updating an item succeeds")
  fun updateItemSucceeds(testContext: VertxTestContext) {
    val updateJson = jsonObjectOf(
      "beacon" to "ad:ab:ab:ab:ab:ab",
      "category" to "Lit",
      "service" to "Bloc 42"
    )

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(updateJson.encode())
    } When {
      put("/api/items/$itemID")
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
  @Order(20)
  @DisplayName("Deleting an item succeeds")
  fun deleteItemSucceeds(testContext: VertxTestContext) {
    val newItem = jsonObjectOf(
      "beacon" to "ab:cd:ef:aa:aa:aa",
      "category" to "Lit",
      "service" to "Bloc 42"
    )

    // Register the item
    val id = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(newItem.encode())
    } When {
      post("/api/items")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    // Delete the item
    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
    } When {
      delete("/api/items/$id")
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
  @Order(21)
  @DisplayName("Liveness check succeeds")
  fun livenessCheckSucceeds(testContext: VertxTestContext) {
    val expected = jsonObjectOf("status" to "UP")

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
      } When {
        get("/health/live")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response).isEqualTo(expected)
      testContext.completeNow()
    }
  }

  @Test
  @Order(22)
  @DisplayName("Readiness check succeeds")
  fun readinessCheckSucceeds(testContext: VertxTestContext) {
    val expected = jsonObjectOf("status" to "UP")

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
      } When {
        get("/health/ready")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response).isEqualTo(expected)
      testContext.completeNow()
    }
  }

  @Test
  @Order(23)
  @DisplayName("Getting the item's status succeeds (analytics)")
  fun getStatusSucceeds(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/analytics/status")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response.isEmpty).isFalse()
      testContext.completeNow()
    }
  }

  companion object {

    private val requestSpecification: RequestSpecification = RequestSpecBuilder()
      .addFilters(listOf(ResponseLoggingFilter(), RequestLoggingFilter()))
      .setBaseUri("http://localhost")
      .setPort(PublicApiVerticle.PUBLIC_PORT)
      .build()

    private val instance: KDockerComposeContainer by lazy { defineDockerCompose() }

    class KDockerComposeContainer(file: File) : DockerComposeContainer<KDockerComposeContainer>(file)

    private fun defineDockerCompose() = KDockerComposeContainer(File("../docker-compose.yml")).withExposedService(
      "mongo_1",
      27017
    ).withExposedService(
      "timescale_1",
      5432
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
