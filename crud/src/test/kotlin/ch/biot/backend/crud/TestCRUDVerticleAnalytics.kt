/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud

import ch.biot.backend.crud.queries.insertItem
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
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.io.File
import java.time.LocalDate

@ExtendWith(VertxExtension::class)
@Testcontainers
class TestCRUDVerticleAnalytics {

  private lateinit var pgClient: SqlClient

  private val existingItemOne = jsonObjectOf(
    "beacon" to "ad:ab:ab:ab:ab:ab",
    "category" to "Lit",
    "service" to "Bloc 42",
    "itemID" to "new",
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
  private val existingBeaconDataOne = jsonObjectOf(
    "mac" to existingItemOne.getString("beacon"),
    "battery" to 50,
    "beaconStatus" to "available",
    "latitude" to 2.333333,
    "longitude" to -2.333333,
    "floor" to 1,
    "temperature" to 25.2
  )

  private val existingItemTwo = jsonObjectOf(
    "beacon" to "bb:ab:ab:ab:ab:ab",
    "category" to "ECG",
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
  private val existingBeaconDataTwo = jsonObjectOf(
    "mac" to existingItemTwo.getString("beacon"),
    "battery" to 50,
    "beaconStatus" to "unavailable",
    "latitude" to 2.333333,
    "longitude" to -2.333333,
    "floor" to 1,
    "temperature" to 25.2
  )

  private val existingItemThree = jsonObjectOf(
    "beacon" to "cb:ab:ab:ab:ab:ab",
    "category" to "ECG",
    "service" to "Bloc 2",
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
  private val existingBeaconDataThree = jsonObjectOf(
    "mac" to existingItemThree.getString("beacon"),
    "battery" to 50,
    "beaconStatus" to "toRepair",
    "latitude" to 2.333333,
    "longitude" to -2.333333,
    "floor" to 1,
    "temperature" to 25.2
  )

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext) = runBlocking(vertx.dispatcher()) {
    val pgConnectOptions =
      pgConnectOptionsOf(
        port = CRUDVerticle.TIMESCALE_PORT,
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
    pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          existingItemOne["beacon"],
          existingItemOne["category"],
          existingItemOne["service"],
          existingItemOne["itemID"],
          existingItemOne["brand"],
          existingItemOne["model"],
          existingItemOne["supplier"],
          LocalDate.parse(existingItemOne["purchaseDate"]),
          existingItemOne["purchasePrice"],
          existingItemOne["originLocation"],
          existingItemOne["currentLocation"],
          existingItemOne["room"],
          existingItemOne["contact"],
          existingItemOne["currentOwner"],
          existingItemOne["previousOwner"],
          existingItemOne["orderNumber"],
          existingItemOne["color"],
          existingItemOne["serialNumber"],
          LocalDate.parse(existingItemOne["maintenanceDate"]),
          existingItemOne["status"],
          existingItemOne["comments"],
          LocalDate.parse(existingItemOne["lastModifiedDate"]),
          existingItemOne["lastModifiedBy"]
        )
      ).await()

    pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          existingItemTwo["beacon"],
          existingItemTwo["category"],
          existingItemTwo["service"],
          existingItemTwo["itemID"],
          existingItemTwo["brand"],
          existingItemTwo["model"],
          existingItemTwo["supplier"],
          LocalDate.parse(existingItemTwo["purchaseDate"]),
          existingItemTwo["purchasePrice"],
          existingItemTwo["originLocation"],
          existingItemTwo["currentLocation"],
          existingItemTwo["room"],
          existingItemTwo["contact"],
          existingItemTwo["currentOwner"],
          existingItemTwo["previousOwner"],
          existingItemTwo["orderNumber"],
          existingItemTwo["color"],
          existingItemTwo["serialNumber"],
          LocalDate.parse(existingItemTwo["maintenanceDate"]),
          existingItemTwo["status"],
          existingItemTwo["comments"],
          LocalDate.parse(existingItemTwo["lastModifiedDate"]),
          existingItemTwo["lastModifiedBy"]
        )
      ).await()

    pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          existingItemThree["beacon"],
          existingItemThree["category"],
          existingItemThree["service"],
          existingItemThree["itemID"],
          existingItemThree["brand"],
          existingItemThree["model"],
          existingItemThree["supplier"],
          LocalDate.parse(existingItemThree["purchaseDate"]),
          existingItemThree["purchasePrice"],
          existingItemThree["originLocation"],
          existingItemThree["currentLocation"],
          existingItemThree["room"],
          existingItemThree["contact"],
          existingItemThree["currentOwner"],
          existingItemThree["previousOwner"],
          existingItemThree["orderNumber"],
          existingItemThree["color"],
          existingItemThree["serialNumber"],
          LocalDate.parse(existingItemThree["maintenanceDate"]),
          existingItemThree["status"],
          existingItemThree["comments"],
          LocalDate.parse(existingItemThree["lastModifiedDate"]),
          existingItemThree["lastModifiedBy"]
        )
      ).await()

    pgClient.preparedQuery(INSERT_BEACON_DATA).execute(
      Tuple.of(
        existingBeaconDataOne.getString("mac"),
        existingBeaconDataOne.getInteger("battery"),
        existingBeaconDataOne.getString("beaconStatus"),
        existingBeaconDataOne.getDouble("latitude"),
        existingBeaconDataOne.getDouble("longitude"),
        existingBeaconDataOne.getInteger("floor"),
        existingBeaconDataOne.getDouble("temperature")
      )
    ).await()

    pgClient.preparedQuery(INSERT_BEACON_DATA).execute(
      Tuple.of(
        existingBeaconDataTwo.getString("mac"),
        existingBeaconDataTwo.getInteger("battery"),
        existingBeaconDataTwo.getString("beaconStatus"),
        existingBeaconDataTwo.getDouble("latitude"),
        existingBeaconDataTwo.getDouble("longitude"),
        existingBeaconDataTwo.getInteger("floor"),
        existingBeaconDataTwo.getDouble("temperature")
      )
    ).await()

    return pgClient.preparedQuery(INSERT_BEACON_DATA).execute(
      Tuple.of(
        existingBeaconDataThree.getString("mac"),
        existingBeaconDataThree.getInteger("battery"),
        existingBeaconDataThree.getString("beaconStatus"),
        existingBeaconDataThree.getDouble("latitude"),
        existingBeaconDataThree.getDouble("longitude"),
        existingBeaconDataThree.getInteger("floor"),
        existingBeaconDataThree.getDouble("temperature")
      )
    )
  }

  @AfterEach
  fun cleanup(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    try {
      dropAllItems().await()
      pgClient.close().await()
      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  @DisplayName("getStatus retrieves the items' status for each service")
  fun getStatusIsCorrect(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
      } When {
        queryParam("company", "biot")
        get("/analytics/status")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response.containsKey(existingItemOne.getString("service"))).isTrue()
      expectThat(response.containsKey(existingItemTwo.getString("service"))).isTrue()
      expectThat(response.containsKey(existingItemThree.getString("service"))).isTrue()

      val firstService = response.getJsonObject(existingItemOne.getString("service"))
      expect {
        that(firstService.getInteger("available")).isEqualTo(1)
        that(firstService.getInteger("unavailable")).isEqualTo(1)
        that(firstService.getInteger("toRepair")).isEqualTo(0)
      }

      val secondService = response.getJsonObject(existingItemThree.getString("service"))
      expect {
        that(secondService.getInteger("available")).isEqualTo(0)
        that(secondService.getInteger("unavailable")).isEqualTo(0)
        that(secondService.getInteger("toRepair")).isEqualTo(1)
      }

      testContext.completeNow()
    }
  }

  companion object {

    private const val INSERT_BEACON_DATA =
      "INSERT INTO beacon_data(time, mac, battery, beaconstatus, latitude, longitude, floor, temperature) values(NOW(), $1, $2, $3, $4, $5, $6, $7)"

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
