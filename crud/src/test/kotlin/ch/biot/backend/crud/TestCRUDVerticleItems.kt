/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

package ch.biot.backend.crud

import ch.biot.backend.crud.CRUDVerticle.Companion.HTTP_PORT
import ch.biot.backend.crud.CRUDVerticle.Companion.MONGO_PORT
import ch.biot.backend.crud.CRUDVerticle.Companion.TIMESCALE_PORT
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
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.get
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
import strikt.assertions.*
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

  private val closestItem = jsonObjectOf(
    "beacon" to "ff:ff:ab:ab:ab:ab",
    "category" to "Lit",
    "service" to "Bloc 1"
  )

  private val existingBeaconData = jsonObjectOf(
    "mac" to existingItem.getString("beacon"),
    "battery" to 50,
    "status" to "disponible",
    "latitude" to 2.333333,
    "longitude" to -2.333333,
    "floor" to 1,
    "temperature" to 42.3
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
        port = TIMESCALE_PORT,
        host = "localhost",
        database = "biot",
        user = "biot",
        password = "biot",
        cachePreparedStatements = true
      )
    pgPool = PgPool.pool(vertx, pgConnectOptions, poolOptionsOf())

    dropAllItems()
      .compose {
        insertItems()
      }.onSuccess {
        vertx.deployVerticle(CRUDVerticle(), testContext.succeedingThenComplete())
      }.onFailure(testContext::failNow)
  }

  private fun dropAllItems() = pgPool.query("DELETE FROM items").execute()
    .compose {
      pgPool.query("DELETE FROM beacon_data").execute()
    }

  private fun insertItems() = pgPool.preparedQuery(insertItem("items"))
    .execute(Tuple.of(existingItem["beacon"], existingItem["category"], existingItem["service"]))
    .compose {
      existingItemID = it.iterator().next().getInteger("id")
      pgPool.preparedQuery(insertItem("items"))
        .execute(Tuple.of(closestItem["beacon"], closestItem["category"], closestItem["service"]))
    }.compose {
      pgPool.preparedQuery(insertItem("items"))
        .execute(Tuple.of("fake1", closestItem["category"], closestItem["service"]))
    }.compose {
      pgPool.preparedQuery(insertItem("items"))
        .execute(Tuple.of("fake2", closestItem["category"], closestItem["service"]))
    }.compose {
      pgPool.preparedQuery(insertItem("items"))
        .execute(Tuple.of("fake3", closestItem["category"], closestItem["service"]))
    }
    .compose {
      pgPool.preparedQuery(insertItem("items"))
        .execute(Tuple.of("fake4", closestItem["category"], closestItem["service"]))
    }
    .compose {
      pgPool.preparedQuery(insertItem("items"))
        .execute(Tuple.of("fake5", closestItem["category"], closestItem["service"]))
    }
    .compose {
      pgPool.preparedQuery(INSERT_BEACON_DATA)
        .execute(
          Tuple.of(
            existingBeaconData.getString("mac"),
            existingBeaconData.getInteger("battery") + 5,
            existingBeaconData.getString("status"),
            existingBeaconData.getDouble("latitude"),
            existingBeaconData.getDouble("longitude"),
            existingBeaconData.getInteger("floor"),
            existingBeaconData.getDouble("temperature")
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
            existingBeaconData.getDouble("longitude"),
            existingBeaconData.getInteger("floor"),
            existingBeaconData.getDouble("temperature")
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
            existingBeaconData.getDouble("longitude"),
            existingBeaconData.getInteger("floor"),
              existingBeaconData.getDouble("temperature")
          )
        )
    }.compose {
      pgPool.preparedQuery(INSERT_BEACON_DATA)
        .execute(
          Tuple.of(
            closestItem.getString("beacon"),
            existingBeaconData.getInteger("battery"),
            existingBeaconData.getString("status"),
            42,
            -8,
            existingBeaconData.getInteger("floor"),
            existingBeaconData.getDouble("temperature")
          )
        )
    }.compose {
      pgPool.preparedQuery(INSERT_BEACON_DATA)
        .execute(
          Tuple.of(
            "fake1",
            existingBeaconData.getInteger("battery"),
            existingBeaconData.getString("status"),
            44,
            -8,
            existingBeaconData.getInteger("floor"),
            existingBeaconData.getDouble("temperature")
          )
        )
    }.compose {
      pgPool.preparedQuery(INSERT_BEACON_DATA)
        .execute(
          Tuple.of(
            "fake2",
            existingBeaconData.getInteger("battery"),
            existingBeaconData.getString("status"),
            45,
            -8,
            existingBeaconData.getInteger("floor"),
            existingBeaconData.getDouble("temperature")
          )
        )
    }.compose {
      pgPool.preparedQuery(INSERT_BEACON_DATA)
        .execute(
          Tuple.of(
            "fake3",
            existingBeaconData.getInteger("battery"),
            existingBeaconData.getString("status"),
            46,
            -8,
            existingBeaconData.getInteger("floor"),
            existingBeaconData.getDouble("temperature")
          )
        )
    }.compose {
      pgPool.preparedQuery(INSERT_BEACON_DATA)
        .execute(
          Tuple.of(
            "fake4",
            existingBeaconData.getInteger("battery"),
            existingBeaconData.getString("status"),
            47,
            -8,
            existingBeaconData.getInteger("floor"),
            existingBeaconData.getDouble("temperature")
          )
        )
    }.compose {
      pgPool.preparedQuery(INSERT_BEACON_DATA)
        .execute(
          Tuple.of(
            "fake5",
            existingBeaconData.getInteger("battery"),
            existingBeaconData.getString("status"),
            47,
            -8,
            2,
            3.3
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
      queryParam("company", "biot")
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
  @DisplayName("registerItem correctly registers a new item with a custom id")
  fun registerWithIdIsCorrect(testContext: VertxTestContext) {
    val newItem = jsonObjectOf(
      "id" to 42,
      "beacon" to "aa:aa:aa:aa:aa:aa",
      "category" to "Lit",
      "service" to "Bloc 1"
    )

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      queryParam("company", "biot")
      post("/items")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response.toInt()).isEqualTo(42) // it returns the id of the registered item
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getItems correctly retrieves all items")
  fun getItemsIsCorrect(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        queryParam("company", "biot")
        get("/items")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonArray()

    testContext.verify {
      expectThat(response.isEmpty).isFalse()
      expectThat(response.size()).isEqualTo(7)
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getItems returns an empty list for another company")
  fun getItemsReturnsEmptyForAnotherCompany(testContext: VertxTestContext) {
    pgPool.query(
      """CREATE TABLE IF NOT EXISTS items_another
(
    id SERIAL PRIMARY KEY,
    beacon VARCHAR(17) NOT NULL UNIQUE,
    category VARCHAR(100) NOT NULL,
    service VARCHAR(100)
);"""
    ).execute().compose {
      pgPool.query(
        """CREATE TABLE IF NOT EXISTS beacon_data_another
(
    time TIMESTAMPTZ NOT NULL,
    mac VARCHAR(17) NOT NULL,
    battery INTEGER,
    status VARCHAR(50),
    latitude DECIMAL(9, 6),
    longitude DECIMAL(9, 6),
    floor INTEGER
);"""
      ).execute()
    }.onComplete {
      val response = Buffer.buffer(
        Given {
          spec(requestSpecification)
          accept(ContentType.JSON)
        } When {
          queryParam("company", "another")
          get("/items")
        } Then {
          statusCode(200)
        } Extract {
          asString()
        }
      ).toJsonArray()

      testContext.verify {
        expectThat(response.isEmpty).isTrue()
        testContext.completeNow()
      }
    }
  }

  @Test
  @DisplayName("getItems fails for another company whose tables have not been created")
  fun getItemsFailsForAnotherCompanyNotCreated(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
    } When {
      queryParam("company", "anotherCompany")
      get("/items")
    } Then {
      statusCode(500)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Internal Server Error")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getItems with category correctly retrieves all items of the given category")
  fun getItemsWithCategoryIsCorrect(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        queryParam("category", existingItem.getString("category"))
        queryParam("company", "biot")
        get("/items")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonArray()

    testContext.verify {
      expectThat(response.isEmpty).isFalse()
      response.forEach {
        val jsonObj = it as JsonObject
        expectThat(jsonObj.getString("category")).isEqualTo(existingItem.getString("category"))
      }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getClosestItems correctly retrieves the 5 closest items per floor")
  fun getClosestItemsIsCorrect(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        queryParam("latitude", 42)
        queryParam("longitude", -8)
        get("/items/closest")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      val firstFloor: JsonArray = response["1"]
      expectThat(firstFloor.size()).isEqualTo(5)
      val closestFirstFloor = firstFloor.getJsonObject(0)
      expectThat(closestFirstFloor.getString("beacon")).isEqualTo(closestItem.getString("beacon"))

      val secondFloor: JsonArray = response["2"]
      expectThat(secondFloor.size()).isEqualTo(1)
      val closestSecondFloor = secondFloor.getJsonObject(0)
      expectThat(closestSecondFloor.getString("beacon")).isEqualTo("fake5")

      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getClosestItems correctly retrieves the 5 closest items of the given category per floor")
  fun getClosestItemsWithCategoryIsCorrect(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        queryParam("latitude", 42)
        queryParam("longitude", -8)
        queryParam("category", closestItem.getString("category"))
        get("/items/closest")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      val firstFloor: JsonArray = response["1"]
      expectThat(firstFloor.size()).isEqualTo(5)
      val closestFirstFloor = firstFloor.getJsonObject(0)
      expectThat(closestFirstFloor.getString("category")).isEqualTo(closestItem.getString("category"))
      expectThat(closestFirstFloor.getString("beacon")).isEqualTo(closestItem.getString("beacon"))

      val secondFloor: JsonArray = response["2"]
      expectThat(secondFloor.size()).isEqualTo(1)
      val closestSecondFloor = secondFloor.getJsonObject(0)
      expectThat(closestSecondFloor.getString("category")).isEqualTo(closestItem.getString("category"))
      expectThat(closestSecondFloor.getString("beacon")).isEqualTo("fake5")

      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getCategories correctly retrieves all categories")
  fun getCategoriesIsCorrect(testContext: VertxTestContext) {
    val expected = JsonArray(listOf(existingItem.getString("category"), closestItem.getString("category")))

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        queryParam("company", "biot")
        get("/items/categories")
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
  @DisplayName("getItem correctly retrieves the desired item")
  fun getItemIsCorrect(testContext: VertxTestContext) {
    val expected = existingItem.copy().apply {
      put("battery", existingBeaconData.getInteger("battery"))
      put("status", existingBeaconData.getString("status"))
      put("latitude", existingBeaconData.getDouble("latitude"))
      put("longitude", existingBeaconData.getDouble("longitude"))
      put("floor", existingBeaconData.getInteger("floor"))
      put("temperature", existingBeaconData.getDouble("temperature"))
    }

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        queryParam("company", "biot")
        get("/items/$existingItemID")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

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
  fun updateItemIsCorrect(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(updateItemJson.encode())
    } When {
      queryParam("company", "biot")
      put("/items/$existingItemID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
    }

    pgPool.preparedQuery(getItem("items", "beacon_data")).execute(Tuple.of(existingItemID))
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
          that(json.getInteger("floor")).isEqualTo(existingBeaconData.getInteger("floor"))
          that(json.getDouble("temperature")).isEqualTo(existingBeaconData.getDouble("temperature"))
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
      queryParam("company", "biot")
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
      queryParam("company", "biot")
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
      "INSERT INTO beacon_data(time, mac, battery, status, latitude, longitude, floor, temperature) values(NOW(), $1, $2, $3, $4, $5, $6, $7)"

    private val requestSpecification: RequestSpecification = RequestSpecBuilder()
      .addFilters(listOf(ResponseLoggingFilter(), RequestLoggingFilter()))
      .setBaseUri("http://localhost")
      .setPort(HTTP_PORT)
      .build()

    private val instance: KDockerComposeContainer by lazy { defineDockerCompose() }

    class KDockerComposeContainer(file: File) : DockerComposeContainer<KDockerComposeContainer>(file)

    private fun defineDockerCompose() = KDockerComposeContainer(File("../docker-compose.yml"))
      .withExposedService(
        "mongo_1", MONGO_PORT
      ).withExposedService("timescale_1", TIMESCALE_PORT)

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
