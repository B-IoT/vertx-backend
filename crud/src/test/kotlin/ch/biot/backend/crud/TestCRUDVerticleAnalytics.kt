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
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
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
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.io.File

@ExtendWith(VertxExtension::class)
@Testcontainers
class TestCRUDVerticleAnalytics {

  private lateinit var pgPool: PgPool

  private val existingItemOne = jsonObjectOf(
    "beacon" to "ab:ab:ab:ab:ab:ab",
    "category" to "ECG",
    "service" to "Bloc 1"
  )
  private val existingBeaconDataOne = jsonObjectOf(
    "mac" to existingItemOne.getString("beacon"),
    "battery" to 50,
    "status" to "available",
    "latitude" to 2.333333,
    "longitude" to -2.333333,
    "floor" to 1
  )

  private val existingItemTwo = jsonObjectOf(
    "beacon" to "bb:ab:ab:ab:ab:ab",
    "category" to "ECG",
    "service" to "Bloc 1"
  )
  private val existingBeaconDataTwo = jsonObjectOf(
    "mac" to existingItemTwo.getString("beacon"),
    "battery" to 50,
    "status" to "unavailable",
    "latitude" to 2.333333,
    "longitude" to -2.333333,
    "floor" to 1
  )

  private val existingItemThree = jsonObjectOf(
    "beacon" to "cb:ab:ab:ab:ab:ab",
    "category" to "ECG",
    "service" to "Bloc 2"
  )
  private val existingBeaconDataThree = jsonObjectOf(
    "mac" to existingItemThree.getString("beacon"),
    "battery" to 50,
    "status" to "toRepair",
    "latitude" to 2.333333,
    "longitude" to -2.333333,
    "floor" to 1
  )

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext) {
    val pgConnectOptions =
      pgConnectOptionsOf(
        port = CRUDVerticle.TIMESCALE_PORT,
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
    .execute(Tuple.of(existingItemOne["beacon"], existingItemOne["category"], existingItemOne["service"]))
    .compose {
      pgPool.preparedQuery(insertItem("items"))
        .execute(Tuple.of(existingItemTwo["beacon"], existingItemTwo["category"], existingItemTwo["service"]))
    }.compose {
      pgPool.preparedQuery(insertItem("items"))
        .execute(Tuple.of(existingItemThree["beacon"], existingItemThree["category"], existingItemThree["service"]))
    }.compose {
      pgPool.preparedQuery(INSERT_BEACON_DATA).execute(
        Tuple.of(
          existingBeaconDataOne.getString("mac"),
          existingBeaconDataOne.getInteger("battery"),
          existingBeaconDataOne.getString("status"),
          existingBeaconDataOne.getDouble("latitude"),
          existingBeaconDataOne.getDouble("longitude"),
          existingBeaconDataOne.getInteger("floor")
        )
      ).compose {
        pgPool.preparedQuery(INSERT_BEACON_DATA).execute(
          Tuple.of(
            existingBeaconDataTwo.getString("mac"),
            existingBeaconDataTwo.getInteger("battery"),
            existingBeaconDataTwo.getString("status"),
            existingBeaconDataTwo.getDouble("latitude"),
            existingBeaconDataTwo.getDouble("longitude"),
            existingBeaconDataTwo.getInteger("floor")
          )
        )
      }.compose {
        pgPool.preparedQuery(INSERT_BEACON_DATA).execute(
          Tuple.of(
            existingBeaconDataThree.getString("mac"),
            existingBeaconDataThree.getInteger("battery"),
            existingBeaconDataThree.getString("status"),
            existingBeaconDataThree.getDouble("latitude"),
            existingBeaconDataThree.getDouble("longitude"),
            existingBeaconDataThree.getInteger("floor")
          )
        )
      }
    }

  @AfterEach
  fun cleanup(testContext: VertxTestContext) {
    dropAllItems().compose {
      pgPool.close()
    }.onSuccess { testContext.completeNow() }
      .onFailure(testContext::failNow)
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
      "INSERT INTO beacon_data(time, mac, battery, status, latitude, longitude, floor) values(NOW(), $1, $2, $3, $4, $5, $6)"

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
