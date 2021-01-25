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
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.pgclient.pgConnectOptionsOf
import io.vertx.kotlin.sqlclient.poolOptionsOf
import io.vertx.pgclient.PgPool
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNotEmpty
import java.io.File


@ExtendWith(VertxExtension::class)
@Testcontainers
class TestCRUDVerticleItems {

  private lateinit var pgPool: PgPool

  private var existingItemID: Int = 1
  private val existingItem = jsonObjectOf(
    "beacon" to "ab:ab:ab:ab:ab:ab",
    "category" to "ECG",
    "service" to "Bloc 2"
  )

  private val existingBeaconData = jsonObjectOf(
    "mac" to existingItem.getString("beacon"),
    "battery" to 50,
    "status" to "disponible",
    "latitude" to 2.333333,
    "longitude" to -2.333333
  )

  private val updateItemJson = jsonObjectOf(
    "beacon" to "ad:ab:ab:ab:ab:ab",
    "category" to "Lit",
    "service" to "Bloc 42"
  )

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext) {
    val pgConnectOptions =
      pgConnectOptionsOf(
        port = CRUDVerticle.TIMESCALE_PORT,
        host = "localhost",
        database = "postgres",
        user = "postgres",
        password = "biot"
      )
    pgPool = PgPool.pool(vertx, pgConnectOptions, poolOptionsOf())

    dropAllItems()
      .compose {
        insertItem()
      }.onSuccess {
        vertx.deployVerticle(CRUDVerticle(), testContext.succeedingThenComplete())
      }.onFailure(testContext::failNow)
  }

  private fun dropAllItems() = pgPool.query("DELETE FROM items").execute()
    .compose {
      pgPool.query("DELETE FROM beacon_data").execute()
    }

  private fun insertItem() = pgPool.preparedQuery(INSERT_ITEM)
    .execute(Tuple.of(existingItem["beacon"], existingItem["category"], existingItem["service"]))
    .compose {
      existingItemID = it.iterator().next().getInteger("id")

      pgPool.preparedQuery(INSERT_BEACON_DATA)
        .execute(
          Tuple.of(
            existingBeaconData.getString("mac"),
            existingBeaconData.getInteger("battery") + 5,
            existingBeaconData.getString("status"),
            existingBeaconData.getDouble("latitude"),
            existingBeaconData.getDouble("longitude")
          )
        )
    }.compose {
      pgPool.preparedQuery(INSERT_BEACON_DATA)
        .execute(
          Tuple.of(
            existingBeaconData.getString("mac"),
            existingBeaconData.getInteger("battery"),
            existingBeaconData.getString("status"),
            existingBeaconData.getDouble("latitude"),
            existingBeaconData.getDouble("longitude")
          )
        )
    }.compose {
      pgPool.preparedQuery(INSERT_BEACON_DATA)
        .execute(
          Tuple.of(
            updateItemJson.getString("beacon"),
            existingBeaconData.getInteger("battery"),
            existingBeaconData.getString("status"),
            existingBeaconData.getDouble("latitude"),
            existingBeaconData.getDouble("longitude")
          )
        )
    }

  @AfterEach
  fun cleanup(testContext: VertxTestContext) {
    dropAllItems().compose {
      pgPool.close()
    }.onSuccess { testContext.completeNow() }
      .onFailure(testContext::failNow)
  }

  @Test
  @DisplayName("registerItem correctly registers a new item")
  fun registerIsCorrect(testContext: VertxTestContext) {
    val newItem = jsonObjectOf(
      "beacon" to "aa:aa:aa:aa:aa:aa",
      "category" to "Lit",
      "service" to "Bloc 1"
    )

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      post("/items")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isNotEmpty() // it returns the id of the registered item
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getItems correctly retrieves all items")
  fun getItemsIsCorrect(testContext: VertxTestContext) {
    val expected = jsonArrayOf(existingItem.copy().apply {
      put("battery", existingBeaconData.getInteger("battery"))
      put("status", existingBeaconData.getString("status"))
      put("latitude", existingBeaconData.getDouble("latitude"))
      put("longitude", existingBeaconData.getDouble("longitude"))
    })

    val response = Buffer.buffer(Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
    } When {
      get("/items")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonArray()

    testContext.verify {
      val id = response.getJsonObject(0).remove("id")
      val timestamp: String = response.getJsonObject(0).remove("timestamp") as String
      expectThat(response).isEqualTo(expected)
      expectThat(id).isEqualTo(existingItemID)
      expectThat(timestamp).isNotEmpty()
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getItem correctly retrieves the desired item")
  fun getItemIsCorrect(testContext: VertxTestContext) {
    val expected = existingItem.copy().apply {
      put("battery", existingBeaconData.getInteger("battery"))
      put("status", existingBeaconData.getString("status"))
      put("latitude", existingBeaconData.getDouble("latitude"))
      put("longitude", existingBeaconData.getDouble("longitude"))
    }

    val response = Buffer.buffer(Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
    } When {
      get("/items/$existingItemID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonObject()

    testContext.verify {
      val id = response.remove("id")
      val timestamp: String = response.remove("timestamp") as String
      expectThat(response).isEqualTo(expected)
      expectThat(id).isEqualTo(existingItemID)
      expectThat(timestamp).isNotEmpty()
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("updateItem correctly updates the desired item")
  fun updateItemIsCorrect(vertx: Vertx, testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(updateItemJson.encode())
    } When {
      put("/items/$existingItemID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
    }

    pgPool.preparedQuery(GET_ITEM).execute(Tuple.of(existingItemID))
      .onSuccess { res ->
        val json = res.iterator().next().toJson()
        expect {
          that(json.getString("beacon")).isEqualTo(updateItemJson.getString("beacon"))
          that(json.getString("category")).isEqualTo(updateItemJson.getString("category"))
          that(json.getString("service")).isEqualTo(updateItemJson.getString("service"))
          that(json.getInteger("battery")).isEqualTo(existingBeaconData.getInteger("battery"))
          that(json.getString("status")).isEqualTo(existingBeaconData.getString("status"))
          that(json.getDouble("latitude")).isEqualTo(existingBeaconData.getDouble("latitude"))
          that(json.getDouble("longitude")).isEqualTo(existingBeaconData.getDouble("longitude"))
        }
        testContext.completeNow()
      }
      .onFailure(testContext::failNow)
  }

  @Test
  @DisplayName("deleteItem correctly deletes the item")
  fun deleteItemIsCorrect(testContext: VertxTestContext) {
    val newItem = jsonObjectOf(
      "beacon" to "ab:cd:ef:aa:aa:aa",
      "category" to "Lit",
      "service" to "Bloc 42"
    )

    // Register the item
    val id = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      post("/items")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    // Delete the item
    val response = Given {
      spec(requestSpecification)
    } When {
      delete("/items/$id")
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

    private const val INSERT_BEACON_DATA =
      "INSERT INTO beacon_data(time, mac, battery, status, latitude, longitude) values(NOW(), $1, $2, $3, $4, $5)"

    private val requestSpecification: RequestSpecification = RequestSpecBuilder()
      .addFilters(listOf(ResponseLoggingFilter(), RequestLoggingFilter()))
      .setBaseUri("http://localhost")
      .setPort(CRUDVerticle.HTTP_PORT)
      .build()

    private val instance: KDockerComposeContainer by lazy { defineDockerCompose() }

    class KDockerComposeContainer(file: File) : DockerComposeContainer<KDockerComposeContainer>(file)

    private fun defineDockerCompose() = KDockerComposeContainer(File("../docker-compose.yml"))
      .withExposedService(
        "mongo_1", CRUDVerticle.MONGO_PORT
      ).withExposedService("timescale_1", CRUDVerticle.TIMESCALE_PORT)

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
