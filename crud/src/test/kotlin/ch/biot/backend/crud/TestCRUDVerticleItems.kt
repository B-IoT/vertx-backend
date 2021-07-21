/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud

import ch.biot.backend.crud.CRUDVerticle.Companion.BAD_REQUEST_CODE
import ch.biot.backend.crud.CRUDVerticle.Companion.HTTP_PORT
import ch.biot.backend.crud.CRUDVerticle.Companion.MONGO_PORT
import ch.biot.backend.crud.CRUDVerticle.Companion.TIMESCALE_PORT
import ch.biot.backend.crud.queries.getItem
import ch.biot.backend.crud.queries.insertItem
import ch.biot.backend.crud.updates.UpdateType
import io.restassured.builder.RequestSpecBuilder
import io.restassured.filter.log.RequestLoggingFilter
import io.restassured.filter.log.ResponseLoggingFilter
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.specification.RequestSpecification
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.pgclient.pgConnectOptionsOf
import io.vertx.kotlin.sqlclient.poolOptionsOf
import io.vertx.pgclient.PgPool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
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
@Testcontainers
class TestCRUDVerticleItems {

  private lateinit var pgClient: SqlClient

  private var existingItemID: Int = 1
  private val existingItem = jsonObjectOf(
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

  private val closestItem = jsonObjectOf(
    "beacon" to "ff:ff:ab:ab:ab:ab",
    "category" to "Lit",
    "service" to "Bloc 1",
    "itemID" to "cde",
    "accessControlString" to "biot",
    "brand" to "mazda",
    "model" to "mx5",
    "supplier" to "plier",
    "purchaseDate" to LocalDate.of(2021, 8, 20).toString(),
    "purchasePrice" to 57.8,
    "originLocation" to "center3",
    "currentLocation" to "center4",
    "room" to "614",
    "contact" to "Monsieur Delacroix",
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

  private val existingBeaconData = jsonObjectOf(
    "mac" to existingItem.getString("beacon"),
    "battery" to 50,
    "beaconStatus" to "disponible",
    "latitude" to 2.333333,
    "longitude" to -2.333333,
    "floor" to 1,
    "temperature" to 42.3
  )

  private val updateItemJson = jsonObjectOf(
    "beacon" to "ad:ab:ab:ab:ab:ab",
    "category" to "Lit",
    "service" to "Bloc 42",
    "itemID" to "new",
    "accessControlString" to "biot",
    "brand" to "fiat",
    "model" to "panda",
    "supplier" to "rossi",
    "purchaseDate" to LocalDate.of(2020, 11, 24).toString(),
    "purchasePrice" to 1007.8,
    "originLocation" to "center5",
    "currentLocation" to "center6",
    "room" to "17",
    "contact" to "Jimmy",
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

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext) = runBlocking(vertx.dispatcher()) {
    val pgConnectOptions =
      pgConnectOptionsOf(
        port = TIMESCALE_PORT,
        host = "localhost",
        database = "biot",
        user = "biot",
        password = "biot",
        cachePreparedStatements = true
      )
    pgClient = PgPool.client(vertx, pgConnectOptions, poolOptionsOf())

    try {
      dropAllItems().await()
      insertItems().await()
      vertx.deployVerticle(CRUDVerticle(), testContext.succeedingThenComplete())
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  private fun dropAllItems(): CompositeFuture {
    return CompositeFuture.all(
      pgClient.query("DELETE FROM items").execute(),
      pgClient.query("DELETE FROM beacon_data").execute()
    )
  }

  private suspend fun insertItems(): Future<RowSet<Row>> {
    val result = pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          existingItem["beacon"],
          existingItem["category"],
          existingItem["service"],
          existingItem["itemID"],
          existingItem["accessControlString"],
          existingItem["brand"],
          existingItem["model"],
          existingItem["supplier"],
          LocalDate.parse(existingItem["purchaseDate"]),
          existingItem["purchasePrice"],
          existingItem["originLocation"],
          existingItem["currentLocation"],
          existingItem["room"],
          existingItem["contact"],
          existingItem["currentOwner"],
          existingItem["previousOwner"],
          existingItem["orderNumber"],
          existingItem["color"],
          existingItem["serialNumber"],
          LocalDate.parse(existingItem["maintenanceDate"]),
          existingItem["status"],
          existingItem["comments"],
          LocalDate.parse(existingItem["lastModifiedDate"]),
          existingItem["lastModifiedBy"]
        )
      ).await()

    existingItemID = result.iterator().next().getInteger("id")

    pgClient.preparedQuery(insertItem("items"))
          .execute(
            Tuple.of(
              closestItem["beacon"],
              closestItem["category"],
              closestItem["service"],
              closestItem["itemID"],
              closestItem["accessControlString"],
              closestItem["brand"],
              closestItem["model"],
              closestItem["supplier"],
              LocalDate.parse(existingItem["purchaseDate"]),
              closestItem["purchasePrice"],
              closestItem["originLocation"],
              closestItem["currentLocation"],
              closestItem["room"],
              closestItem["contact"],
              closestItem["currentOwner"],
              closestItem["previousOwner"],
              closestItem["orderNumber"],
              closestItem["color"],
              closestItem["serialNumber"],
              LocalDate.parse(closestItem["maintenanceDate"]),
              closestItem["status"],
              closestItem["comments"],
              LocalDate.parse(closestItem["lastModifiedDate"]),
              closestItem["lastModifiedBy"]
            )
          ).await()

        pgClient.preparedQuery(insertItem("items"))
          .execute(
            Tuple.of(
              "fake1",
              closestItem["category"],
              closestItem["service"],
              closestItem["itemID"],
              closestItem["accessControlString"],
              closestItem["brand"],
              closestItem["model"],
              closestItem["supplier"],
              LocalDate.parse(closestItem["purchaseDate"]),
              closestItem["purchasePrice"],
              closestItem["originLocation"],
              closestItem["currentLocation"],
              closestItem["room"],
              closestItem["contact"],
              closestItem["currentOwner"],
              closestItem["previousOwner"],
              closestItem["orderNumber"],
              closestItem["color"],
              closestItem["serialNumber"],
              LocalDate.parse(closestItem["maintenanceDate"]),
              closestItem["status"],
              closestItem["comments"],
              LocalDate.parse(closestItem["lastModifiedDate"]),
              closestItem["lastModifiedBy"]
            )
          ).await()

        pgClient.preparedQuery(insertItem("items"))
          .execute(
            Tuple.of(
              "fake2",
              closestItem["category"],
              closestItem["service"],
              closestItem["itemID"],
              closestItem["accessControlString"],
              closestItem["brand"],
              closestItem["model"],
              closestItem["supplier"],
              LocalDate.parse(closestItem["purchaseDate"]),
              closestItem["purchasePrice"],
              closestItem["originLocation"],
              closestItem["currentLocation"],
              closestItem["room"],
              closestItem["contact"],
              closestItem["currentOwner"],
              closestItem["previousOwner"],
              closestItem["orderNumber"],
              closestItem["color"],
              closestItem["serialNumber"],
              LocalDate.parse(closestItem["maintenanceDate"]),
              closestItem["status"],
              closestItem["comments"],
              LocalDate.parse(closestItem["lastModifiedDate"]),
              closestItem["lastModifiedBy"]
            )
          ).await()

        pgClient.preparedQuery(insertItem("items"))
          .execute(
            Tuple.of(
              "fake3",
              closestItem["category"],
              closestItem["service"],
              closestItem["itemID"],
              closestItem["accessControlString"],
              closestItem["brand"],
              closestItem["model"],
              closestItem["supplier"],
              LocalDate.parse(closestItem["purchaseDate"]),
              closestItem["purchasePrice"],
              closestItem["originLocation"],
              closestItem["currentLocation"],
              closestItem["room"],
              closestItem["contact"],
              closestItem["currentOwner"],
              closestItem["previousOwner"],
              closestItem["orderNumber"],
              closestItem["color"],
              closestItem["serialNumber"],
              LocalDate.parse(closestItem["maintenanceDate"]),
              closestItem["status"],
              closestItem["comments"],
              LocalDate.parse(closestItem["lastModifiedDate"]),
              closestItem["lastModifiedBy"]
            )
          ).await()

        pgClient.preparedQuery(insertItem("items"))
          .execute(
            Tuple.of(
              "fake4",
              closestItem["category"],
              closestItem["service"],
              closestItem["itemID"],
              closestItem["accessControlString"],
              closestItem["brand"],
              closestItem["model"],
              closestItem["supplier"],
              LocalDate.parse(closestItem["purchaseDate"]),
              closestItem["purchasePrice"],
              closestItem["originLocation"],
              closestItem["currentLocation"],
              closestItem["room"],
              closestItem["contact"],
              closestItem["currentOwner"],
              closestItem["previousOwner"],
              closestItem["orderNumber"],
              closestItem["color"],
              closestItem["serialNumber"],
              LocalDate.parse(closestItem["maintenanceDate"]),
              closestItem["status"],
              closestItem["comments"],
              LocalDate.parse(closestItem["lastModifiedDate"]),
              closestItem["lastModifiedBy"]
            )
          ).await()

        pgClient.preparedQuery(insertItem("items"))
          .execute(
            Tuple.of(
              "fake5",
              closestItem["category"],
              closestItem["service"],
              closestItem["itemID"],
              closestItem["accessControlString"],
              closestItem["brand"],
              closestItem["model"],
              closestItem["supplier"],
              LocalDate.parse(closestItem["purchaseDate"]),
              closestItem["purchasePrice"],
              closestItem["originLocation"],
              closestItem["currentLocation"],
              closestItem["room"],
              closestItem["contact"],
              closestItem["currentOwner"],
              closestItem["previousOwner"],
              closestItem["orderNumber"],
              closestItem["color"],
              closestItem["serialNumber"],
              LocalDate.parse(closestItem["maintenanceDate"]),
              closestItem["status"],
              closestItem["comments"],
              LocalDate.parse(closestItem["lastModifiedDate"]),
              closestItem["lastModifiedBy"]
            )
          ).await()

        pgClient.preparedQuery(INSERT_BEACON_DATA)
          .execute(
            Tuple.of(
              existingBeaconData.getString("mac"),
              existingBeaconData.getInteger("battery") + 5,
              existingBeaconData.getString("beaconStatus"),
              existingBeaconData.getDouble("latitude"),
              existingBeaconData.getDouble("longitude"),
              existingBeaconData.getInteger("floor"),
              existingBeaconData.getDouble("temperature")
            )
          ).await()

        pgClient.preparedQuery(INSERT_BEACON_DATA)
          .execute(
            Tuple.of(
              existingBeaconData.getString("mac"),
              existingBeaconData.getInteger("battery"),
              existingBeaconData.getString("beaconStatus"),
              existingBeaconData.getDouble("latitude"),
              existingBeaconData.getDouble("longitude"),
              existingBeaconData.getInteger("floor"),
              existingBeaconData.getDouble("temperature")
            )
          ).await()

        pgClient.preparedQuery(INSERT_BEACON_DATA)
          .execute(
            Tuple.of(
              updateItemJson.getString("beacon"),
              existingBeaconData.getInteger("battery"),
              existingBeaconData.getString("beaconStatus"),
              existingBeaconData.getDouble("latitude"),
              existingBeaconData.getDouble("longitude"),
              existingBeaconData.getInteger("floor"),
              existingBeaconData.getDouble("temperature")
            )
          ).await()

        pgClient.preparedQuery(INSERT_BEACON_DATA)
          .execute(
            Tuple.of(
              closestItem.getString("beacon"),
              existingBeaconData.getInteger("battery"),
              existingBeaconData.getString("beaconStatus"),
              42,
              -8,
              existingBeaconData.getInteger("floor"),
              existingBeaconData.getDouble("temperature")
            )
          ).await()

        pgClient.preparedQuery(INSERT_BEACON_DATA)
          .execute(
            Tuple.of(
              "fake1",
              existingBeaconData.getInteger("battery"),
              existingBeaconData.getString("beaconStatus"),
              44,
              -8,
              existingBeaconData.getInteger("floor"),
              existingBeaconData.getDouble("temperature")
            )
          ).await()

        pgClient.preparedQuery(INSERT_BEACON_DATA)
          .execute(
            Tuple.of(
              "fake2",
              existingBeaconData.getInteger("battery"),
              existingBeaconData.getString("beaconStatus"),
              45,
              -8,
              existingBeaconData.getInteger("floor"),
              existingBeaconData.getDouble("temperature")
            )
          ).await()

        pgClient.preparedQuery(INSERT_BEACON_DATA)
          .execute(
            Tuple.of(
              "fake3",
              existingBeaconData.getInteger("battery"),
              existingBeaconData.getString("beaconStatus"),
              46,
              -8,
              existingBeaconData.getInteger("floor"),
              existingBeaconData.getDouble("temperature")
            )
          ).await()

        pgClient.preparedQuery(INSERT_BEACON_DATA)
          .execute(
            Tuple.of(
              "fake4",
              existingBeaconData.getInteger("battery"),
              existingBeaconData.getString("beaconStatus"),
              47,
              -8,
              existingBeaconData.getInteger("floor"),
              existingBeaconData.getDouble("temperature")
            )
          ).await()

        return pgClient.preparedQuery(INSERT_BEACON_DATA)
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
  fun cleanup(vertx: Vertx, testContext: VertxTestContext) = runBlocking(vertx.dispatcher()) {
    try {
      dropAllItems().await()
      pgClient.close()
      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  @DisplayName("registerItem correctly registers a new item")
  fun registerIsCorrect(testContext: VertxTestContext) {
    val newItem = jsonObjectOf(
      "beacon" to "aa:aa:aa:aa:aa:aa",
      "category" to "Lit",
      "service" to "Bloc 1",
      "itemID" to "ee",
      "brand" to "oo",
      "model" to "aa",
      "supplier" to "uu",
      "purchaseDate" to LocalDate.of(2019, 3, 19).toString(),
      "purchasePrice" to 102.12,
      "originLocation" to "or",
      "currentLocation" to "cur",
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
  @DisplayName("registerItem without specifying the status correctly registers a new item with the status set to 'Under creation'")
  fun registerWithoutSpecifyingStatusIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "category" to "Lit",
        "service" to "Bloc 1",
        "itemID" to "ee",
        "brand" to "oo",
        "model" to "aa",
        "supplier" to "uu",
        "purchaseDate" to LocalDate.of(2019, 3, 19).toString(),
        "purchasePrice" to 102.12,
        "originLocation" to "or",
        "currentLocation" to "cur",
        "room" to "616",
        "contact" to "Monsieur Poirot",
        "currentOwner" to "Monsieur Dupont",
        "previousOwner" to "Monsieur Dupond",
        "orderNumber" to "abcdf",
        "color" to "red",
        "serialNumber" to "abcdf",
        "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
        "comments" to "A comment",
        "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
        "lastModifiedBy" to "Monsieur Duport"
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
      }

      val id = response.toInt()

      try {
        val res = pgClient.preparedQuery(getItem("items", "beacon_data")).execute(Tuple.of(id)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(newItem.getString("beacon"))
          that(json.getString("category")).isEqualTo(newItem.getString("category"))
          that(json.getString("service")).isEqualTo(newItem.getString("service"))
          that(json.getString("itemID")).isEqualTo(newItem.getString("itemID"))
          that(json.getString("brand")).isEqualTo(newItem.getString("brand"))
          that(json.getString("model")).isEqualTo(newItem.getString("model"))
          that(json.getString("supplier")).isEqualTo(newItem.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(newItem.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(newItem.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(newItem.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(newItem.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(newItem.getString("room"))
          that(json.getString("contact")).isEqualTo(newItem.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(newItem.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(newItem.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(newItem.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(newItem.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(newItem.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(newItem.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo("Under creation")
          that(json.getString("comments")).isEqualTo(newItem.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(newItem.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(newItem.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isNull()
          that(json.getString("beaconStatus")).isNull()
          that(json.getDouble("latitude")).isNull()
          that(json.getDouble("longitude")).isNull()
          that(json.getInteger("floor")).isNull()
          that(json.getDouble("temperature")).isNull()
        }
        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("registerItem correctly registers a new item with a custom id")
  fun registerWithIdIsCorrect(testContext: VertxTestContext) {
    val newItem = jsonObjectOf(
      "id" to 120,
      "beacon" to "aa:aa:aa:aa:aa:aa",
      "category" to "Lit",
      "service" to "Bloc 1",
      "itemID" to "ee",
      "brand" to "oo",
      "model" to "aa",
      "supplier" to "uu",
      "purchaseDate" to LocalDate.of(2019, 3, 19).toString(),
      "purchasePrice" to 102.12,
      "originLocation" to "or",
      "currentLocation" to "cur",
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
      expectThat(response.toInt()).isEqualTo(newItem.getInteger("id")) // the response returns the id of the registered item
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
    pgClient.query(
      """CREATE TABLE IF NOT EXISTS items_another
(
    id SERIAL PRIMARY KEY,
    beacon VARCHAR(17) UNIQUE,
    category VARCHAR(100),
    service VARCHAR(100),
    itemID VARCHAR(50),
    accessControlString VARCHAR(2048),
    brand VARCHAR(100),
    model VARCHAR(100),
    supplier VARCHAR(100),
    purchaseDate DATE,
    purchasePrice DECIMAL(15, 6),
    originLocation VARCHAR(100),
    currentLocation VARCHAR(100),
    room VARCHAR(100),
    contact VARCHAR(100),
    currentOwner VARCHAR(100),
    previousOwner VARCHAR(100),
    orderNumber VARCHAR(100),
    color VARCHAR(100),
    serialNumber VARCHAR(100),
    maintenanceDate DATE,
    status VARCHAR(100),
    comments VARCHAR(100),
    lastModifiedDate DATE,
    lastModifiedBy VARCHAR(100)
);"""
    ).execute().compose {
      pgClient.query(
        """CREATE TABLE IF NOT EXISTS beacon_data_another
(
    time TIMESTAMPTZ NOT NULL,
    mac VARCHAR(17) NOT NULL,
    battery INTEGER,
    beaconStatus VARCHAR(50),
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
      put("beaconStatus", existingBeaconData.getString("beaconStatus"))
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
  @DisplayName("getItem returns not found on non existing item")
  fun getItemReturnsNotFoundOnNonExistingItem(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      get("/items/100")
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
  @DisplayName("updateItem correctly updates the desired item")
  fun updateItemIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
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

    try {
      val res = pgClient.preparedQuery(getItem("items", "beacon_data")).execute(Tuple.of(existingItemID)).await()
      val json = res.iterator().next().toItemJson()
      expect {
        that(json.getString("beacon")).isEqualTo(updateItemJson.getString("beacon"))
        that(json.getString("category")).isEqualTo(updateItemJson.getString("category"))
        that(json.getString("service")).isEqualTo(updateItemJson.getString("service"))
        that(json.getString("itemID")).isEqualTo(updateItemJson.getString("itemID"))
        that(json.getString("brand")).isEqualTo(updateItemJson.getString("brand"))
        that(json.getString("model")).isEqualTo(updateItemJson.getString("model"))
        that(json.getString("supplier")).isEqualTo(updateItemJson.getString("supplier"))
        that(json.getString("purchaseDate")).isEqualTo(updateItemJson.getString("purchaseDate"))
        that(json.getDouble("purchasePrice")).isEqualTo(updateItemJson.getDouble("purchasePrice"))
        that(json.getString("originLocation")).isEqualTo(updateItemJson.getString("originLocation"))
        that(json.getString("currentLocation")).isEqualTo(updateItemJson.getString("currentLocation"))
        that(json.getString("room")).isEqualTo(updateItemJson.getString("room"))
        that(json.getString("contact")).isEqualTo(updateItemJson.getString("contact"))
        that(json.getString("currentOwner")).isEqualTo(updateItemJson.getString("currentOwner"))
        that(json.getString("previousOwner")).isEqualTo(updateItemJson.getString("previousOwner"))
        that(json.getString("orderNumber")).isEqualTo(updateItemJson.getString("orderNumber"))
        that(json.getString("color")).isEqualTo(updateItemJson.getString("color"))
        that(json.getString("serialNumber")).isEqualTo(updateItemJson.getString("serialNumber"))
        that(json.getString("maintenanceDate")).isEqualTo(updateItemJson.getString("maintenanceDate"))
        that(json.getString("status")).isEqualTo(updateItemJson.getString("status"))
        that(json.getString("comments")).isEqualTo(updateItemJson.getString("comments"))
        that(json.getString("lastModifiedDate")).isEqualTo(updateItemJson.getString("lastModifiedDate"))
        that(json.getString("lastModifiedBy")).isEqualTo(updateItemJson.getString("lastModifiedBy"))
        that(json.getInteger("battery")).isEqualTo(existingBeaconData.getInteger("battery"))
        that(json.getString("beaconStatus")).isEqualTo(existingBeaconData.getString("beaconStatus"))
        that(json.getDouble("latitude")).isEqualTo(existingBeaconData.getDouble("latitude"))
        that(json.getDouble("longitude")).isEqualTo(existingBeaconData.getDouble("longitude"))
        that(json.getInteger("floor")).isEqualTo(existingBeaconData.getInteger("floor"))
        that(json.getDouble("temperature")).isEqualTo(existingBeaconData.getDouble("temperature"))
      }
      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  @DisplayName("updateItem only updates the specified fields")
  fun updateItemOnlyUpdatesSpecifiedFields(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val updateJson = jsonObjectOf(
        "service" to "A new service"
      )
      val response = Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
        body(updateJson.encode())
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

      try {
        val res = pgClient.preparedQuery(getItem("items", "beacon_data")).execute(Tuple.of(existingItemID)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(existingItem.getString("beacon"))
          that(json.getString("category")).isEqualTo(existingItem.getString("category"))
          that(json.getString("service")).isEqualTo(updateJson.getString("service"))
          that(json.getString("itemID")).isEqualTo(existingItem.getString("itemID"))
          that(json.getString("brand")).isEqualTo(existingItem.getString("brand"))
          that(json.getString("model")).isEqualTo(existingItem.getString("model"))
          that(json.getString("supplier")).isEqualTo(existingItem.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(existingItem.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(existingItem.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(existingItem.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(existingItem.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(existingItem.getString("room"))
          that(json.getString("contact")).isEqualTo(existingItem.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(existingItem.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(existingItem.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(existingItem.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(existingItem.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(existingItem.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(existingItem.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo(existingItem.getString("status"))
          that(json.getString("comments")).isEqualTo(existingItem.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(existingItem.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(existingItem.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isEqualTo(existingBeaconData.getInteger("battery"))
          that(json.getString("beaconStatus")).isEqualTo(existingBeaconData.getString("beaconStatus"))
          that(json.getDouble("latitude")).isEqualTo(existingBeaconData.getDouble("latitude"))
          that(json.getDouble("longitude")).isEqualTo(existingBeaconData.getDouble("longitude"))
          that(json.getInteger("floor")).isEqualTo(existingBeaconData.getInteger("floor"))
          that(json.getDouble("temperature")).isEqualTo(existingBeaconData.getDouble("temperature"))
        }
        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("updateItem correctly updates the desired item when it is under creation to status 'Created'")
  fun updateItemUnderCreationIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val updateJson = updateItemJson.copy().put("status", "Under creation")

      val response = Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
        body(updateJson.encode())
      } When {
        queryParam("company", "biot")
        queryParam("scan", true)
        put("/items/$existingItemID")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isEmpty()
      }

      try {
        val res = pgClient.preparedQuery(getItem("items", "beacon_data")).execute(Tuple.of(existingItemID)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(updateJson.getString("beacon"))
          that(json.getString("category")).isEqualTo(updateJson.getString("category"))
          that(json.getString("service")).isEqualTo(updateJson.getString("service"))
          that(json.getString("itemID")).isEqualTo(updateJson.getString("itemID"))
          that(json.getString("brand")).isEqualTo(updateJson.getString("brand"))
          that(json.getString("model")).isEqualTo(updateJson.getString("model"))
          that(json.getString("supplier")).isEqualTo(updateJson.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(updateJson.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(updateJson.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(updateJson.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(updateJson.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(updateJson.getString("room"))
          that(json.getString("contact")).isEqualTo(updateJson.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(updateJson.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(updateJson.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(updateJson.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(updateJson.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(updateJson.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(updateJson.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo("Created")
          that(json.getString("comments")).isEqualTo(updateJson.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(updateJson.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(updateJson.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isEqualTo(existingBeaconData.getInteger("battery"))
          that(json.getString("beaconStatus")).isEqualTo(existingBeaconData.getString("beaconStatus"))
          that(json.getDouble("latitude")).isEqualTo(existingBeaconData.getDouble("latitude"))
          that(json.getDouble("longitude")).isEqualTo(existingBeaconData.getDouble("longitude"))
          that(json.getInteger("floor")).isEqualTo(existingBeaconData.getInteger("floor"))
          that(json.getDouble("temperature")).isEqualTo(existingBeaconData.getDouble("temperature"))
        }
        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("The bad request error handler works on wrong body received")
  fun badRequestErrorHandlerWorksOnWrongBodyReceived(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(jsonObjectOf("category" to 12).encode())
    } When {
      queryParam("company", "biot")
      put("/items/$existingItemID")
    } Then {
      statusCode(BAD_REQUEST_CODE)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went while parsing/validating the body.")
      testContext.completeNow()
    }
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

  @Test
  @DisplayName("Item updates on POST operations are correctly received on the event bus")
  fun receivePostUpdatesOnEventBus(vertx: Vertx, testContext: VertxTestContext) {
    val itemId = 100
    val newItem = jsonObjectOf(
      "id" to itemId,
      "category" to "ECG"
    )
    val expectedType = UpdateType.POST.toString()

    vertx.eventBus().consumer<JsonObject>(ITEMS_UPDATES_ADDRESS) { msg ->
      val body = msg.body()
      testContext.verify {
        expectThat(body.getString("type")).isEqualTo(expectedType)
        expectThat(body.getInteger("id")).isEqualTo(itemId)
        expectThat(body.getJsonObject("content").getString("category")).isEqualTo(newItem.getString("category"))
        testContext.completeNow()
      }
    }

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      queryParam("company", "biot")
      post("/items")
    } Then {
      statusCode(200)
    }
  }

  @Test
  @DisplayName("Item updates on PUT operations are correctly received on the event bus")
  fun receivePutUpdatesOnEventBus(vertx: Vertx, testContext: VertxTestContext) {
    val itemId = 100
    val newItem = jsonObjectOf(
      "id" to itemId,
      "category" to "ECG"
    )
    val updateItem = jsonObjectOf(
      "category" to "Lit"
    )
    val expectedType = UpdateType.PUT.toString()

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      queryParam("company", "biot")
      post("/items")
    } Then {
      statusCode(200)
    }

    vertx.eventBus().consumer<JsonObject>(ITEMS_UPDATES_ADDRESS) { msg ->
      val body = msg.body()
      testContext.verify {
        expectThat(body.getString("type")).isEqualTo(expectedType)
        expectThat(body.getInteger("id")).isEqualTo(itemId)
        expectThat(body.getJsonObject("content").getString("category")).isEqualTo(updateItem.getString("category"))
        testContext.completeNow()
      }
    }

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(updateItem.encode())
    } When {
      queryParam("company", "biot")
      put("/items/$itemId")
    } Then {
      statusCode(200)
    }
  }

  @Test
  @DisplayName("Item updates on DELETE operations are correctly received on the event bus")
  fun receiveDeleteUpdatesOnEventBus(vertx: Vertx, testContext: VertxTestContext) {
    val itemId = 100
    val newItem = jsonObjectOf(
      "id" to itemId,
      "category" to "ECG"
    )
    val expectedType = UpdateType.DELETE.toString()

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      queryParam("company", "biot")
      post("/items")
    } Then {
      statusCode(200)
    }

    vertx.eventBus().consumer<JsonObject>(ITEMS_UPDATES_ADDRESS) { msg ->
      val body = msg.body()
      testContext.verify {
        expectThat(body.getString("type")).isEqualTo(expectedType)
        expectThat(body.getInteger("id")).isEqualTo(itemId)
        testContext.completeNow()
      }
    }

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      delete("/items/$itemId")
    } Then {
      statusCode(200)
    }
  }

  @Test
  @DisplayName("registerItem without specifying the accessControlString correctly registers a new item with " +
    "the accessControlString set to '<company>' (here 'biot')")
  fun registerWithoutSpecifyingACStringIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "category" to "Lit",
        "service" to "Bloc 1",
        "itemID" to "ee",
        "brand" to "oo",
        "model" to "aa",
        "supplier" to "uu",
        "purchaseDate" to LocalDate.of(2019, 3, 19).toString(),
        "purchasePrice" to 102.12,
        "originLocation" to "or",
        "currentLocation" to "cur",
        "room" to "616",
        "contact" to "Monsieur Poirot",
        "currentOwner" to "Monsieur Dupont",
        "previousOwner" to "Monsieur Dupond",
        "orderNumber" to "abcdf",
        "color" to "red",
        "serialNumber" to "abcdf",
        "status" to "Status",
        "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
        "comments" to "A comment",
        "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
        "lastModifiedBy" to "Monsieur Duport"
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
      }

      val id = response.toInt()

      try {
        val res = pgClient.preparedQuery(getItem("items", "beacon_data")).execute(Tuple.of(id)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(newItem.getString("beacon"))
          that(json.getString("category")).isEqualTo(newItem.getString("category"))
          that(json.getString("service")).isEqualTo(newItem.getString("service"))
          that(json.getString("itemID")).isEqualTo(newItem.getString("itemID"))
          that(json.getString("accessControlString")).isEqualTo("biot")
          that(json.getString("brand")).isEqualTo(newItem.getString("brand"))
          that(json.getString("model")).isEqualTo(newItem.getString("model"))
          that(json.getString("supplier")).isEqualTo(newItem.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(newItem.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(newItem.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(newItem.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(newItem.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(newItem.getString("room"))
          that(json.getString("contact")).isEqualTo(newItem.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(newItem.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(newItem.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(newItem.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(newItem.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(newItem.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(newItem.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo(newItem.getString("status"))
          that(json.getString("comments")).isEqualTo(newItem.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(newItem.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(newItem.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isNull()
          that(json.getString("beaconStatus")).isNull()
          that(json.getDouble("latitude")).isNull()
          that(json.getDouble("longitude")).isNull()
          that(json.getInteger("floor")).isNull()
          that(json.getDouble("temperature")).isNull()
        }
        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("registerItem by specifying the accessControlString wrongly registers a new item with " +
    "the accessControlString set to '<company>' (here 'biot') 1")
  fun registerSpecifyingACStringWronglyIsCorrect1(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "category" to "Lit",
        "service" to "Bloc 1",
        "itemID" to "ee",
        "accessControlString" to "grpajfo",
        "brand" to "oo",
        "model" to "aa",
        "supplier" to "uu",
        "purchaseDate" to LocalDate.of(2019, 3, 19).toString(),
        "purchasePrice" to 102.12,
        "originLocation" to "or",
        "currentLocation" to "cur",
        "room" to "616",
        "contact" to "Monsieur Poirot",
        "currentOwner" to "Monsieur Dupont",
        "previousOwner" to "Monsieur Dupond",
        "orderNumber" to "abcdf",
        "color" to "red",
        "serialNumber" to "abcdf",
        "status" to "Status",
        "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
        "comments" to "A comment",
        "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
        "lastModifiedBy" to "Monsieur Duport"
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
      }

      val id = response.toInt()

      try {
        val res = pgClient.preparedQuery(getItem("items", "beacon_data")).execute(Tuple.of(id)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(newItem.getString("beacon"))
          that(json.getString("category")).isEqualTo(newItem.getString("category"))
          that(json.getString("service")).isEqualTo(newItem.getString("service"))
          that(json.getString("itemID")).isEqualTo(newItem.getString("itemID"))
          that(json.getString("accessControlString")).isEqualTo("biot")
          that(json.getString("brand")).isEqualTo(newItem.getString("brand"))
          that(json.getString("model")).isEqualTo(newItem.getString("model"))
          that(json.getString("supplier")).isEqualTo(newItem.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(newItem.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(newItem.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(newItem.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(newItem.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(newItem.getString("room"))
          that(json.getString("contact")).isEqualTo(newItem.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(newItem.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(newItem.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(newItem.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(newItem.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(newItem.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(newItem.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo(newItem.getString("status"))
          that(json.getString("comments")).isEqualTo(newItem.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(newItem.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(newItem.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isNull()
          that(json.getString("beaconStatus")).isNull()
          that(json.getDouble("latitude")).isNull()
          that(json.getDouble("longitude")).isNull()
          that(json.getInteger("floor")).isNull()
          that(json.getDouble("temperature")).isNull()
        }
        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("registerItem by specifying the accessControlString wrongly registers a new item with " +
    "the accessControlString set to '<company>' (here 'biot') 2")
  fun registerSpecifyingACStringWronglyIsCorrect2(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "category" to "Lit",
        "service" to "Bloc 1",
        "itemID" to "ee",
        "accessControlString" to "biot21:fdas",
        "brand" to "oo",
        "model" to "aa",
        "supplier" to "uu",
        "purchaseDate" to LocalDate.of(2019, 3, 19).toString(),
        "purchasePrice" to 102.12,
        "originLocation" to "or",
        "currentLocation" to "cur",
        "room" to "616",
        "contact" to "Monsieur Poirot",
        "currentOwner" to "Monsieur Dupont",
        "previousOwner" to "Monsieur Dupond",
        "orderNumber" to "abcdf",
        "color" to "red",
        "serialNumber" to "abcdf",
        "status" to "Status",
        "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
        "comments" to "A comment",
        "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
        "lastModifiedBy" to "Monsieur Duport"
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
      }

      val id = response.toInt()

      try {
        val res = pgClient.preparedQuery(getItem("items", "beacon_data")).execute(Tuple.of(id)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(newItem.getString("beacon"))
          that(json.getString("category")).isEqualTo(newItem.getString("category"))
          that(json.getString("service")).isEqualTo(newItem.getString("service"))
          that(json.getString("itemID")).isEqualTo(newItem.getString("itemID"))
          that(json.getString("accessControlString")).isEqualTo("biot")
          that(json.getString("brand")).isEqualTo(newItem.getString("brand"))
          that(json.getString("model")).isEqualTo(newItem.getString("model"))
          that(json.getString("supplier")).isEqualTo(newItem.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(newItem.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(newItem.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(newItem.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(newItem.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(newItem.getString("room"))
          that(json.getString("contact")).isEqualTo(newItem.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(newItem.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(newItem.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(newItem.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(newItem.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(newItem.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(newItem.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo(newItem.getString("status"))
          that(json.getString("comments")).isEqualTo(newItem.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(newItem.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(newItem.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isNull()
          that(json.getString("beaconStatus")).isNull()
          that(json.getDouble("latitude")).isNull()
          that(json.getDouble("longitude")).isNull()
          that(json.getInteger("floor")).isNull()
          that(json.getDouble("temperature")).isNull()
        }
        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("registerItem by specifying the accessControlString wrongly registers a new item with " +
    "the accessControlString set to '<company>' (here 'biot') 3")
  fun registerSpecifyingACStringWronglyIsCorrect3(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "category" to "Lit",
        "service" to "Bloc 1",
        "itemID" to "ee",
        "accessControlString" to "",
        "brand" to "oo",
        "model" to "aa",
        "supplier" to "uu",
        "purchaseDate" to LocalDate.of(2019, 3, 19).toString(),
        "purchasePrice" to 102.12,
        "originLocation" to "or",
        "currentLocation" to "cur",
        "room" to "616",
        "contact" to "Monsieur Poirot",
        "currentOwner" to "Monsieur Dupont",
        "previousOwner" to "Monsieur Dupond",
        "orderNumber" to "abcdf",
        "color" to "red",
        "serialNumber" to "abcdf",
        "status" to "Status",
        "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
        "comments" to "A comment",
        "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
        "lastModifiedBy" to "Monsieur Duport"
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
      }

      val id = response.toInt()

      try {
        val res = pgClient.preparedQuery(getItem("items", "beacon_data")).execute(Tuple.of(id)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(newItem.getString("beacon"))
          that(json.getString("category")).isEqualTo(newItem.getString("category"))
          that(json.getString("service")).isEqualTo(newItem.getString("service"))
          that(json.getString("itemID")).isEqualTo(newItem.getString("itemID"))
          that(json.getString("accessControlString")).isEqualTo("biot")
          that(json.getString("brand")).isEqualTo(newItem.getString("brand"))
          that(json.getString("model")).isEqualTo(newItem.getString("model"))
          that(json.getString("supplier")).isEqualTo(newItem.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(newItem.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(newItem.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(newItem.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(newItem.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(newItem.getString("room"))
          that(json.getString("contact")).isEqualTo(newItem.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(newItem.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(newItem.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(newItem.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(newItem.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(newItem.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(newItem.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo(newItem.getString("status"))
          that(json.getString("comments")).isEqualTo(newItem.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(newItem.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(newItem.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isNull()
          that(json.getString("beaconStatus")).isNull()
          that(json.getDouble("latitude")).isNull()
          that(json.getDouble("longitude")).isNull()
          that(json.getInteger("floor")).isNull()
          that(json.getDouble("temperature")).isNull()
        }
        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("registerItem by specifying the accessControlString correctly registers a new item with " +
    "the correct accessControlString")
  fun registerSpecifyingACStringCorrectlyIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "category" to "Lit",
        "service" to "Bloc 1",
        "itemID" to "ee",
        "accessControlString" to "biot:grp1:grp2",
        "brand" to "oo",
        "model" to "aa",
        "supplier" to "uu",
        "purchaseDate" to LocalDate.of(2019, 3, 19).toString(),
        "purchasePrice" to 102.12,
        "originLocation" to "or",
        "currentLocation" to "cur",
        "room" to "616",
        "contact" to "Monsieur Poirot",
        "currentOwner" to "Monsieur Dupont",
        "previousOwner" to "Monsieur Dupond",
        "orderNumber" to "abcdf",
        "color" to "red",
        "serialNumber" to "abcdf",
        "status" to "Status",
        "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
        "comments" to "A comment",
        "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
        "lastModifiedBy" to "Monsieur Duport"
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
      }

      val id = response.toInt()

      try {
        val res = pgClient.preparedQuery(getItem("items", "beacon_data")).execute(Tuple.of(id)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(newItem.getString("beacon"))
          that(json.getString("category")).isEqualTo(newItem.getString("category"))
          that(json.getString("service")).isEqualTo(newItem.getString("service"))
          that(json.getString("itemID")).isEqualTo(newItem.getString("itemID"))
          that(json.getString("accessControlString")).isEqualTo(newItem.getString("accessControlString"))
          that(json.getString("brand")).isEqualTo(newItem.getString("brand"))
          that(json.getString("model")).isEqualTo(newItem.getString("model"))
          that(json.getString("supplier")).isEqualTo(newItem.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(newItem.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(newItem.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(newItem.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(newItem.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(newItem.getString("room"))
          that(json.getString("contact")).isEqualTo(newItem.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(newItem.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(newItem.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(newItem.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(newItem.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(newItem.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(newItem.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo(newItem.getString("status"))
          that(json.getString("comments")).isEqualTo(newItem.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(newItem.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(newItem.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isNull()
          that(json.getString("beaconStatus")).isNull()
          that(json.getDouble("latitude")).isNull()
          that(json.getDouble("longitude")).isNull()
          that(json.getInteger("floor")).isNull()
          that(json.getDouble("temperature")).isNull()
        }
        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  companion object {

    private const val ITEMS_UPDATES_ADDRESS = "items.updates.biot"

    private const val INSERT_BEACON_DATA =
      "INSERT INTO beacon_data(time, mac, battery, beaconstatus, latitude, longitude, floor, temperature) values(NOW(), $1, $2, $3, $4, $5, $6, $7)"

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
