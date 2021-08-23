/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud

import ch.biot.backend.crud.CRUDVerticle.Companion.BAD_REQUEST_CODE
import ch.biot.backend.crud.CRUDVerticle.Companion.HTTP_PORT
import ch.biot.backend.crud.CRUDVerticle.Companion.MONGO_PORT
import ch.biot.backend.crud.CRUDVerticle.Companion.TIMESCALE_PORT
import ch.biot.backend.crud.queries.*
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

  // Will be overwritten when creating the actual categories in the DB
  private var ecgCategory: Pair<Int, String> = 1 to "ECG"
  private var litCategory: Pair<Int, String> = 2 to "Lit"
  private var lit2Category: Pair<Int, String> = 3 to "Lit2"

  private var existingItemID: Int = 1
  private val existingItem = jsonObjectOf(
    "beacon" to "ab:ab:ab:ab:ab:ab",
    "categoryID" to ecgCategory.first,
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
    "categoryID" to litCategory.first,
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
    "categoryID" to litCategory.first,
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

  private val existingItemGrp1 = jsonObjectOf(
    "beacon" to "ab:ab:ab:ab:ff:ff",
    "categoryID" to ecgCategory.first,
    "service" to "Bloc 2",
    "itemID" to "abc",
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

  private val existingItemGrp2 = jsonObjectOf(
    "beacon" to "ab:ab:ab:af:ff:ff",
    "categoryID" to ecgCategory.first,
    "service" to "Bloc 2",
    "itemID" to "abc",
    "accessControlString" to "biot:grp2",
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

  private var existingItemGrp1Grp3Id: Int = -1
  private val existingItemGrp1Grp3 = jsonObjectOf(
    "id" to 1234,
    "beacon" to "fakegrp1grp3",
    "categoryID" to ecgCategory.first,
    "service" to "Bloc 2",
    "itemID" to "abc",
    "accessControlString" to "biot:grp1:grp3",
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

  private val closestItemGrp1 = jsonObjectOf(
    "beacon" to "fakeClosestGrp1",
    "categoryID" to litCategory.first,
    "service" to "Bloc 1",
    "itemID" to "cde",
    "accessControlString" to "biot:grp1",
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

  private val closestItemGrp1Cat2 = jsonObjectOf(
    "beacon" to "fakeCloseGrp1Ct2",
    "categoryID" to lit2Category.first,
    "service" to "Bloc 1",
    "itemID" to "cde",
    "accessControlString" to "biot:grp1",
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
      dropAllSnapshots().await()
      dropAllItems().await()
      dropAllCategories().await()
      insertCategories().await()
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

  private fun dropAllCategories(): CompositeFuture {
    return CompositeFuture.all(
      pgClient.query("DELETE FROM categories").execute(),
      pgClient.query("DELETE FROM company_categories").execute()
    )
  }

  private suspend fun dropAllSnapshots(): CompositeFuture {
    val firstTableExists = pgClient.tableExists("items_snapshot_1")
    val secondTableExists = pgClient.tableExists("items_snapshot_2")

    return CompositeFuture.all(
      pgClient.query("DELETE FROM items_snapshots").execute(),
      if (firstTableExists) pgClient.query("DROP TABLE items_snapshot_1").execute() else Future.succeededFuture(),
      if (secondTableExists) pgClient.query("DROP TABLE items_snapshot_2").execute() else Future.succeededFuture()
    )
  }

  private suspend fun insertCategories(): Future<RowSet<Row>> {
    ecgCategory = pgClient.preparedQuery(insertCategory())
      .execute(Tuple.of(ecgCategory.second)).await().iterator().next().getInteger("id") to ecgCategory.second
    existingItem.put("categoryID", ecgCategory.first)
    existingItemGrp1.put("categoryID", ecgCategory.first)
    existingItemGrp2.put("categoryID", ecgCategory.first)
    existingItemGrp1Grp3.put("categoryID", ecgCategory.first)
    pgClient.preparedQuery(addCategoryToCompany())
      .execute(Tuple.of(ecgCategory.first, "biot")).await()

    litCategory = pgClient.preparedQuery(insertCategory())
      .execute(Tuple.of(litCategory.second)).await().iterator().next().getInteger("id") to litCategory.second
    closestItem.put("categoryID", litCategory.first)
    updateItemJson.put("categoryID", litCategory.first)
    closestItemGrp1.put("categoryID", litCategory.first)
    pgClient.preparedQuery(addCategoryToCompany())
      .execute(Tuple.of(litCategory.first, "biot")).await()

    lit2Category = pgClient.preparedQuery(insertCategory())
      .execute(Tuple.of(lit2Category.second)).await().iterator().next().getInteger("id") to lit2Category.second
    closestItemGrp1Cat2.put("categoryID", lit2Category.first)
    return pgClient.preparedQuery(addCategoryToCompany())
      .execute(Tuple.of(lit2Category.first, "biot"))
  }

  private suspend fun insertItems(): Future<RowSet<Row>> {
    val result = pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          existingItem["beacon"],
          existingItem["categoryID"],
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
          closestItem["categoryID"],
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
          closestItem["categoryID"],
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
          closestItem["categoryID"],
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
          closestItem["categoryID"],
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
          closestItem["categoryID"],
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
          closestItem["categoryID"],
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
  fun cleanup(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    existingItemGrp1Grp3Id = -1
    try {
      dropAllSnapshots().await()
      dropAllItems().await()
      dropAllCategories().await()
      pgClient.close()
      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  @DisplayName("createSnapshot correctly creates a snapshot")
  fun createSnapshotIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    val acString = "biot:grp1"
    val snapshotID = Given {
      spec(requestSpecification)
    } When {
      queryParam("company", "biot")
      queryParam("accessControlString", acString)
      post("/items/snapshots")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(snapshotID).isNotEmpty() // it returns the id of the created snapshot
    }

    try {
      val snapshotTableExists = pgClient.tableExists("items_snapshot_$snapshotID")
      testContext.verify {
        expectThat(snapshotTableExists).isTrue()
      }
    } catch (error: Throwable) {
      testContext.failNow(error)
    }

    try {
      val snapshots = pgClient.query(getSnapshots("items", acString)).execute().await().iterator()
      testContext.verify {
        expectThat(snapshots.hasNext()).isTrue()

        val snapshot = snapshots.next()
        val id = snapshot.getInteger("id")
        val date = snapshot.getLocalDate("snapshotdate")
        val accessControlString = snapshot.getString("accesscontrolstring")

        expectThat(id).isEqualTo(snapshotID.toInt())
        expectThat(date).isEqualTo(LocalDate.now())
        expectThat(accessControlString).isEqualTo(acString)
        testContext.completeNow()
      }
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  @DisplayName("createSnapshot fails without an access control string")
  fun createSnapshotFailsWithoutACString(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      post("/items/snapshots")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("createSnapshot fails without a company")
  fun createSnapshotFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      post("/items/snapshots")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getSnapshots correctly retrieves the list of snapshots")
  fun getSnapshotsIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    try {
      val id1 = pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await().iterator().next()
        .getInteger("id")
      val id2 = pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await().iterator().next()
        .getInteger("id")

      val snapshots = Buffer.buffer(
        Given {
          spec(requestSpecification)
          accept(ContentType.JSON)
        } When {
          queryParam("company", "biot")
          queryParam("accessControlString", "biot")
          get("/items/snapshots")
        } Then {
          statusCode(200)
        } Extract {
          asString()
        }
      ).toJsonArray()

      testContext.verify {
        expectThat(snapshots.size()).isEqualTo(2)

        val firstSnapshot = snapshots.getJsonObject(0)
        val secondSnapshot = snapshots.getJsonObject(1)

        expectThat(firstSnapshot.getInteger("id")).isEqualTo(id1)
        expectThat(secondSnapshot.getInteger("id")).isEqualTo(id2)

        expectThat(firstSnapshot.getString("date")).isEqualTo(LocalDate.now().toString())
        expectThat(secondSnapshot.getString("date")).isEqualTo(LocalDate.now().toString())

        testContext.completeNow()
      }
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  @DisplayName("getSnapshots fails without an access control string")
  fun getSnapshotsFailsWithoutACString(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      get("/items/snapshots")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getSnapshots fails without a company")
  fun getSnapshotsFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      get("/items/snapshots")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getSnapshots correctly retrieves the list of snapshots accessible given an access control string")
  fun getSnapshotsIsCorrectWithSufficientACString(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val acString = "biot:grp1"
        pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await().iterator().next()
          .getInteger("id")
        val id2 = pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of(acString)).await().iterator().next()
          .getInteger("id")

        val snapshots = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("company", "biot")
            queryParam("accessControlString", acString)
            get("/items/snapshots")
          } Then {
            statusCode(200)
          } Extract {
            asString()
          }
        ).toJsonArray()

        testContext.verify {
          expectThat(snapshots.size()).isEqualTo(1)

          val snapshot = snapshots.getJsonObject(0)

          expectThat(snapshot.getInteger("id")).isEqualTo(id2)

          expectThat(snapshot.getString("date")).isEqualTo(LocalDate.now().toString())

          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("getSnapshots correctly retrieves no snapshots given an insufficient access control string")
  fun getSnapshotsIsCorrectWithInsufficientACString(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val acString = "biot:grp1"
        pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await().iterator().next()
          .getInteger("id")
        pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await().iterator().next()
          .getInteger("id")

        val snapshots = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("company", "biot")
            queryParam("accessControlString", acString)
            get("/items/snapshots")
          } Then {
            statusCode(200)
          } Extract {
            asString()
          }
        ).toJsonArray()

        testContext.verify {
          expectThat(snapshots.isEmpty).isTrue()
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("getSnapshot correctly retrieves the list of items contained in a snapshot")
  fun getSnapshotIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    try {
      val snapshotID =
        pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await().iterator().next()
          .getInteger("id")
      pgClient.query(snapshotTable("items", snapshotID)).execute().await()

      val items = Buffer.buffer(
        Given {
          spec(requestSpecification)
          accept(ContentType.JSON)
        } When {
          queryParam("company", "biot")
          queryParam("accessControlString", "biot")
          get("/items/snapshots/$snapshotID")
        } Then {
          statusCode(200)
        } Extract {
          asString()
        }
      ).toJsonArray()

      testContext.verify {
        expectThat(items.size()).isGreaterThan(0)

        val firstItem = items.getJsonObject(0).apply { remove("id") }
        val expected = existingItem.copy().apply {
          remove("categoryID")
          put("category", ecgCategory.second)
        }
        expectThat(firstItem).isEqualTo(expected)

        testContext.completeNow()
      }
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  @DisplayName("getSnapshot fails without an access control string")
  fun getSnapshotFailsWithoutACString(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      get("/items/snapshots/1")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getSnapshot fails without a company")
  fun getSnapshotFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      get("/items/snapshots/1")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getSnapshot fails with error 403 if the access control string is insufficient")
  fun getSnapshotFailsIfInsufficientACString(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val snapshotID =
          pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await().iterator().next()
            .getInteger("id")
        pgClient.query(snapshotTable("items", snapshotID)).execute().await()

        val response = Given {
          spec(requestSpecification)
          accept(ContentType.JSON)
        } When {
          queryParam("company", "biot")
          queryParam("accessControlString", "biot:grp1:grp2")
          get("/items/snapshots/$snapshotID")
        } Then {
          statusCode(403)
        } Extract {
          asString()
        }

        testContext.verify {
          expectThat(response).isEqualTo("Forbidden")
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("getSnapshot fails with error 404 if the snapshot does not exist")
  fun getSnapshotFailsIfTheSnapshotDoesNotExist(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val snapshotID = 1

        val response = Given {
          spec(requestSpecification)
          accept(ContentType.JSON)
        } When {
          queryParam("company", "biot")
          queryParam("accessControlString", "biot")
          get("/items/snapshots/$snapshotID")
        } Then {
          statusCode(404)
        } Extract {
          asString()
        }

        testContext.verify {
          expectThat(response).isEmpty()
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("deleteSnapshot correctly deletes the snapshot")
  fun deleteSnapshotIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    try {
      val snapshotID =
        pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await().iterator().next()
          .getInteger("id")
      pgClient.query(snapshotTable("items", snapshotID)).execute().await()

      val response = Given {
        spec(requestSpecification)
      } When {
        queryParam("company", "biot")
        queryParam("accessControlString", "biot")
        delete("/items/snapshots/$snapshotID")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isEmpty()
      }

      val snapshotTableExists = pgClient.tableExists("items_snapshot_$snapshotID")
      val snapshotEntryExists =
        pgClient.query("SELECT * FROM items_snapshots WHERE id = $snapshotID").execute().await().iterator().hasNext()

      testContext.verify {
        expectThat(snapshotTableExists).isFalse()
        expectThat(snapshotEntryExists).isFalse()
        testContext.completeNow()
      }
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  @DisplayName("deleteSnapshot fails without an access control string")
  fun deleteSnapshotFailsWithoutACString(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      delete("/items/snapshots/1")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("deleteSnapshot fails without a company")
  fun deleteSnapshotFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      delete("/items/snapshots/1")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("deleteSnapshot fails with error 403 if the access control string is insufficient")
  fun deleteSnapshotFailsIfInsufficientACString(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val snapshotID =
          pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await().iterator().next()
            .getInteger("id")
        pgClient.query(snapshotTable("items", snapshotID)).execute().await()

        val response = Given {
          spec(requestSpecification)
        } When {
          queryParam("company", "biot")
          queryParam("accessControlString", "biot:grp1:grp2")
          delete("/items/snapshots/$snapshotID")
        } Then {
          statusCode(403)
        } Extract {
          asString()
        }

        testContext.verify {
          expectThat(response).isEqualTo("Forbidden")
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("deleteSnapshot fails with error 404 if the snapshot does not exist")
  fun deleteSnapshotFailsIfTheSnapshotDoesNotExist(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val snapshotID = 1

        val response = Given {
          spec(requestSpecification)
        } When {
          queryParam("company", "biot")
          queryParam("accessControlString", "biot")
          delete("/items/snapshots/$snapshotID")
        } Then {
          statusCode(404)
        } Extract {
          asString()
        }

        testContext.verify {
          expectThat(response).isEmpty()
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("compareSnapshots fails with error 403 if insufficient access control string")
  fun compareSnapshotsFailsIfInsufficientACString(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val firstSnapshotId = pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await()
          .iterator().next().getInteger("id")
        pgClient.query(snapshotTable("items", firstSnapshotId)).execute().await()

        val secondSnapshotId = pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot:grp1")).await()
          .iterator().next().getInteger("id")
        pgClient.query(snapshotTable("items", secondSnapshotId)).execute().await()

        val response = Given {
          spec(requestSpecification)
        } When {
          queryParam("company", "biot")
          queryParam("firstSnapshotId", firstSnapshotId)
          queryParam("secondSnapshotId", secondSnapshotId)
          queryParam("accessControlString", "biot:grp1:grp2")
          get("/items/snapshots/compare")
        } Then {
          statusCode(403)
        } Extract {
          asString()
        }

        testContext.verify {
          expectThat(response).isEqualTo("Forbidden")
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("compareSnapshots fails without an access control string")
  fun compareSnapshotsFailsWithoutACString(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      queryParam("firstSnapshotId", 1)
      queryParam("secondSnapshotId", 2)
      get("/items/snapshots/compare")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("compareSnapshots fails without a company")
  fun compareSnapshotsFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      queryParam("firstSnapshotId", 1)
      queryParam("secondSnapshotId", 2)
      get("/items/snapshots/compare")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("compareSnapshots fails with error 404 if the first snapshot does not exist")
  fun compareSnapshotsFailsIfTheFirstSnapshotDoesNotExist(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val firstSnapshotId = 1
        val secondSnapshotId = 2

        val response = Given {
          spec(requestSpecification)
        } When {
          queryParam("company", "biot")
          queryParam("firstSnapshotId", firstSnapshotId)
          queryParam("secondSnapshotId", secondSnapshotId)
          queryParam("accessControlString", "biot")
          get("/items/snapshots/compare")
        } Then {
          statusCode(404)
        } Extract {
          asString()
        }

        testContext.verify {
          expectThat(response).isEmpty()
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("compareSnapshots fails with error 404 if the second snapshot does not exist")
  fun compareSnapshotsFailsIfTheSecondSnapshotDoesNotExist(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val secondSnapshotId = 2

        val firstSnapshotId = pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await()
          .iterator().next().getInteger("id")
        pgClient.query(snapshotTable("items", firstSnapshotId)).execute().await()

        val response = Given {
          spec(requestSpecification)
        } When {
          queryParam("company", "biot")
          queryParam("firstSnapshotId", firstSnapshotId)
          queryParam("secondSnapshotId", secondSnapshotId)
          queryParam("accessControlString", "biot")
          get("/items/snapshots/compare")
        } Then {
          statusCode(404)
        } Extract {
          asString()
        }

        testContext.verify {
          expectThat(response).isEmpty()
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("compareSnapshots correctly returns the comparison object")
  fun compareSnapshotsIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val firstSnapshotId = pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await()
          .iterator().next().getInteger("id")
        pgClient.query(snapshotTable("items", firstSnapshotId)).execute().await()

        // Add new items
        val newItem1 = jsonObjectOf(
          "beacon" to "ab:cd:ab:cd:ab:cd"
        )
        Given {
          spec(requestSpecification)
          contentType(ContentType.JSON)
          body(newItem1.encode())
        } When {
          queryParam("company", "biot")
          queryParam("accessControlString", "biot")
          post("/items")
        } Then {
          statusCode(200)
        }

        val newItem2 = jsonObjectOf(
          "beacon" to "cd:ab:cd:ab:cd:ab"
        )
        Given {
          spec(requestSpecification)
          contentType(ContentType.JSON)
          body(newItem2.encode())
        } When {
          queryParam("company", "biot")
          queryParam("accessControlString", "biot")
          post("/items")
        } Then {
          statusCode(200)
        }

        val secondSnapshotId = pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await()
          .iterator().next().getInteger("id")
        pgClient.query(snapshotTable("items", secondSnapshotId)).execute().await()

        val comparison = Buffer.buffer(Given {
          spec(requestSpecification)
        } When {
          queryParam("company", "biot")
          queryParam("firstSnapshotId", firstSnapshotId)
          queryParam("secondSnapshotId", secondSnapshotId)
          queryParam("accessControlString", "biot")
          get("/items/snapshots/compare")
        } Then {
          statusCode(200)
        } Extract {
          asString()
        }).toJsonObject()

        testContext.verify {
          val onlyFirst = comparison.getJsonArray("onlyFirst")
          val onlySecond = comparison.getJsonArray("onlySecond")
          val inCommon = comparison.getJsonArray("inCommon")

          expectThat(onlyFirst.size()).isEqualTo(0)
          expectThat(onlySecond.size()).isEqualTo(2)
          expectThat(inCommon.size()).isEqualTo(7)

          expectThat(onlySecond.any { entry ->
            val jsonObj = entry as JsonObject
            jsonObj.apply { remove("id") }.getString("beacon") == newItem1.getString("beacon")
          }).isTrue()
          expectThat(onlySecond.any { entry ->
            val jsonObj = entry as JsonObject
            jsonObj.apply { remove("id") }.getString("beacon") == newItem2.getString("beacon")
          }).isTrue()

          expectThat(inCommon).contains(existingItem.apply {
            remove("categoryID")
            put("category", ecgCategory.second)
            put("id", existingItemID)
          })

          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("registerItem correctly registers a new item")
  fun registerItemIsCorrect(testContext: VertxTestContext) {
    val newItem = jsonObjectOf(
      "beacon" to "aa:aa:aa:aa:aa:aa",
      "categoryID" to litCategory.first,
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
      queryParam("accessControlString", "biot")
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
  @DisplayName("registerItem fails without an access control string")
  fun registerItemFailsWithoutACString(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      post("/items")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("registerItem fails without a company")
  fun registerItemFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      post("/items")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("registerItem without specifying the status correctly registers a new item with the status set to 'Under creation'")
  fun registerItemWithoutSpecifyingStatusIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "categoryID" to litCategory.first,
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
        queryParam("accessControlString", "biot")
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
        val res = pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(id)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(newItem.getString("beacon"))
          that(newItem.getInteger("categoryID")).isEqualTo(litCategory.first)
          that(json.getString("category")).isEqualTo(litCategory.second)
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

  private suspend fun insertItemsAccessControl(): Future<RowSet<Row>> {
    pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          "fake6",
          existingItemGrp1["categoryID"],
          existingItemGrp1["service"],
          existingItemGrp1["itemID"],
          existingItemGrp1["accessControlString"],
          existingItemGrp1["brand"],
          existingItemGrp1["model"],
          existingItemGrp1["supplier"],
          LocalDate.parse(existingItemGrp1["purchaseDate"]),
          existingItemGrp1["purchasePrice"],
          existingItemGrp1["originLocation"],
          existingItemGrp1["currentLocation"],
          existingItemGrp1["room"],
          existingItemGrp1["contact"],
          existingItemGrp1["currentOwner"],
          existingItemGrp1["previousOwner"],
          existingItemGrp1["orderNumber"],
          existingItemGrp1["color"],
          existingItemGrp1["serialNumber"],
          LocalDate.parse(existingItemGrp1["maintenanceDate"]),
          existingItemGrp1["status"],
          existingItemGrp1["comments"],
          LocalDate.parse(existingItemGrp1["lastModifiedDate"]),
          existingItemGrp1["lastModifiedBy"]
        )
      ).await()

    pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          "fake7",
          existingItemGrp2["categoryID"],
          existingItemGrp2["service"],
          existingItemGrp2["itemID"],
          existingItemGrp2["accessControlString"],
          existingItemGrp2["brand"],
          existingItemGrp2["model"],
          existingItemGrp2["supplier"],
          LocalDate.parse(existingItemGrp2["purchaseDate"]),
          existingItemGrp2["purchasePrice"],
          existingItemGrp2["originLocation"],
          existingItemGrp2["currentLocation"],
          existingItemGrp2["room"],
          existingItemGrp2["contact"],
          existingItemGrp2["currentOwner"],
          existingItemGrp2["previousOwner"],
          existingItemGrp2["orderNumber"],
          existingItemGrp2["color"],
          existingItemGrp2["serialNumber"],
          LocalDate.parse(existingItemGrp2["maintenanceDate"]),
          existingItemGrp2["status"],
          existingItemGrp2["comments"],
          LocalDate.parse(existingItemGrp2["lastModifiedDate"]),
          existingItemGrp2["lastModifiedBy"]
        )
      ).await()

    val result = pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          existingItemGrp1Grp3["beacon"],
          existingItemGrp1Grp3["categoryID"],
          existingItemGrp1Grp3["service"],
          existingItemGrp1Grp3["itemID"],
          existingItemGrp1Grp3["accessControlString"],
          existingItemGrp1Grp3["brand"],
          existingItemGrp1Grp3["model"],
          existingItemGrp1Grp3["supplier"],
          LocalDate.parse(existingItemGrp1Grp3["purchaseDate"]),
          existingItemGrp1Grp3["purchasePrice"],
          existingItemGrp1Grp3["originLocation"],
          existingItemGrp1Grp3["currentLocation"],
          existingItemGrp1Grp3["room"],
          existingItemGrp1Grp3["contact"],
          existingItemGrp1Grp3["currentOwner"],
          existingItemGrp1Grp3["previousOwner"],
          existingItemGrp1Grp3["orderNumber"],
          existingItemGrp1Grp3["color"],
          existingItemGrp1Grp3["serialNumber"],
          LocalDate.parse(existingItemGrp1Grp3["maintenanceDate"]),
          existingItemGrp1Grp3["status"],
          existingItemGrp1Grp3["comments"],
          LocalDate.parse(existingItemGrp1Grp3["lastModifiedDate"]),
          existingItemGrp1Grp3["lastModifiedBy"]
        )
      ).await()

    existingItemGrp1Grp3Id = result.iterator().next().getInteger("id")

    pgClient.preparedQuery(INSERT_BEACON_DATA)
      .execute(
        Tuple.of(
          closestItemGrp1.getString("beacon"),
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
          closestItemGrp1Cat2.getString("beacon"),
          existingBeaconData.getInteger("battery"),
          existingBeaconData.getString("beaconStatus"),
          42,
          -7.99,
          existingBeaconData.getInteger("floor"),
          existingBeaconData.getDouble("temperature")
        )
      ).await()

    pgClient.preparedQuery(INSERT_BEACON_DATA)
      .execute(
        Tuple.of(
          existingItemGrp1Grp3.getString("beacon"),
          existingBeaconData.getInteger("battery"),
          existingBeaconData.getString("beaconStatus"),
          47,
          -8,
          2,
          3.3
        )
      ).await()

    pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          closestItemGrp1Cat2["beacon"],
          closestItemGrp1Cat2["categoryID"],
          closestItemGrp1Cat2["service"],
          closestItemGrp1Cat2["itemID"],
          closestItemGrp1Cat2["accessControlString"],
          closestItemGrp1Cat2["brand"],
          closestItemGrp1Cat2["model"],
          closestItemGrp1Cat2["supplier"],
          LocalDate.parse(closestItemGrp1Cat2["purchaseDate"]),
          closestItemGrp1Cat2["purchasePrice"],
          closestItemGrp1Cat2["originLocation"],
          closestItemGrp1Cat2["currentLocation"],
          closestItemGrp1Cat2["room"],
          closestItemGrp1Cat2["contact"],
          closestItemGrp1Cat2["currentOwner"],
          closestItemGrp1Cat2["previousOwner"],
          closestItemGrp1Cat2["orderNumber"],
          closestItemGrp1Cat2["color"],
          closestItemGrp1Cat2["serialNumber"],
          LocalDate.parse(closestItemGrp1Cat2["maintenanceDate"]),
          closestItemGrp1Cat2["status"],
          closestItemGrp1Cat2["comments"],
          LocalDate.parse(closestItemGrp1Cat2["lastModifiedDate"]),
          closestItemGrp1Cat2["lastModifiedBy"]
        )
      ).await()

    return pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          closestItemGrp1["beacon"],
          closestItemGrp1["categoryID"],
          closestItemGrp1["service"],
          closestItemGrp1["itemID"],
          closestItemGrp1["accessControlString"],
          closestItemGrp1["brand"],
          closestItemGrp1["model"],
          closestItemGrp1["supplier"],
          LocalDate.parse(closestItemGrp1["purchaseDate"]),
          closestItemGrp1["purchasePrice"],
          closestItemGrp1["originLocation"],
          closestItemGrp1["currentLocation"],
          closestItemGrp1["room"],
          closestItemGrp1["contact"],
          closestItemGrp1["currentOwner"],
          closestItemGrp1["previousOwner"],
          closestItemGrp1["orderNumber"],
          closestItemGrp1["color"],
          closestItemGrp1["serialNumber"],
          LocalDate.parse(closestItemGrp1["maintenanceDate"]),
          closestItemGrp1["status"],
          closestItemGrp1["comments"],
          LocalDate.parse(closestItemGrp1["lastModifiedDate"]),
          closestItemGrp1["lastModifiedBy"]
        )
      )
  }

  @Test
  @DisplayName("registerItem correctly registers a new item with a custom id")
  fun registerItemWithIdIsCorrect(testContext: VertxTestContext) {
    val newItem = jsonObjectOf(
      "id" to 120,
      "beacon" to "aa:aa:aa:aa:aa:aa",
      "categoryID" to litCategory.first,
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
      queryParam("accessControlString", "biot")
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
        queryParam("accessControlString", "biot")
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
  @DisplayName("getItems fails without an access control string")
  fun getItemsFailsWithoutACString(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      get("/items")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getItems fails without a company")
  fun getItemsFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      get("/items")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
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
    categoryID SERIAL REFERENCES categories(id) ON UPDATE CASCADE ON DELETE SET NULL,
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
          queryParam("accessControlString", "another")
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
      queryParam("accessControlString", "anotherCompany")
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
        queryParam("categoryID", existingItem.getInteger("categoryID"))
        queryParam("company", "biot")
        queryParam("accessControlString", "biot")
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
        expectThat(existingItem.getInteger("categoryID")).isEqualTo(ecgCategory.first)
        expectThat(jsonObj.getString("category")).isEqualTo(ecgCategory.second)
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
        queryParam("company", "biot")
        queryParam("accessControlString", "biot")
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
  @DisplayName("getClosestItems fails without an access control string")
  fun getClosestItemsFailsWithoutACString(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      get("/items/closest")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getClosestItems fails without a company")
  fun getClosestItemsFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      get("/items/closeset")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
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
        queryParam("company", "biot")
        queryParam("accessControlString", "biot")
        queryParam("category", closestItem.getInteger("categoryID"))
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
      expectThat(closestItem.getInteger("categoryID")).isEqualTo(litCategory.first)
      expectThat(closestFirstFloor.getString("category")).isEqualTo(litCategory.second)
      expectThat(closestFirstFloor.getString("beacon")).isEqualTo(closestItem.getString("beacon"))

      val secondFloor: JsonArray = response["2"]
      expectThat(secondFloor.size()).isEqualTo(1)
      val closestSecondFloor = secondFloor.getJsonObject(0)

      expectThat(closestSecondFloor.getString("category")).isEqualTo(litCategory.second)
      expectThat(closestSecondFloor.getString("beacon")).isEqualTo("fake5")

      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getCategories correctly retrieves all categories")
  fun getCategoriesIsCorrect(testContext: VertxTestContext) {
    val expected = JsonArray(
      listOf(
        jsonObjectOf("id" to ecgCategory.first, "name" to ecgCategory.second),
        jsonObjectOf("id" to litCategory.first, "name" to litCategory.second),
        jsonObjectOf("id" to lit2Category.first, "name" to lit2Category.second)
      )
    )

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        queryParam("company", "biot")
        queryParam("accessControlString", "biot")
        get("/items/categories")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonArray()

    testContext.verify {
      expectThat(response.toHashSet()).isEqualTo(expected.toHashSet())
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getCategories fails without a company")
  fun getCategoriesFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      get("/items/categories")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getItem correctly retrieves the desired item")
  fun getItemIsCorrect(testContext: VertxTestContext) {
    val expected = existingItem.copy().apply {
      remove("categoryID")
      put("category", ecgCategory.second)
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
        queryParam("accessControlString", "biot")
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
  @DisplayName("getItem fails without an access control string")
  fun getItemFailsWithoutACString(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      get("/items/1")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getItem fails without a company")
  fun getItemFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      get("/items/1")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
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
      queryParam("accessControlString", "biot")
      get("/items/1000")
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
      queryParam("accessControlString", "biot")
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
      val res =
        pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(existingItemID)).await()
      val json = res.iterator().next().toItemJson()
      expect {
        that(json.getString("beacon")).isEqualTo(updateItemJson.getString("beacon"))
        that(updateItemJson.getInteger("categoryID")).isEqualTo(litCategory.first)
        that(json.getString("category")).isEqualTo(litCategory.second)
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
  @DisplayName("updateItem fails without an access control string")
  fun updateItemFailsWithoutACString(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      put("/items/1")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("updateItem fails without a company")
  fun updateItemFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      put("/items/1")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
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
        queryParam("accessControlString", "biot")
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
        val res =
          pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(existingItemID)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(existingItem.getString("beacon"))
          that(existingItem.getInteger("categoryID")).isEqualTo(ecgCategory.first)
          that(json.getString("category")).isEqualTo(ecgCategory.second)
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
        queryParam("accessControlString", "biot")
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
        val res =
          pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(existingItemID)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(updateJson.getString("beacon"))
          that(updateJson.getInteger("categoryID")).isEqualTo(litCategory.first)
          that(json.getString("category")).isEqualTo(litCategory.second)
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
  @DisplayName("updateItem fails with error 404 if the item does not exist")
  fun updateItemFailsIfItemDoesNotExist(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val nonExistingItemID = 9999
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
        queryParam("accessControlString", "biot")
        put("/items/$nonExistingItemID")
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
  @DisplayName("The bad request error handler works on wrong body received")
  fun badRequestErrorHandlerWorksOnWrongBodyReceived(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(jsonObjectOf("categoryID" to "abcd").encode())
    } When {
      queryParam("company", "biot")
      queryParam("accessControlString", "biot")
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
      "categoryID" to litCategory.first,
      "service" to "Bloc 42"
    )

    // Register the item
    val id = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      queryParam("company", "biot")
      queryParam("accessControlString", "biot")
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
      queryParam("accessControlString", "biot")
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
  @DisplayName("deleteItem fails without an access control string")
  fun deleteItemFailsWithoutACString(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      delete("/items/1")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("deleteItem fails without a company")
  fun deleteItemFailsWithoutCompany(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("accessControlString", "biot")
      delete("/items/1")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Something went wrong while parsing/validating a parameter.")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("deleteItem fails with error 404 if the item does not exist")
  fun deleteItemFailsIfItemDoesNotExist(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val response = Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
      } When {
        queryParam("company", "biot")
        queryParam("accessControlString", "biot")
        delete("/items/1000")
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
  @DisplayName("Item updates on POST operations are correctly received on the event bus")
  fun receivePostUpdatesOnEventBus(vertx: Vertx, testContext: VertxTestContext) {
    val itemId = 100
    val newItem = jsonObjectOf(
      "id" to itemId,
      "categoryID" to ecgCategory.first
    )
    val expectedType = UpdateType.POST.toString()

    vertx.eventBus().consumer<JsonObject>(ITEMS_UPDATES_ADDRESS) { msg ->
      val body = msg.body()
      testContext.verify {
        expectThat(body.getString("type")).isEqualTo(expectedType)
        expectThat(body.getInteger("id")).isEqualTo(itemId)
        expectThat(newItem.getInteger("categoryID")).isEqualTo(ecgCategory.first)
        expectThat(body.getJsonObject("content").getString("category")).isEqualTo(ecgCategory.second)
        testContext.completeNow()
      }
    }

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      queryParam("company", "biot")
      queryParam("accessControlString", "biot")
      post("/items")
    } Then {
      statusCode(200)
    }
  }

  @Test
  @DisplayName("Item updates on PUT operations are correctly received on the event bus")
  fun receivePutUpdatesOnEventBus(vertx: Vertx, testContext: VertxTestContext) {
    val itemId = 999999
    val newItem = jsonObjectOf(
      "id" to itemId,
      "categoryID" to ecgCategory.first
    )
    val updateItem = jsonObjectOf(
      "categoryID" to litCategory.first
    )
    val expectedType = UpdateType.PUT.toString()

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      queryParam("company", "biot")
      queryParam("accessControlString", "biot")
      post("/items")
    } Then {
      statusCode(200)
    }

    vertx.eventBus().consumer<JsonObject>(ITEMS_UPDATES_ADDRESS) { msg ->
      val body = msg.body()
      testContext.verify {
        val type = body.getString("type")
        if (type != UpdateType.POST.toString()) {
          expectThat(body.getString("type")).isEqualTo(expectedType)
          expectThat(body.getInteger("id")).isEqualTo(itemId)
          expectThat(updateItem.getInteger("categoryID")).isEqualTo(litCategory.first)
          expectThat(body.getJsonObject("content").getString("category")).isEqualTo(litCategory.second)
          testContext.completeNow()
        }
      }
    }

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(updateItem.encode())
    } When {
      queryParam("company", "biot")
      queryParam("accessControlString", "biot")
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
      "categoryID" to ecgCategory.first
    )
    val expectedType = UpdateType.DELETE.toString()

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      queryParam("company", "biot")
      queryParam("accessControlString", "biot")
      post("/items")
    } Then {
      statusCode(200)
    }

    vertx.eventBus().consumer<JsonObject>(ITEMS_UPDATES_ADDRESS) { msg ->
      val body = msg.body()
      testContext.verify {
        val type = body.getString("type")
        if (type != UpdateType.POST.toString()) {
          expectThat(type).isEqualTo(expectedType)
          expectThat(body.getInteger("id")).isEqualTo(itemId)
          testContext.completeNow()
        }
      }
    }

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      queryParam("accessControlString", "biot")
      delete("/items/$itemId")
    } Then {
      statusCode(200)
    }
  }

  @Test
  @DisplayName(
    "registerItem without specifying the accessControlString correctly registers a new item with " +
      "the accessControlString set to '<company>' (here 'biot')"
  )
  fun registerWithoutSpecifyingACStringIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "categoryID" to litCategory.first,
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
        queryParam("accessControlString", "biot")
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
        val res = pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(id)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(newItem.getString("beacon"))
          that(newItem.getInteger("categoryID")).isEqualTo(litCategory.first)
          that(json.getString("category")).isEqualTo(litCategory.second)
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

  // Access Control tests

  @Test
  @DisplayName("registerItem by specifying the accessControlString wrongly sends a 400 error 1")
  fun registerSpecifyingACStringWronglyIsCorrect1(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "categoryID" to litCategory.first,
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
        queryParam("accessControlString", "biot")
        post("/items")
      } Then {
        statusCode(400)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isNotEmpty()
        testContext.completeNow()
      }
    }

  @Test
  @DisplayName("registerItem by specifying the accessControlString wrongly sends a 400 error 2")
  fun registerSpecifyingACStringWronglyIsCorrect2(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "categoryID" to litCategory.first,
        "service" to "Bloc 1",
        "itemID" to "ee",
        "accessControlString" to "biot21:fdsa",
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
        queryParam("accessControlString", "biot")
        post("/items")
      } Then {
        statusCode(400)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isNotEmpty()
        testContext.completeNow()
      }
    }

  @Test
  @DisplayName("registerItem by specifying the accessControlString wrongly sends a 400 error 3")
  fun registerSpecifyingACStringWronglyIsCorrect3(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "categoryID" to litCategory.first,
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
        queryParam("accessControlString", "biot")
        post("/items")
      } Then {
        statusCode(400)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isNotEmpty()
        testContext.completeNow()
      }
    }

  @Test
  @DisplayName(
    "registerItem by specifying the accessControlString correctly registers a new item with " +
      "the correct accessControlString"
  )
  fun registerSpecifyingACStringCorrectlyIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "categoryID" to litCategory.first,
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
        queryParam("accessControlString", "biot")
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
        val res = pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(id)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(newItem.getString("beacon"))
          that(newItem.getInteger("categoryID")).isEqualTo(litCategory.first)
          that(json.getString("category")).isEqualTo(litCategory.second)
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

  @Test
  @DisplayName(
    "registerItem by not specifying the accessControlString registers a new item with " +
      "the accessControlString set to <company> (here 'biot')"
  )
  fun registerNotSpecifyingACStringIsCorrect(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "aa:aa:aa:aa:aa:aa",
        "categoryID" to litCategory.first,
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
        queryParam("accessControlString", "biot")
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
        val res = pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(id)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(newItem.getString("beacon"))
          that(newItem.getInteger("categoryID")).isEqualTo(litCategory.first)
          that(json.getString("category")).isEqualTo(litCategory.second)
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
  @DisplayName("getItems correctly retrieves accessible items when an accessControlString is passed 1")
  fun getItemsIsCorrectWithACString1(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      try {
        insertItemsAccessControl().await()

        val accessControlString = "biot:grp1"
        val response = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("company", "biot")
            queryParam("accessControlString", accessControlString)
            get("/items")
          } Then {
            statusCode(200)
          } Extract {
            asString()
          }
        ).toJsonArray()

        testContext.verify {
          expectThat(response.isEmpty).isFalse()
          expectThat(response.size()).isEqualTo(4)
          val accessString1 = response.getJsonObject(0).getString("accessControlString")
          val accessString2 = response.getJsonObject(1).getString("accessControlString")
          val accessString3 = response.getJsonObject(2).getString("accessControlString")
          val accessString4 = response.getJsonObject(3).getString("accessControlString")

          expectThat(accessString1).startsWith(accessControlString)
          expectThat(accessString2).startsWith(accessControlString)
          expectThat(accessString3).startsWith(accessControlString)
          expectThat(accessString4).startsWith(accessControlString)
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow("Cannot insert objects prior to the test")
      }
    }
  }

  @Test
  @DisplayName("getItems correctly retrieves accessible items when an accessControlString is passed 2")
  fun getItemsIsCorrectWithACString2(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      try {
        insertItemsAccessControl().await()

        val accessControlString = "biot:grp2"
        val response = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("company", "biot")
            queryParam("accessControlString", accessControlString)
            get("/items")
          } Then {
            statusCode(200)
          } Extract {
            asString()
          }
        ).toJsonArray()

        testContext.verify {
          expectThat(response.isEmpty).isFalse()
          expectThat(response.size()).isEqualTo(1)
          val accessString1 = response.getJsonObject(0).getString("accessControlString")

          expectThat(accessString1).startsWith(accessControlString)
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow("Cannot insert objects prior to the test")
      }
    }
  }

  @Test
  @DisplayName("getItems correctly retrieves accessible items when an accessControlString is passed 3")
  fun getItemsIsCorrectWithACString3(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      try {
        insertItemsAccessControl().await()

        val accessControlString = "biot:grp1:grp3"
        val response = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("company", "biot")
            queryParam("accessControlString", accessControlString)
            get("/items")
          } Then {
            statusCode(200)
          } Extract {
            asString()
          }
        ).toJsonArray()

        testContext.verify {
          expectThat(response.isEmpty).isFalse()
          expectThat(response.size()).isEqualTo(1)
          val accessString1 = response.getJsonObject(0).getString("accessControlString")

          expectThat(accessString1).startsWith(accessControlString)
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow("Cannot insert objects prior to the test")
      }
    }
  }

  @Test
  @DisplayName("getItems only retrieves accessible items when the accessControlString is prefix and groups are complete")
  fun getItemsIsCorrectWithACStringNoGroupPrefix(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      try {
        insertItemsAccessControl().await()

        val accessControlString = "biot:gr"
        val response = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("company", "biot")
            queryParam("accessControlString", accessControlString)
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
      } catch (error: Throwable) {
        testContext.failNow("Cannot insert objects prior to the test")
      }
    }
  }

  @Test
  @DisplayName("registerItem correctly inserts well formed item with an accessControlString")
  fun registerItemIsCorrectWithValidACString(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "ae:ac:ab:aa:aa:ff",
        "categoryID" to ecgCategory.first,
        "service" to "Bloc 1",
        "itemID" to "ee",
        "accessControlString" to "biot:grp1:grp2:grp3",
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
        queryParam("accessControlString", "biot")
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
        val res = pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(id)).await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(newItem.getString("beacon"))
          that(newItem.getInteger("categoryID")).isEqualTo(ecgCategory.first)
          that(json.getString("category")).isEqualTo(ecgCategory.second)
          that(json.getString("service")).isEqualTo(newItem.getString("service"))
          that(json.getString("itemID")).isEqualTo(newItem.getString("itemID"))
          that(json.getString("brand")).isEqualTo(newItem.getString("brand"))
          that(json.getString("accessControlString")).isEqualTo(newItem.getString("accessControlString"))
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
  }

  @Test
  @DisplayName("registerItem fails when no AC string is passed")
  fun registerItemFailsWithNoACString(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      val newItem = jsonObjectOf(
        "beacon" to "ae:ac:ab:aa:aa:ff",
        "categoryID" to ecgCategory.first,
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
        statusCode(400)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isNotEmpty()
        testContext.completeNow()
      }
    }
  }

  @Test
  @DisplayName("getClosestItems correctly retrieves the closest items per floor with correct AC")
  fun getClosestItemsIsCorrectWithAC(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      try {
        insertItemsAccessControl().await()

        val response = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("latitude", 42)
            queryParam("longitude", -8)
            queryParam("company", "biot")
            queryParam("accessControlString", closestItemGrp1.getString("accessControlString"))
            get("/items/closest")
          } Then {
            statusCode(200)
          } Extract {
            asString()
          }
        ).toJsonObject()

        testContext.verify {
          val firstFloor: JsonArray = response["1"]
          expectThat(firstFloor.size()).isEqualTo(2)
          val closestFirstFloor = firstFloor.getJsonObject(0)
          expectThat(closestFirstFloor.getString("beacon")).isEqualTo(closestItemGrp1.getString("beacon"))

          val secondFloor: JsonArray = response["2"]
          expectThat(secondFloor.size()).isEqualTo(1)
          val closestSecondFloor = secondFloor.getJsonObject(0)
          expectThat(closestSecondFloor.getString("beacon")).isEqualTo(existingItemGrp1Grp3.getString("beacon"))

          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow("Cannot insert objects prior to the test")
      }
    }
  }

  @Test
  @DisplayName("getClosestItems correctly retrieves nothing per floor with partially prefix AC")
  fun getClosestItemsIsCorrectWithIncorrectAC(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      try {
        insertItemsAccessControl().await()

        val response = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("latitude", 42)
            queryParam("longitude", -8)
            queryParam("company", "biot")
            queryParam(
              "accessControlString",
              closestItemGrp1.getString("accessControlString")
                .subSequence(0, closestItemGrp1.getString("accessControlString").length - 1)
            )
            get("/items/closest")
          } Then {
            statusCode(200)
          } Extract {
            asString()
          }
        ).toJsonObject()

        testContext.verify {
          expectThat(response.size()).isEqualTo(0)

          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow("Cannot insert objects prior to the test")
      }
    }
  }

  @Test
  @DisplayName("getClosestItems correctly retrieves nothing with a category per floor with AC")
  fun getClosestItemsWithCategoryIsCorrectWithAC(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      try {
        insertItemsAccessControl().await()

        val response = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("latitude", 42)
            queryParam("longitude", -8)
            queryParam("company", "biot")
            queryParam("categoryID", closestItemGrp1Cat2.getInteger("categoryID"))
            queryParam("accessControlString", closestItemGrp1Cat2.getString("accessControlString"))
            get("/items/closest")
          } Then {
            statusCode(200)
          } Extract {
            asString()
          }
        ).toJsonObject()

        testContext.verify {
          expectThat(response.size()).isEqualTo(1)
          val firstFloor: JsonArray = response["1"]
          expectThat(firstFloor.size()).isEqualTo(1)
          val closestFirstFloor = firstFloor.getJsonObject(0)
          expectThat(closestItemGrp1Cat2.getInteger("categoryID")).isEqualTo(lit2Category.first)
          expectThat(closestFirstFloor.getString("category")).isEqualTo(lit2Category.second)
          expectThat(closestFirstFloor.getString("beacon")).isEqualTo(closestItemGrp1Cat2.getString("beacon"))

          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow("Cannot insert objects prior to the test")
      }
    }
  }

  @Test
  @DisplayName("getClosestItems correctly retrieves the 1 closest items of the given category per floor with partially prefix AC")
  fun getClosestItemsWithCategoryIsCorrectWithIncorrectAC(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      try {
        insertItemsAccessControl().await()

        val response = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("latitude", 42)
            queryParam("longitude", -8)
            queryParam("category", closestItemGrp1Cat2.getInteger("categoryID"))
            queryParam("company", "biot")
            queryParam(
              "accessControlString",
              closestItemGrp1.getString("accessControlString")
                .subSequence(0, closestItemGrp1.getString("accessControlString").length - 1)
            )
            get("/items/closest")
          } Then {
            statusCode(200)
          } Extract {
            asString()
          }
        ).toJsonObject()

        testContext.verify {
          expectThat(response.size()).isEqualTo(0)
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow("Cannot insert objects prior to the test")
      }
    }
  }

  @Test
  @DisplayName("getItem correctly retrieves the desired item if the accessString has the right to access 1")
  fun getItemIsCorrectWithCorrectAC1(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      try {
        insertItemsAccessControl().await()

        val expected = existingItemGrp1Grp3.copy().apply {
          remove("categoryID")
          put("category", ecgCategory.second)
          put("battery", existingBeaconData.getInteger("battery"))
          put("beaconStatus", existingBeaconData.getString("beaconStatus"))
          put("latitude", 47)
          put("longitude", -8)
          put("floor", 2)
          put("temperature", 3.3)
          remove("id")
        }

        val response = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("company", "biot")
            queryParam("accessControlString", existingItemGrp1Grp3.getString("accessControlString"))
            get("/items/$existingItemGrp1Grp3Id")
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
          expectThat(id).isEqualTo(existingItemGrp1Grp3Id)
          expectThat(timestamp).isNotEmpty()
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow("Cannot insert objects prior to the test")
      }
    }
  }

  @Test
  @DisplayName("getItem correctly retrieves the desired item if the accessString has the right to access 2")
  fun getItemIsCorrectWithCorrectAC2(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      try {
        insertItemsAccessControl().await()

        val expected = existingItemGrp1Grp3.copy().apply {
          remove("categoryID")
          put("category", ecgCategory.second)
          put("battery", existingBeaconData.getInteger("battery"))
          put("beaconStatus", existingBeaconData.getString("beaconStatus"))
          put("latitude", 47)
          put("longitude", -8)
          put("floor", 2)
          put("temperature", 3.3)
          remove("id")
        }

        val response = Buffer.buffer(
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("company", "biot")
            queryParam("accessControlString", "biot:grp1")
            get("/items/$existingItemGrp1Grp3Id")
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
          expectThat(id).isEqualTo(existingItemGrp1Grp3Id)
          expectThat(timestamp).isNotEmpty()
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow("Cannot insert objects prior to the test")
      }
    }
  }

  @Test
  @DisplayName("getItem returns not found if the accessString has not the right to access")
  fun getItemIsCorrectWithInsufficientAC(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      try {
        insertItemsAccessControl().await()

        val response =
          Given {
            spec(requestSpecification)
            accept(ContentType.JSON)
          } When {
            queryParam("company", "biot")
            queryParam("accessControlString", "biot:grp1:grp4")
            get("/items/$existingItemGrp1Grp3Id")
          } Then {
            statusCode(404)
          } Extract {
            asString()
          }

        testContext.verify {
          expectThat(response).isEmpty()
          testContext.completeNow()
        }
      } catch (error: Throwable) {
        testContext.failNow("Cannot insert objects prior to the test")
      }
    }
  }

  @Test
  @DisplayName("deleteItem does not delete the item if the accessString does not allow access")
  fun deleteItemIsCorrectWithInsufficientAccessString(testContext: VertxTestContext) {
    val newItem = jsonObjectOf(
      "beacon" to "ab:cd:ef:aa:aa:aa",
      "categoryID" to litCategory.first,
      "accessControlString" to "biot:grp1",
      "service" to "Bloc 42"
    )

    // Register the item
    val id = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      queryParam("company", "biot")
      queryParam("accessControlString", "biot")
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
      queryParam("accessControlString", "biot:grp1:grp2")
      delete("/items/$id")
    } Then {
      statusCode(404)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
    }

    val response2 = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        queryParam("company", "biot")
        queryParam("accessControlString", "biot")
        get("/items/$id")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      val idBis = response2.remove("id")
      expectThat(idBis).isEqualTo(id.toInt())
      val beacon: String = response2.remove("beacon") as String
      expectThat(beacon).isEqualTo(newItem.getString("beacon"))
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("deleteItem does delete the item if the accessString does allow access")
  fun deleteItemIsCorrectWithSufficientAccessString(testContext: VertxTestContext) {
    val newItem = jsonObjectOf(
      "beacon" to "ab:cd:ef:aa:aa:aa",
      "categoryID" to litCategory.first,
      "accessControlString" to "biot:grp1",
      "service" to "Bloc 42"
    )

    // Register the item
    val id = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(newItem.encode())
    } When {
      queryParam("company", "biot")
      queryParam("accessControlString", "biot")
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
      queryParam("accessControlString", "biot:grp1")
      delete("/items/$id")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
    }

    val response2 = Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
    } When {
      queryParam("company", "biot")
      queryParam("accessControlString", "biot")
      get("/items/$id")
    } Then {
      statusCode(404)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response2).isEmpty()
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("updateItem correctly updates the desired item if the accessControlString authorizes the access 1")
  fun updateItemIsCorrectWithSufficientACString1(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      insertItemsAccessControl().await()

      if (existingItemGrp1Grp3Id == -1) {
        testContext.failNow("existingItemGrp1Grp3Id == -1")
      }
      val response = Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
        body(updateItemJson.encode())
      } When {
        queryParam("company", "biot")
        queryParam("accessControlString", "biot:grp1:grp3")
        put("/items/$existingItemGrp1Grp3Id")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isEmpty()
      }

      try {
        val res =
          pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(existingItemGrp1Grp3Id))
            .await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(updateItemJson.getString("beacon"))
          that(json.getString("accessControlString")).isEqualTo(updateItemJson.getString("accessControlString"))
          that(updateItemJson.getInteger("categoryID")).isEqualTo(litCategory.first)
          that(json.getString("category")).isEqualTo(litCategory.second)
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
  }

  @Test
  @DisplayName("updateItem correctly updates the desired item if the accessControlString authorizes the access 2")
  fun updateItemIsCorrectWithSufficientACString2(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      insertItemsAccessControl().await()
      val response = Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
        body(updateItemJson.encode())
      } When {
        queryParam("company", "biot")
        queryParam("accessControlString", "biot:grp1")
        put("/items/$existingItemGrp1Grp3Id")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isEmpty()
      }

      try {
        val res =
          pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(existingItemGrp1Grp3Id))
            .await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(updateItemJson.getString("beacon"))
          that(json.getString("accessControlString")).isEqualTo(updateItemJson.getString("accessControlString"))
          that(updateItemJson.getInteger("categoryID")).isEqualTo(litCategory.first)
          that(json.getString("category")).isEqualTo(litCategory.second)
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
  }


  @Test
  @DisplayName("updateItem does not update the desired item if the accessControlString does not authorize the access and sends a code 403 1")
  fun updateItemIsCorrectWithInsufficientACString1(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      insertItemsAccessControl().await()
      val response = Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
        body(updateItemJson.encode())
      } When {
        queryParam("company", "biot")
        queryParam("accessControlString", "biot:grp1:grp3:grp4")
        put("/items/$existingItemGrp1Grp3Id")
      } Then {
        statusCode(403)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isNotNull()
      }

      try {
        val res =
          pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(existingItemGrp1Grp3Id))
            .await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(existingItemGrp1Grp3.getString("beacon"))
          that(json.getString("accessControlString")).isEqualTo(existingItemGrp1Grp3.getString("accessControlString"))
          that(existingItemGrp1Grp3.getInteger("categoryID")).isEqualTo(ecgCategory.first)
          that(json.getString("category")).isEqualTo(ecgCategory.second)
          that(json.getString("service")).isEqualTo(existingItemGrp1Grp3.getString("service"))
          that(json.getString("itemID")).isEqualTo(existingItemGrp1Grp3.getString("itemID"))
          that(json.getString("brand")).isEqualTo(existingItemGrp1Grp3.getString("brand"))
          that(json.getString("model")).isEqualTo(existingItemGrp1Grp3.getString("model"))
          that(json.getString("supplier")).isEqualTo(existingItemGrp1Grp3.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(existingItemGrp1Grp3.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(existingItemGrp1Grp3.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(existingItemGrp1Grp3.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(existingItemGrp1Grp3.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(existingItemGrp1Grp3.getString("room"))
          that(json.getString("contact")).isEqualTo(existingItemGrp1Grp3.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(existingItemGrp1Grp3.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(existingItemGrp1Grp3.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(existingItemGrp1Grp3.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(existingItemGrp1Grp3.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(existingItemGrp1Grp3.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(existingItemGrp1Grp3.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo(existingItemGrp1Grp3.getString("status"))
          that(json.getString("comments")).isEqualTo(existingItemGrp1Grp3.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(existingItemGrp1Grp3.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(existingItemGrp1Grp3.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isEqualTo(existingBeaconData.getInteger("battery"))
          that(json.getString("beaconStatus")).isEqualTo(existingBeaconData.getString("beaconStatus"))
          that(json.getDouble("latitude")).isEqualTo(47.0)
          that(json.getDouble("longitude")).isEqualTo(-8.0)
          that(json.getInteger("floor")).isEqualTo(2)
          that(json.getDouble("temperature")).isEqualTo(3.3)
        }

        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }
  }


  @Test
  @DisplayName("updateItem does not update the desired item if the accessControlString does not authorize the access and sends a code 403 2")
  fun updateItemIsCorrectWithInsufficientACString2(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      insertItemsAccessControl().await()
      val response = Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
        body(updateItemJson.encode())
      } When {
        queryParam("company", "biot")
        queryParam("accessControlString", "biot:grp2")
        put("/items/$existingItemGrp1Grp3Id")
      } Then {
        statusCode(403)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isNotNull()
      }

      try {
        val res =
          pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(existingItemGrp1Grp3Id))
            .await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(existingItemGrp1Grp3.getString("beacon"))
          that(json.getString("accessControlString")).isEqualTo(existingItemGrp1Grp3.getString("accessControlString"))
          that(existingItemGrp1Grp3.getInteger("categoryID")).isEqualTo(ecgCategory.first)
          that(json.getString("category")).isEqualTo(ecgCategory.second)
          that(json.getString("service")).isEqualTo(existingItemGrp1Grp3.getString("service"))
          that(json.getString("itemID")).isEqualTo(existingItemGrp1Grp3.getString("itemID"))
          that(json.getString("brand")).isEqualTo(existingItemGrp1Grp3.getString("brand"))
          that(json.getString("model")).isEqualTo(existingItemGrp1Grp3.getString("model"))
          that(json.getString("supplier")).isEqualTo(existingItemGrp1Grp3.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(existingItemGrp1Grp3.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(existingItemGrp1Grp3.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(existingItemGrp1Grp3.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(existingItemGrp1Grp3.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(existingItemGrp1Grp3.getString("room"))
          that(json.getString("contact")).isEqualTo(existingItemGrp1Grp3.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(existingItemGrp1Grp3.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(existingItemGrp1Grp3.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(existingItemGrp1Grp3.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(existingItemGrp1Grp3.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(existingItemGrp1Grp3.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(existingItemGrp1Grp3.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo(existingItemGrp1Grp3.getString("status"))
          that(json.getString("comments")).isEqualTo(existingItemGrp1Grp3.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(existingItemGrp1Grp3.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(existingItemGrp1Grp3.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isEqualTo(existingBeaconData.getInteger("battery"))
          that(json.getString("beaconStatus")).isEqualTo(existingBeaconData.getString("beaconStatus"))
          that(json.getDouble("latitude")).isEqualTo(47.0)
          that(json.getDouble("longitude")).isEqualTo(-8.0)
          that(json.getInteger("floor")).isEqualTo(2)
          that(json.getDouble("temperature")).isEqualTo(3.3)
        }

        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }
  }

  @Test
  @DisplayName(
    "updateItem returns bad request error 400 if the passed accessControlString to update is wrongly formatted " +
      "and does not modify the item 1"
  )
  fun updateItemThrowsErrorIfUpdatedACStringIsWrong1(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      insertItemsAccessControl().await()

      val newUpdateItem = updateItemJson.copy().apply {
        remove("accessControlString")
        put("accessControlString", "bio")
      }
      val response = Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
        body(newUpdateItem.encode())
      } When {
        queryParam("company", "biot")
        queryParam("accessControlString", "biot:grp1:grp3")
        put("/items/$existingItemGrp1Grp3Id")
      } Then {
        statusCode(400)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isEqualTo("Bad Request")
      }

      try {
        val res =
          pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(existingItemGrp1Grp3Id))
            .await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(existingItemGrp1Grp3.getString("beacon"))
          that(json.getString("accessControlString")).isEqualTo(existingItemGrp1Grp3.getString("accessControlString"))
          that(existingItemGrp1Grp3.getInteger("categoryID")).isEqualTo(ecgCategory.first)
          that(json.getString("category")).isEqualTo(ecgCategory.second)
          that(json.getString("service")).isEqualTo(existingItemGrp1Grp3.getString("service"))
          that(json.getString("itemID")).isEqualTo(existingItemGrp1Grp3.getString("itemID"))
          that(json.getString("brand")).isEqualTo(existingItemGrp1Grp3.getString("brand"))
          that(json.getString("model")).isEqualTo(existingItemGrp1Grp3.getString("model"))
          that(json.getString("supplier")).isEqualTo(existingItemGrp1Grp3.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(existingItemGrp1Grp3.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(existingItemGrp1Grp3.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(existingItemGrp1Grp3.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(existingItemGrp1Grp3.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(existingItemGrp1Grp3.getString("room"))
          that(json.getString("contact")).isEqualTo(existingItemGrp1Grp3.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(existingItemGrp1Grp3.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(existingItemGrp1Grp3.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(existingItemGrp1Grp3.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(existingItemGrp1Grp3.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(existingItemGrp1Grp3.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(existingItemGrp1Grp3.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo(existingItemGrp1Grp3.getString("status"))
          that(json.getString("comments")).isEqualTo(existingItemGrp1Grp3.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(existingItemGrp1Grp3.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(existingItemGrp1Grp3.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isEqualTo(existingBeaconData.getInteger("battery"))
          that(json.getString("beaconStatus")).isEqualTo(existingBeaconData.getString("beaconStatus"))
          that(json.getDouble("latitude")).isEqualTo(47.0)
          that(json.getDouble("longitude")).isEqualTo(-8.0)
          that(json.getInteger("floor")).isEqualTo(2)
          that(json.getDouble("temperature")).isEqualTo(3.3)
        }

        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }
  }

  @Test
  @DisplayName(
    "updateItem returns bad request error 400 if the passed accessControlString to update is wrongly formatted" +
      "and does not modify the item 2"
  )
  fun updateItemThrowsErrorIfUpdatedACStringIsWrong2(vertx: Vertx, testContext: VertxTestContext) {
    runBlocking(vertx.dispatcher()) {
      insertItemsAccessControl().await()

      val newUpdateItem = updateItemJson.copy().apply {
        remove("accessControlString")
        put("accessControlString", "biot:grp1:")
      }
      val response = Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
        body(newUpdateItem.encode())
      } When {
        queryParam("company", "biot")
        queryParam("accessControlString", "biot:grp1:grp3")
        put("/items/$existingItemGrp1Grp3Id")
      } Then {
        statusCode(400)
      } Extract {
        asString()
      }

      testContext.verify {
        expectThat(response).isEqualTo("Bad Request")
      }

      try {
        val res =
          pgClient.preparedQuery(getItem("items", "beacon_data", "biot")).execute(Tuple.of(existingItemGrp1Grp3Id))
            .await()
        val json = res.iterator().next().toItemJson()
        expect {
          that(json.getString("beacon")).isEqualTo(existingItemGrp1Grp3.getString("beacon"))
          that(json.getString("accessControlString")).isEqualTo(existingItemGrp1Grp3.getString("accessControlString"))
          that(existingItemGrp1Grp3.getInteger("categoryID")).isEqualTo(ecgCategory.first)
          that(json.getString("category")).isEqualTo(ecgCategory.second)
          that(json.getString("service")).isEqualTo(existingItemGrp1Grp3.getString("service"))
          that(json.getString("itemID")).isEqualTo(existingItemGrp1Grp3.getString("itemID"))
          that(json.getString("brand")).isEqualTo(existingItemGrp1Grp3.getString("brand"))
          that(json.getString("model")).isEqualTo(existingItemGrp1Grp3.getString("model"))
          that(json.getString("supplier")).isEqualTo(existingItemGrp1Grp3.getString("supplier"))
          that(json.getString("purchaseDate")).isEqualTo(existingItemGrp1Grp3.getString("purchaseDate"))
          that(json.getDouble("purchasePrice")).isEqualTo(existingItemGrp1Grp3.getDouble("purchasePrice"))
          that(json.getString("originLocation")).isEqualTo(existingItemGrp1Grp3.getString("originLocation"))
          that(json.getString("currentLocation")).isEqualTo(existingItemGrp1Grp3.getString("currentLocation"))
          that(json.getString("room")).isEqualTo(existingItemGrp1Grp3.getString("room"))
          that(json.getString("contact")).isEqualTo(existingItemGrp1Grp3.getString("contact"))
          that(json.getString("currentOwner")).isEqualTo(existingItemGrp1Grp3.getString("currentOwner"))
          that(json.getString("previousOwner")).isEqualTo(existingItemGrp1Grp3.getString("previousOwner"))
          that(json.getString("orderNumber")).isEqualTo(existingItemGrp1Grp3.getString("orderNumber"))
          that(json.getString("color")).isEqualTo(existingItemGrp1Grp3.getString("color"))
          that(json.getString("serialNumber")).isEqualTo(existingItemGrp1Grp3.getString("serialNumber"))
          that(json.getString("maintenanceDate")).isEqualTo(existingItemGrp1Grp3.getString("maintenanceDate"))
          that(json.getString("status")).isEqualTo(existingItemGrp1Grp3.getString("status"))
          that(json.getString("comments")).isEqualTo(existingItemGrp1Grp3.getString("comments"))
          that(json.getString("lastModifiedDate")).isEqualTo(existingItemGrp1Grp3.getString("lastModifiedDate"))
          that(json.getString("lastModifiedBy")).isEqualTo(existingItemGrp1Grp3.getString("lastModifiedBy"))
          that(json.getInteger("battery")).isEqualTo(existingBeaconData.getInteger("battery"))
          that(json.getString("beaconStatus")).isEqualTo(existingBeaconData.getString("beaconStatus"))
          that(json.getDouble("latitude")).isEqualTo(47.0)
          that(json.getDouble("longitude")).isEqualTo(-8.0)
          that(json.getInteger("floor")).isEqualTo(2)
          that(json.getDouble("temperature")).isEqualTo(3.3)
        }

        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
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
