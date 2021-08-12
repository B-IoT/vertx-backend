/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.publicapi

import ch.biot.backend.crud.CRUDVerticle
import ch.biot.backend.crud.CRUDVerticle.Companion.INITIAL_USER
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
import io.vertx.eventbusclient.EventBusClient
import io.vertx.eventbusclient.EventBusClientOptions
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.*
import java.io.File
import java.time.LocalDate

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
    "company" to "biot",
    "accessControlString" to "biot"
  )

  private val user2 = jsonObjectOf(
    "userID" to "test22",
    "username" to "test22",
    "password" to "password2",
    "company" to "biot",
    "accessControlString" to "biot:grp1"
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

  private val item1 = jsonObjectOf(
    "beacon" to "ab:ab:ab:ab:ab:ab",
    "category" to "ECG",
    "service" to "Bloc 2",
    "itemID" to "abc",
    "accessControlString" to "biot",
    "brand" to "ferrari",
    "model" to "GT",
    "supplier" to "sup",
    "purchaseDate" to LocalDate.of(2021, 7, 8).toString(),
    "purchasePrice" to 42.3,
    "originLocation" to "center1",
    "currentLocation" to "center2",
    "room" to "616",
    "contact" to "Monsieur Poirot",
    "currentOwner" to "Monsieur Dupont",
    "previousOwner" to "Monsieur Dupond",
    "orderNumber" to "abcdf",
    "color" to "red",
    "serialNumber" to "abcdf",
    "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
    "status" to "In maintenance",
    "comments" to "A comment",
    "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
    "lastModifiedBy" to "Monsieur Duport"
  )

  private val item2Grp1 = jsonObjectOf(
    "beacon" to "ff:ff:ff:ff:ff:ff",
    "category" to "Lit",
    "service" to "Cardio",
    "itemID" to "cde",
    "accessControlString" to "biot:grp1",
    "brand" to "ferrari",
    "model" to "GT",
    "supplier" to "sup",
    "purchaseDate" to LocalDate.of(2021, 7, 8).toString(),
    "purchasePrice" to 42.3,
    "originLocation" to "center1",
    "currentLocation" to "center2",
    "room" to "616",
    "contact" to "Monsieur Poirot",
    "currentOwner" to "Monsieur Dupont",
    "previousOwner" to "Monsieur Dupond",
    "orderNumber" to "abcdf",
    "color" to "red",
    "serialNumber" to "abcdf",
    "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
    "status" to "In maintenance",
    "comments" to "A comment",
    "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
    "lastModifiedBy" to "Monsieur Duport"
  )

  private val item3 = jsonObjectOf(
    "beacon" to "ff:ff:zz:zz:ff:ff",
    "category" to "EEG",
    "service" to "Neuro",
    "itemID" to "hgfds",
    "accessControlString" to "biot:grp1:grp3",
    "brand" to "htre",
    "model" to "dfg",
    "supplier" to "gfds",
    "purchaseDate" to LocalDate.of(2021, 7, 8).toString(),
    "purchasePrice" to 42.3,
    "originLocation" to "center154",
    "currentLocation" to "center254",
    "room" to "616",
    "contact" to "Monsieur Poirot",
    "currentOwner" to "Monsieur Dupont",
    "previousOwner" to "Monsieur Dupond",
    "orderNumber" to "abcdf",
    "color" to "red",
    "serialNumber" to "abcdf",
    "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
    "status" to "In maintenance",
    "comments" to "A comment",
    "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
    "lastModifiedBy" to "Monsieur Duport"
  )

  private var item2IDGrp1 = -1
  private var itemID: Int = -1

  private lateinit var token: String
  private lateinit var tokenGrp1: String

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    try {
      vertx.deployVerticle(PublicApiVerticle()).await()
      vertx.deployVerticle(CRUDVerticle()).await()
      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  @Order(1)
  @DisplayName("Getting the token for the initial user succeeds")
  fun getTokenSucceeds(testContext: VertxTestContext) {
    val loginInfo = jsonObjectOf(
      "username" to INITIAL_USER["username"],
      "password" to INITIAL_USER["password"]
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
  @Order(2)
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
  @Order(3)
  @DisplayName("Registering a user succeeds")
  fun registerUserSucceeds(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
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
  @Order(4)
  @DisplayName("Getting the users succeeds")
  fun getUsersSucceeds(testContext: VertxTestContext) {
    val expected =
      jsonArrayOf(INITIAL_USER.copy().apply { remove("password") }, user.copy().apply { remove("password") })

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

      val password1 = response.getJsonObject(0).remove("password")
      val password2 = response.getJsonObject(1).remove("password")
      expectThat(response).isEqualTo(expected)

      expectThat(password1).isNotNull()
      expectThat(password2).isNotNull()
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
      "company" to "biot",
      "accessControlString" to "biot:grp"
    )

    // Register the user
    Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
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
      body(item1.encode())
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
    val expected = item1.copy()

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
        that(obj.getString("itemID")).isEqualTo(expected.getString("itemID"))
        that(obj.getString("accessControlString")).isEqualTo(expected.getString("accessControlString"))
        that(obj.getString("brand")).isEqualTo(expected.getString("brand"))
        that(obj.getString("model")).isEqualTo(expected.getString("model"))
        that(obj.getString("supplier")).isEqualTo(expected.getString("supplier"))
        that(obj.getString("purchaseDate")).isEqualTo(expected.getString("purchaseDate"))
        that(obj.getDouble("purchasePrice")).isEqualTo(expected.getDouble("purchasePrice"))
        that(obj.getString("originLocation")).isEqualTo(expected.getString("originLocation"))
        that(obj.getString("currentLocation")).isEqualTo(expected.getString("currentLocation"))
        that(obj.getString("room")).isEqualTo(expected.getString("room"))
        that(obj.getString("contact")).isEqualTo(expected.getString("contact"))
        that(obj.getString("currentOwner")).isEqualTo(expected.getString("currentOwner"))
        that(obj.getString("previousOwner")).isEqualTo(expected.getString("previousOwner"))
        that(obj.getString("orderNumber")).isEqualTo(expected.getString("orderNumber"))
        that(obj.getString("color")).isEqualTo(expected.getString("color"))
        that(obj.getString("serialNumber")).isEqualTo(expected.getString("serialNumber"))
        that(obj.getString("maintenanceDate")).isEqualTo(expected.getString("maintenanceDate"))
        that(obj.getString("status")).isEqualTo(expected.getString("status"))
        that(obj.getString("comments")).isEqualTo(expected.getString("comments"))
        that(obj.getString("lastModifiedDate")).isEqualTo(expected.getString("lastModifiedDate"))
        that(obj.getString("lastModifiedBy")).isEqualTo(expected.getString("lastModifiedBy"))
        that(obj.containsKey("timestamp")).isTrue()
        that(obj.containsKey("battery")).isTrue()
        that(obj.containsKey("beaconStatus")).isTrue()
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
        queryParam("category", item1.getString("category"))
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
        that(obj.getString("beacon")).isEqualTo(item1.getString("beacon"))
        that(obj.getString("category")).isEqualTo(item1.getString("category"))
        that(obj.getString("service")).isEqualTo(item1.getString("service"))
        that(obj.getString("itemID")).isEqualTo(item1.getString("itemID"))
        that(obj.getString("accessControlString")).isEqualTo(item1.getString("accessControlString"))
        that(obj.getString("brand")).isEqualTo(item1.getString("brand"))
        that(obj.getString("model")).isEqualTo(item1.getString("model"))
        that(obj.getString("supplier")).isEqualTo(item1.getString("supplier"))
        that(obj.getString("purchaseDate")).isEqualTo(item1.getString("purchaseDate"))
        that(obj.getDouble("purchasePrice")).isEqualTo(item1.getDouble("purchasePrice"))
        that(obj.getString("originLocation")).isEqualTo(item1.getString("originLocation"))
        that(obj.getString("currentLocation")).isEqualTo(item1.getString("currentLocation"))
        that(obj.getString("room")).isEqualTo(item1.getString("room"))
        that(obj.getString("contact")).isEqualTo(item1.getString("contact"))
        that(obj.getString("currentOwner")).isEqualTo(item1.getString("currentOwner"))
        that(obj.getString("previousOwner")).isEqualTo(item1.getString("previousOwner"))
        that(obj.getString("orderNumber")).isEqualTo(item1.getString("orderNumber"))
        that(obj.getString("color")).isEqualTo(item1.getString("color"))
        that(obj.getString("serialNumber")).isEqualTo(item1.getString("serialNumber"))
        that(obj.getString("maintenanceDate")).isEqualTo(item1.getString("maintenanceDate"))
        that(obj.getString("status")).isEqualTo(item1.getString("status"))
        that(obj.getString("comments")).isEqualTo(item1.getString("comments"))
        that(obj.getString("lastModifiedDate")).isEqualTo(item1.getString("lastModifiedDate"))
        that(obj.getString("lastModifiedBy")).isEqualTo(item1.getString("lastModifiedBy"))
        that(obj.containsKey("timestamp")).isTrue()
        that(obj.containsKey("battery")).isTrue()
        that(obj.containsKey("beaconStatus")).isTrue()
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
    val expected = item1.copy()

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
        that(response.getString("itemID")).isEqualTo(expected.getString("itemID"))
        that(response.getString("accessControlString")).isEqualTo(expected.getString("accessControlString"))
        that(response.getString("brand")).isEqualTo(expected.getString("brand"))
        that(response.getString("model")).isEqualTo(expected.getString("model"))
        that(response.getString("supplier")).isEqualTo(expected.getString("supplier"))
        that(response.getString("purchaseDate")).isEqualTo(expected.getString("purchaseDate"))
        that(response.getDouble("purchasePrice")).isEqualTo(expected.getDouble("purchasePrice"))
        that(response.getString("originLocation")).isEqualTo(expected.getString("originLocation"))
        that(response.getString("currentLocation")).isEqualTo(expected.getString("currentLocation"))
        that(response.getString("room")).isEqualTo(expected.getString("room"))
        that(response.getString("contact")).isEqualTo(expected.getString("contact"))
        that(response.getString("currentOwner")).isEqualTo(expected.getString("currentOwner"))
        that(response.getString("previousOwner")).isEqualTo(expected.getString("previousOwner"))
        that(response.getString("orderNumber")).isEqualTo(expected.getString("orderNumber"))
        that(response.getString("color")).isEqualTo(expected.getString("color"))
        that(response.getString("serialNumber")).isEqualTo(expected.getString("serialNumber"))
        that(response.getString("maintenanceDate")).isEqualTo(expected.getString("maintenanceDate"))
        that(response.getString("status")).isEqualTo(expected.getString("status"))
        that(response.getString("comments")).isEqualTo(expected.getString("comments"))
        that(response.getString("lastModifiedDate")).isEqualTo(expected.getString("lastModifiedDate"))
        that(response.getString("lastModifiedBy")).isEqualTo(expected.getString("lastModifiedBy"))
        that(response.containsKey("timestamp")).isTrue()
        that(response.containsKey("battery")).isTrue()
        that(response.containsKey("beaconStatus")).isTrue()
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
    val expected = JsonArray(listOf(item1.getString("category")))

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
      "service" to "Bloc 42",
      "itemID" to "sdsddsd",
      "brand" to "maserati",
      "model" to "wdwd",
      "supplier" to "supplier",
      "purchaseDate" to LocalDate.of(2020, 11, 8).toString(),
      "purchasePrice" to 1000.3,
      "originLocation" to "center6",
      "currentLocation" to "center10",
      "room" to "2",
      "contact" to "Monsieur Poire",
      "currentOwner" to "Monsieur Dupe",
      "previousOwner" to "Monsieur Pistache",
      "orderNumber" to "asasas",
      "color" to "blue",
      "serialNumber" to "aasasasa",
      "maintenanceDate" to LocalDate.of(2022, 12, 25).toString(),
      "status" to "Disponible",
      "comments" to "A comment",
      "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
      "lastModifiedBy" to "Monsieur Duport"
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
  @DisplayName("Updating an item (with query parameters) succeeds")
  fun updateItemWithQueryParametersSucceeds(testContext: VertxTestContext) {
    val updateJson = jsonObjectOf(
      "beacon" to "ad:ab:ab:ab:ab:ab",
      "category" to "Lit",
      "service" to "Bloc 42",
      "itemID" to "sdsddsd",
      "brand" to "maserati",
      "model" to "wdwd",
      "supplier" to "supplier",
      "purchaseDate" to LocalDate.of(2020, 11, 8).toString(),
      "purchasePrice" to 1000.3,
      "originLocation" to "center6",
      "currentLocation" to "center10",
      "room" to "2",
      "contact" to "Monsieur Poire",
      "currentOwner" to "Monsieur Dupe",
      "previousOwner" to "Monsieur Pistache",
      "orderNumber" to "asasas",
      "color" to "blue",
      "serialNumber" to "aasasasa",
      "maintenanceDate" to LocalDate.of(2022, 12, 25).toString(),
      "status" to "Disponible",
      "comments" to "A comment",
      "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
      "lastModifiedBy" to "Monsieur Duport"
    )

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(updateJson.encode())
    } When {
      queryParam("scan", true)
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
  @Order(21)
  @DisplayName("Deleting an item succeeds")
  fun deleteItemSucceeds(testContext: VertxTestContext) {
    val newItem = jsonObjectOf(
      "beacon" to "ab:cd:ef:aa:aa:aa",
      "category" to "Lit",
      "service" to "Bloc 42",
      "itemID" to "abc",
      "brand" to "ferrari",
      "model" to "GT",
      "supplier" to "sup",
      "purchaseDate" to LocalDate.of(2021, 7, 8).toString(),
      "purchasePrice" to 42.3,
      "originLocation" to "center1",
      "currentLocation" to "center2",
      "room" to "616",
      "contact" to "Monsieur Poirot",
      "currentOwner" to "Monsieur Dupont",
      "previousOwner" to "Monsieur Dupond",
      "orderNumber" to "abcdf",
      "color" to "red",
      "serialNumber" to "abcdf",
      "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
      "status" to "In maintenance",
      "comments" to "A comment",
      "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
      "lastModifiedBy" to "Monsieur Duport"
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
  @Order(22)
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
  @Order(23)
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
  @Order(24)
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

  @Test
  @Order(25)
  @DisplayName("Errors are handled in getOneHandler")
  fun errorIsHandledGetOneRequest(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
    } When {
      get("/api/items/100")
    } Then {
      statusCode(404)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      testContext.completeNow()
    }
  }

  @Test
  @Order(26)
  @DisplayName("Errors are handled in updateHandler")
  fun errorIsHandledUpdateRequest(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body("A body")
    } When {
      put("/api/items/100")
    } Then {
      statusCode(502)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Bad Gateway")
      testContext.completeNow()
    }
  }

  @Test
  @Order(27)
  @DisplayName("Errors are handled in registerHandler")
  fun errorIsHandledRegisterRequest(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body("A body")
    } When {
      post("/api/items")
    } Then {
      statusCode(502)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Bad Gateway")
      testContext.completeNow()
    }
  }

  @Test
  @Order(28)
  @DisplayName("Getting the user information succeeds")
  fun getUserInfoIsCorrect(testContext: VertxTestContext) {
    val expected = jsonObjectOf(
      "company" to "biot"
    )

    val response = Buffer.buffer(Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
    } When {
      get("/api/users/me")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonObject()

    testContext.verify {
      expectThat(response).isEqualTo(expected)
      testContext.completeNow()
    }
  }

  @Test
  @Order(29)
  @DisplayName("The event bus bridge endpoint is available")
  fun eventBusBridgeIsAvailable(vertx: Vertx, testContext: VertxTestContext) {
    val options = EventBusClientOptions()
      .setHost("localhost").setPort(PublicApiVerticle.PUBLIC_PORT)
      .setWebSocketPath("/eventbus/websocket?token=$token") // websocket needed otherwise the client doesn't work
    val webSocketEventBusClient = EventBusClient.webSocket(options)

    webSocketEventBusClient.connectedHandler {
      testContext.verify {
        expectThat(webSocketEventBusClient.isConnected).isTrue()
        testContext.completeNow()
      }
    }.connect()
  }

  @Test
  @Order(30)
  @DisplayName("Creating a snapshot succeeds")
  fun createSnapshotSucceeds(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
    } When {
      post("/api/items/snapshots")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isNotEmpty()
      testContext.completeNow()
    }
  }

  @Test
  @Order(31)
  @DisplayName("Getting the list of snapshots succeeds")
  fun getSnapshotsSucceeds(testContext: VertxTestContext) {
    val snapshots = Buffer.buffer(Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
    } When {
      get("/api/items/snapshots")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonArray()

    testContext.verify {
      expectThat(snapshots.size()).isGreaterThan(0)
      testContext.completeNow()
    }
  }

  @Test
  @Order(32)
  @DisplayName("Getting a snapshot succeeds")
  fun getSnapshotSucceeds(testContext: VertxTestContext) {
    val snapshotID = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
    } When {
      post("/api/items/snapshots")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    val items = Buffer.buffer(Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
    } When {
      get("/api/items/snapshots/$snapshotID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonArray()

    testContext.verify {
      expectThat(items.size()).isGreaterThan(0)
      testContext.completeNow()
    }
  }

  @Test
  @Order(33)
  @DisplayName("Deleting a snapshot succeeds")
  fun deleteSnapshotSucceeds(testContext: VertxTestContext) {
    val snapshotID = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
    } When {
      post("/api/items/snapshots")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
    } When {
      delete("/api/items/snapshots/$snapshotID")
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
  @Order(34)
  @DisplayName("Comparing two snapshots succeeds")
  fun compareSnapshotsSucceeds(testContext: VertxTestContext) {
    val firstSnapshotId = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
    } When {
      post("/api/items/snapshots")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    val secondSnapshotId = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
    } When {
      post("/api/items/snapshots")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    val comparisonObject = Buffer.buffer(Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
    } When {
      queryParam("firstSnapshotId", firstSnapshotId)
      queryParam("secondSnapshotId", secondSnapshotId)
      get("/api/items/snapshots/compare")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonObject()

    testContext.verify {
      expectThat(comparisonObject.isEmpty).isFalse()
      testContext.completeNow()
    }
  }

  @Test
  @Order(35)
  @DisplayName("Registering a new user succeeds (used later)")
  fun registerUserSucceeds2(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
      body(user2.encode())
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
  @Order(36)
  @DisplayName("Getting the token for the newly added user succeeds 2")
  fun getTokenSucceeds2(testContext: VertxTestContext) {
    val loginInfo = jsonObjectOf(
      "username" to user2.getString("username"),
      "password" to user2.getString("password")
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

    tokenGrp1 = response

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response).isNotBlank()
      testContext.completeNow()
    }
  }

  @Test
  @Order(37)
  @DisplayName("Registering a second item succeeds with a user with ac string = biot:grp1")
  fun registerItemSucceeds2(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      header("Authorization", "Bearer $tokenGrp1")
      body(item2Grp1.encode())
    } When {
      post("/api/items")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isNotEmpty() // it returns the id of the registered item
      item2IDGrp1 = response.toInt()
      testContext.completeNow()
    }
  }

  @Test
  @Order(38)
  @DisplayName("Getting an item succeeds with the right ac string")
  fun getItemSucceedsWithACString(testContext: VertxTestContext) {
    val expected = item2Grp1.copy()

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $tokenGrp1")
      } When {
        get("/api/items/$item2IDGrp1")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      val id = response.remove("id")
      expectThat(id).isEqualTo(item2IDGrp1)
      expect {
        that(response.getString("beacon")).isEqualTo(expected.getString("beacon"))
        that(response.getString("category")).isEqualTo(expected.getString("category"))
        that(response.getString("service")).isEqualTo(expected.getString("service"))
        that(response.getString("itemID")).isEqualTo(expected.getString("itemID"))
        that(response.getString("accessControlString")).isEqualTo(user2.getString("accessControlString"))
        that(response.getString("brand")).isEqualTo(expected.getString("brand"))
        that(response.getString("model")).isEqualTo(expected.getString("model"))
        that(response.getString("supplier")).isEqualTo(expected.getString("supplier"))
        that(response.getString("purchaseDate")).isEqualTo(expected.getString("purchaseDate"))
        that(response.getDouble("purchasePrice")).isEqualTo(expected.getDouble("purchasePrice"))
        that(response.getString("originLocation")).isEqualTo(expected.getString("originLocation"))
        that(response.getString("currentLocation")).isEqualTo(expected.getString("currentLocation"))
        that(response.getString("room")).isEqualTo(expected.getString("room"))
        that(response.getString("contact")).isEqualTo(expected.getString("contact"))
        that(response.getString("currentOwner")).isEqualTo(expected.getString("currentOwner"))
        that(response.getString("previousOwner")).isEqualTo(expected.getString("previousOwner"))
        that(response.getString("orderNumber")).isEqualTo(expected.getString("orderNumber"))
        that(response.getString("color")).isEqualTo(expected.getString("color"))
        that(response.getString("serialNumber")).isEqualTo(expected.getString("serialNumber"))
        that(response.getString("maintenanceDate")).isEqualTo(expected.getString("maintenanceDate"))
        that(response.getString("status")).isEqualTo(expected.getString("status"))
        that(response.getString("comments")).isEqualTo(expected.getString("comments"))
        that(response.getString("lastModifiedDate")).isEqualTo(expected.getString("lastModifiedDate"))
        that(response.getString("lastModifiedBy")).isEqualTo(expected.getString("lastModifiedBy"))
        that(response.containsKey("timestamp")).isTrue()
        that(response.containsKey("battery")).isTrue()
        that(response.containsKey("beaconStatus")).isTrue()
        that(response.containsKey("latitude")).isTrue()
        that(response.containsKey("longitude")).isTrue()
        that(response.containsKey("floor")).isTrue()
      }
      testContext.completeNow()
    }
  }

  @Test
  @Order(39)
  @DisplayName("Getting an item fails (404 as if the item does not exist) with an insufficient ac string")
  fun getItemFailsWithWrongACString(testContext: VertxTestContext) {
    val response =
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $tokenGrp1")
      } When {
        get("/api/items/$itemID")
      } Then {
        statusCode(404)
      } Extract {
        asString()
      }

    testContext.verify {
      expectThat(response).isNotNull()
      testContext.completeNow()
    }
  }

  @Test
  @Order(40)
  @DisplayName("Updating an item fails with insufficient ac string and does not modify the item")
  fun updateItemFailsWithWrongACString(testContext: VertxTestContext) {

    val expected = Buffer.buffer(
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

    val updateJson = jsonObjectOf(
      "beacon" to "biot_fake_beacon",
      "category" to "Lit",
      "service" to "Chirurgie",
      "itemID" to "fdasfdsa",
      "accessControlString" to "biot:grp1",
      "brand" to "fdasfgt",
      "model" to "fdgsopo3",
      "supplier" to "sup",
      "purchaseDate" to LocalDate.of(2021, 7, 8).toString(),
      "purchasePrice" to 42.3,
      "originLocation" to "ni3ofn",
      "currentLocation" to "kfdsao",
      "room" to "616",
      "contact" to "Monsieur Poirot",
      "currentOwner" to "Monsieur Dupont",
      "previousOwner" to "Monsieur Dupond",
      "orderNumber" to "abcdf",
      "color" to "red",
      "serialNumber" to "abcdf",
      "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
      "status" to "In maintenance",
      "comments" to "A comment",
      "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
      "lastModifiedBy" to "Monsieur Duport"
    )

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $tokenGrp1")
      body(updateJson.encode())
    } When {
      put("/api/items/$itemID")
    } Then {
      statusCode(502)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isNotNull()
    }

    val response2 = Buffer.buffer(
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
      expectThat(response2).isNotNull()
      val id = response2.remove("id")
      expectThat(id).isEqualTo(itemID)
      expect {
        that(response2.getString("beacon")).isEqualTo(expected.getString("beacon"))
        that(response2.getString("category")).isEqualTo(expected.getString("category"))
        that(response2.getString("service")).isEqualTo(expected.getString("service"))
        that(response2.getString("itemID")).isEqualTo(expected.getString("itemID"))
        that(response2.getString("accessControlString")).isEqualTo(expected.getString("accessControlString"))
        that(response2.getString("brand")).isEqualTo(expected.getString("brand"))
        that(response2.getString("model")).isEqualTo(expected.getString("model"))
        that(response2.getString("supplier")).isEqualTo(expected.getString("supplier"))
        that(response2.getString("purchaseDate")).isEqualTo(expected.getString("purchaseDate"))
        that(response2.getDouble("purchasePrice")).isEqualTo(expected.getDouble("purchasePrice"))
        that(response2.getString("originLocation")).isEqualTo(expected.getString("originLocation"))
        that(response2.getString("currentLocation")).isEqualTo(expected.getString("currentLocation"))
        that(response2.getString("room")).isEqualTo(expected.getString("room"))
        that(response2.getString("contact")).isEqualTo(expected.getString("contact"))
        that(response2.getString("currentOwner")).isEqualTo(expected.getString("currentOwner"))
        that(response2.getString("previousOwner")).isEqualTo(expected.getString("previousOwner"))
        that(response2.getString("orderNumber")).isEqualTo(expected.getString("orderNumber"))
        that(response2.getString("color")).isEqualTo(expected.getString("color"))
        that(response2.getString("serialNumber")).isEqualTo(expected.getString("serialNumber"))
        that(response2.getString("maintenanceDate")).isEqualTo(expected.getString("maintenanceDate"))
        that(response2.getString("status")).isEqualTo(expected.getString("status"))
        that(response2.getString("comments")).isEqualTo(expected.getString("comments"))
        that(response2.getString("lastModifiedDate")).isEqualTo(expected.getString("lastModifiedDate"))
        that(response2.getString("lastModifiedBy")).isEqualTo(expected.getString("lastModifiedBy"))
        that(response2.containsKey("timestamp")).isTrue()
        that(response2.containsKey("battery")).isTrue()
        that(response2.containsKey("beaconStatus")).isTrue()
        that(response2.containsKey("latitude")).isTrue()
        that(response2.containsKey("longitude")).isTrue()
        that(response2.containsKey("floor")).isTrue()
      }
      testContext.completeNow()
    }
  }

  @Test
  @Order(41)
  @DisplayName("Deleting an item fails with insufficient ac string (it completes but does not delete the item)")
  fun deleteItemFailsWithWrongACString(testContext: VertxTestContext) {

    val expected = Buffer.buffer(
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

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $tokenGrp1")
    } When {
      delete("/api/items/$itemID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isNotNull()
    }

    val response2 = Buffer.buffer(
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
      expectThat(response2).isNotNull()
      val id = response2.remove("id")
      expectThat(id).isEqualTo(itemID)
      expect {
        that(response2.getString("beacon")).isEqualTo(expected.getString("beacon"))
        that(response2.getString("category")).isEqualTo(expected.getString("category"))
        that(response2.getString("service")).isEqualTo(expected.getString("service"))
        that(response2.getString("itemID")).isEqualTo(expected.getString("itemID"))
        that(response2.getString("accessControlString")).isEqualTo(expected.getString("accessControlString"))
        that(response2.getString("brand")).isEqualTo(expected.getString("brand"))
        that(response2.getString("model")).isEqualTo(expected.getString("model"))
        that(response2.getString("supplier")).isEqualTo(expected.getString("supplier"))
        that(response2.getString("purchaseDate")).isEqualTo(expected.getString("purchaseDate"))
        that(response2.getDouble("purchasePrice")).isEqualTo(expected.getDouble("purchasePrice"))
        that(response2.getString("originLocation")).isEqualTo(expected.getString("originLocation"))
        that(response2.getString("currentLocation")).isEqualTo(expected.getString("currentLocation"))
        that(response2.getString("room")).isEqualTo(expected.getString("room"))
        that(response2.getString("contact")).isEqualTo(expected.getString("contact"))
        that(response2.getString("currentOwner")).isEqualTo(expected.getString("currentOwner"))
        that(response2.getString("previousOwner")).isEqualTo(expected.getString("previousOwner"))
        that(response2.getString("orderNumber")).isEqualTo(expected.getString("orderNumber"))
        that(response2.getString("color")).isEqualTo(expected.getString("color"))
        that(response2.getString("serialNumber")).isEqualTo(expected.getString("serialNumber"))
        that(response2.getString("maintenanceDate")).isEqualTo(expected.getString("maintenanceDate"))
        that(response2.getString("status")).isEqualTo(expected.getString("status"))
        that(response2.getString("comments")).isEqualTo(expected.getString("comments"))
        that(response2.getString("lastModifiedDate")).isEqualTo(expected.getString("lastModifiedDate"))
        that(response2.getString("lastModifiedBy")).isEqualTo(expected.getString("lastModifiedBy"))
        that(response2.containsKey("timestamp")).isTrue()
        that(response2.containsKey("battery")).isTrue()
        that(response2.containsKey("beaconStatus")).isTrue()
        that(response2.containsKey("latitude")).isTrue()
        that(response2.containsKey("longitude")).isTrue()
        that(response2.containsKey("floor")).isTrue()
      }
      testContext.completeNow()
    }
  }

  @Test
  @Order(42)
  @DisplayName("Getting the closest items succeeds with AC")
  fun getClosestItemsSucceedsWithAC(testContext: VertxTestContext) {
    val response1 = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $tokenGrp1")
      } When {
        queryParam("latitude", 42)
        queryParam("longitude", -7.9)
        get("/api/items/closest")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    val expected = item2Grp1

    testContext.verify {
      expectThat(response1.isEmpty).isFalse()
      val response = response1.getJsonArray("unknown").getJsonObject(0)
      val id = response.remove("id")
      expectThat(id).isEqualTo(item2IDGrp1)
      expect {
        that(response.getString("beacon")).isEqualTo(expected.getString("beacon"))
        that(response.getString("category")).isEqualTo(expected.getString("category"))
        that(response.getString("service")).isEqualTo(expected.getString("service"))
        that(response.getString("accesscontrolstring")).isEqualTo(expected.getString("accessControlString"))
        that(response.containsKey("timestamp")).isTrue()
        that(response.containsKey("battery")).isTrue()
        that(response.containsKey("latitude")).isTrue()
        that(response.containsKey("longitude")).isTrue()
        that(response.containsKey("floor")).isTrue()
      }
      testContext.completeNow()
    }
  }

  @Test
  @Order(43)
  @DisplayName("Registering an item succeeds with a user with ac string = biot:grp1 without specifying it in the json creates the item correctly with the AC string of the user")
  fun registerItemSucceeds3(testContext: VertxTestContext) {
    val insertJson = item3.copy().apply { remove("accessControlString") }

    var item3ID = -1
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      header("Authorization", "Bearer $tokenGrp1")
      body(insertJson.encode())
    } When {
      post("/api/items")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isNotEmpty() // it returns the id of the registered item
      item3ID = response.toInt()
    }

    val expected = insertJson.copy().put("accessControlString", user2.getString("accessControlString"))
    val response2 = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/items/$item3ID")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response2).isNotNull()
      val id = response2.remove("id")
      expectThat(id).isEqualTo(item3ID)
      expect {
        that(response2.getString("beacon")).isEqualTo(expected.getString("beacon"))
        that(response2.getString("category")).isEqualTo(expected.getString("category"))
        that(response2.getString("service")).isEqualTo(expected.getString("service"))
        that(response2.getString("itemID")).isEqualTo(expected.getString("itemID"))
        that(response2.getString("accessControlString")).isEqualTo(expected.getString("accessControlString"))
        that(response2.getString("brand")).isEqualTo(expected.getString("brand"))
        that(response2.getString("model")).isEqualTo(expected.getString("model"))
        that(response2.getString("supplier")).isEqualTo(expected.getString("supplier"))
        that(response2.getString("purchaseDate")).isEqualTo(expected.getString("purchaseDate"))
        that(response2.getDouble("purchasePrice")).isEqualTo(expected.getDouble("purchasePrice"))
        that(response2.getString("originLocation")).isEqualTo(expected.getString("originLocation"))
        that(response2.getString("currentLocation")).isEqualTo(expected.getString("currentLocation"))
        that(response2.getString("room")).isEqualTo(expected.getString("room"))
        that(response2.getString("contact")).isEqualTo(expected.getString("contact"))
        that(response2.getString("currentOwner")).isEqualTo(expected.getString("currentOwner"))
        that(response2.getString("previousOwner")).isEqualTo(expected.getString("previousOwner"))
        that(response2.getString("orderNumber")).isEqualTo(expected.getString("orderNumber"))
        that(response2.getString("color")).isEqualTo(expected.getString("color"))
        that(response2.getString("serialNumber")).isEqualTo(expected.getString("serialNumber"))
        that(response2.getString("maintenanceDate")).isEqualTo(expected.getString("maintenanceDate"))
        that(response2.getString("status")).isEqualTo(expected.getString("status"))
        that(response2.getString("comments")).isEqualTo(expected.getString("comments"))
        that(response2.getString("lastModifiedDate")).isEqualTo(expected.getString("lastModifiedDate"))
        that(response2.getString("lastModifiedBy")).isEqualTo(expected.getString("lastModifiedBy"))
        that(response2.containsKey("timestamp")).isTrue()
        that(response2.containsKey("battery")).isTrue()
        that(response2.containsKey("beaconStatus")).isTrue()
        that(response2.containsKey("latitude")).isTrue()
        that(response2.containsKey("longitude")).isTrue()
        that(response2.containsKey("floor")).isTrue()
      }
    }

    // Delete the item
    val response3 = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
    } When {
      delete("/api/items/$item3ID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response3).isEmpty()
      testContext.completeNow()
    }

  }


  @Test
  @Order(44)
  @DisplayName("Registering an item succeeds with a user with ac string = biot:grp1 specifying it in the json creates the item correctly with the AC string specified in the JSON")
  fun registerItemSucceeds4(testContext: VertxTestContext) {
    val insertJson = item3.copy()

    var item3ID = -1
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      header("Authorization", "Bearer $tokenGrp1")
      body(insertJson.encode())
    } When {
      post("/api/items")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isNotEmpty() // it returns the id of the registered item
      item3ID = response.toInt()
    }

    val expected = insertJson.copy()
    val response2 = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/items/$item3ID")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response2).isNotNull()
      val id = response2.remove("id")
      expectThat(id).isEqualTo(item3ID)
      expect {
        that(response2.getString("beacon")).isEqualTo(expected.getString("beacon"))
        that(response2.getString("category")).isEqualTo(expected.getString("category"))
        that(response2.getString("service")).isEqualTo(expected.getString("service"))
        that(response2.getString("itemID")).isEqualTo(expected.getString("itemID"))
        that(response2.getString("accessControlString")).isEqualTo(expected.getString("accessControlString"))
        that(response2.getString("brand")).isEqualTo(expected.getString("brand"))
        that(response2.getString("model")).isEqualTo(expected.getString("model"))
        that(response2.getString("supplier")).isEqualTo(expected.getString("supplier"))
        that(response2.getString("purchaseDate")).isEqualTo(expected.getString("purchaseDate"))
        that(response2.getDouble("purchasePrice")).isEqualTo(expected.getDouble("purchasePrice"))
        that(response2.getString("originLocation")).isEqualTo(expected.getString("originLocation"))
        that(response2.getString("currentLocation")).isEqualTo(expected.getString("currentLocation"))
        that(response2.getString("room")).isEqualTo(expected.getString("room"))
        that(response2.getString("contact")).isEqualTo(expected.getString("contact"))
        that(response2.getString("currentOwner")).isEqualTo(expected.getString("currentOwner"))
        that(response2.getString("previousOwner")).isEqualTo(expected.getString("previousOwner"))
        that(response2.getString("orderNumber")).isEqualTo(expected.getString("orderNumber"))
        that(response2.getString("color")).isEqualTo(expected.getString("color"))
        that(response2.getString("serialNumber")).isEqualTo(expected.getString("serialNumber"))
        that(response2.getString("maintenanceDate")).isEqualTo(expected.getString("maintenanceDate"))
        that(response2.getString("status")).isEqualTo(expected.getString("status"))
        that(response2.getString("comments")).isEqualTo(expected.getString("comments"))
        that(response2.getString("lastModifiedDate")).isEqualTo(expected.getString("lastModifiedDate"))
        that(response2.getString("lastModifiedBy")).isEqualTo(expected.getString("lastModifiedBy"))
        that(response2.containsKey("timestamp")).isTrue()
        that(response2.containsKey("battery")).isTrue()
        that(response2.containsKey("beaconStatus")).isTrue()
        that(response2.containsKey("latitude")).isTrue()
        that(response2.containsKey("longitude")).isTrue()
        that(response2.containsKey("floor")).isTrue()
      }
    }

    // Delete the item
    val response3 = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
    } When {
      delete("/api/items/$item3ID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response3).isEmpty()
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
