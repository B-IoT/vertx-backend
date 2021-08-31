/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.publicapi

import ch.biot.backend.crud.CRUDVerticle
import ch.biot.backend.crud.CRUDVerticle.Companion.INITIAL_USER
import ch.biot.backend.crud.queries.*
import ch.biot.backend.crud.saltAndHash
import ch.biot.backend.crud.tableExists
import ch.biot.backend.publicapi.PublicApiVerticle.Companion.CRUD_HOST
import ch.biot.backend.publicapi.PublicApiVerticle.Companion.CRUD_PORT
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
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
import io.vertx.core.http.HttpVersion
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.eventbusclient.EventBusClient
import io.vertx.eventbusclient.EventBusClientOptions
import io.vertx.ext.auth.mongo.MongoAuthentication
import io.vertx.ext.auth.mongo.MongoUserUtil
import io.vertx.ext.mongo.MongoClient
import io.vertx.ext.web.client.WebClient
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import io.vertx.kotlin.ext.mongo.indexOptionsOf
import io.vertx.kotlin.ext.web.client.webClientOptionsOf
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
/**
 * Integration tests.
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
    ),
    "forceReset" to false
  )

  // Will be overwritten when creating the actual categories in the DB
  private var ecgCategory: Pair<Int, String> = 1 to "ECG"
  private var litCategory: Pair<Int, String> = 2 to "Lit"

  private val item1 = jsonObjectOf(
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

  private val item2Grp1 = jsonObjectOf(
    "beacon" to "ff:ff:ff:ff:ff:ff",
    "categoryID" to litCategory.first,
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
    "categoryID" to ecgCategory.first,
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
  private var item1ID = -1

  private var snapshotID = -1

  private lateinit var token: String
  private lateinit var tokenGrp1: String

  private lateinit var mongoClientUsers: MongoClient
  private lateinit var mongoUserUtilUsers: MongoUserUtil
  private lateinit var mongoAuthUsers: MongoAuthentication

  private lateinit var mongoClientRelays: MongoClient
  private lateinit var mongoUserUtilRelays: MongoUserUtil
  private lateinit var mongoAuthRelays: MongoAuthentication

  private lateinit var pgClient: SqlClient

  private lateinit var webClient: WebClient

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    try {
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

      initializeMongoForUsers(vertx)
      initializeMongoForRelays(vertx)

      dropAllUsers().await()
      mongoClientUsers.createIndexWithOptions("users", jsonObjectOf("userID" to 1), indexOptionsOf().unique(true))
        .await()
      mongoClientUsers.createIndexWithOptions("users", jsonObjectOf("username" to 1), indexOptionsOf().unique(true))
        .await()

      dropAllRelays().await()
      mongoClientRelays.createIndexWithOptions("relays", jsonObjectOf("relayID" to 1), indexOptionsOf().unique(true))
        .await()
      mongoClientRelays.createIndexWithOptions("relays", jsonObjectOf("mqttID" to 1), indexOptionsOf().unique(true))
        .await()
      mongoClientRelays.createIndexWithOptions(
        "relays",
        jsonObjectOf("mqttUsername" to 1),
        indexOptionsOf().unique(true)
      )
        .await()

      insertUsers().await()
      insertRelay().await()

      dropAllSnapshots().await()
      dropAllItems().await()
      dropAllCategories().await()
      insertCategories().await()
      insertItems()
      insertSnapshots()

      webClient = spyk(
        WebClient.create(
          vertx,
          webClientOptionsOf(
            tryUseCompression = true,
            protocolVersion = HttpVersion.HTTP_2,
            http2ClearTextUpgrade = true
          )
        )
      )

      vertx.deployVerticle(PublicApiVerticle(webClient)).await()
      vertx.deployVerticle(CRUDVerticle()).await()

      token = getAuthToken(user)
      tokenGrp1 = getAuthToken(user2)

      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  private fun getAuthToken(user: JsonObject): String {
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

    return response
  }

  private fun initializeMongoForUsers(vertx: Vertx) {
    mongoClientUsers =
      MongoClient.createShared(
        vertx,
        jsonObjectOf("host" to "localhost", "port" to CRUDVerticle.MONGO_PORT, "db_name" to "clients")
      )

    val usernameField = "username"
    val passwordField = "password"
    val mongoAuthOptionsUsers = mongoAuthenticationOptionsOf(
      collectionName = "users",
      passwordCredentialField = passwordField,
      passwordField = passwordField,
      usernameCredentialField = usernameField,
      usernameField = usernameField
    )

    mongoUserUtilUsers = MongoUserUtil.create(
      mongoClientUsers, mongoAuthOptionsUsers, mongoAuthorizationOptionsOf()
    )
    mongoAuthUsers = MongoAuthentication.create(mongoClientUsers, mongoAuthOptionsUsers)
  }

  private fun initializeMongoForRelays(vertx: Vertx) {
    mongoClientRelays =
      MongoClient.createShared(
        vertx,
        jsonObjectOf("host" to "localhost", "port" to CRUDVerticle.MONGO_PORT, "db_name" to "clients")
      )

    val usernameField = "mqttUsername"
    val passwordField = "mqttPassword"
    val mongoAuthOptionsRelays = mongoAuthenticationOptionsOf(
      collectionName = "relays",
      passwordCredentialField = passwordField,
      passwordField = passwordField,
      usernameCredentialField = usernameField,
      usernameField = usernameField
    )

    mongoUserUtilRelays = MongoUserUtil.create(
      mongoClientRelays, mongoAuthOptionsRelays, mongoAuthorizationOptionsOf()
    )
    mongoAuthRelays = MongoAuthentication.create(mongoClientRelays, mongoAuthOptionsRelays)
  }

  @AfterEach
  fun cleanup(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    try {
      clearAllMocks()
      dropAllSnapshots().await()
      dropAllItems().await()
      dropAllCategories().await()
      dropAllUsers().await()
      dropAllRelays().await()
      mongoClientUsers.close().await()
      mongoClientRelays.close().await()
      pgClient.close()
      webClient.close()
      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  private fun dropAllUsers() = mongoClientUsers.removeDocuments("users", jsonObjectOf())

  private fun dropAllRelays() = mongoClientRelays.removeDocuments("relays", jsonObjectOf())

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

  private suspend fun insertSnapshots() {
    snapshotID = pgClient.preparedQuery(createSnapshot("items")).execute(Tuple.of("biot")).await().iterator().next()
      .getInteger("id")
    pgClient.query(snapshotTable("items", snapshotID)).execute().await()
  }

  private suspend fun insertItems() {
    val item1Result = pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          item1["beacon"],
          item1["categoryID"],
          item1["service"],
          item1["itemID"],
          item1["accessControlString"],
          item1["brand"],
          item1["model"],
          item1["supplier"],
          LocalDate.parse(item1["purchaseDate"]),
          item1["purchasePrice"],
          item1["originLocation"],
          item1["currentLocation"],
          item1["room"],
          item1["contact"],
          item1["currentOwner"],
          item1["previousOwner"],
          item1["orderNumber"],
          item1["color"],
          item1["serialNumber"],
          LocalDate.parse(item1["maintenanceDate"]),
          item1["status"],
          item1["comments"],
          LocalDate.parse(item1["lastModifiedDate"]),
          item1["lastModifiedBy"]
        )
      ).await()

    item1ID = item1Result.iterator().next().getInteger("id")

    val item2Result = pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          item2Grp1["beacon"],
          item2Grp1["categoryID"],
          item2Grp1["service"],
          item2Grp1["itemID"],
          item2Grp1["accessControlString"],
          item2Grp1["brand"],
          item2Grp1["model"],
          item2Grp1["supplier"],
          LocalDate.parse(item2Grp1["purchaseDate"]),
          item2Grp1["purchasePrice"],
          item2Grp1["originLocation"],
          item2Grp1["currentLocation"],
          item2Grp1["room"],
          item2Grp1["contact"],
          item2Grp1["currentOwner"],
          item2Grp1["previousOwner"],
          item2Grp1["orderNumber"],
          item2Grp1["color"],
          item2Grp1["serialNumber"],
          LocalDate.parse(item2Grp1["maintenanceDate"]),
          item2Grp1["status"],
          item2Grp1["comments"],
          LocalDate.parse(item2Grp1["lastModifiedDate"]),
          item2Grp1["lastModifiedBy"]
        )
      ).await()

    item2IDGrp1 = item2Result.iterator().next().getInteger("id")
  }

  private suspend fun insertCategories(): Future<RowSet<Row>> {
    ecgCategory = pgClient.preparedQuery(insertCategory())
      .execute(Tuple.of(ecgCategory.second)).await().iterator().next().getInteger("id") to ecgCategory.second
    item1.put("categoryID", ecgCategory.first)
    item3.put("categoryID", ecgCategory.first)
    pgClient.preparedQuery(addCategoryToCompany()).execute(Tuple.of(ecgCategory.first, "biot")).await()

    litCategory = pgClient.preparedQuery(insertCategory())
      .execute(Tuple.of(litCategory.second)).await().iterator().next().getInteger("id") to litCategory.second
    item2Grp1.put("categoryID", litCategory.first)
    return pgClient.preparedQuery(addCategoryToCompany()).execute(Tuple.of(litCategory.first, "biot"))
  }

  private suspend fun insertUsers(): CompositeFuture {
    suspend fun insertUser(user: JsonObject): Future<JsonObject> {
      val hashedPassword = user.getString("password").saltAndHash(mongoAuthUsers)
      val docID = mongoUserUtilUsers.createHashedUser(user.getString("username"), hashedPassword).await()
      val query = jsonObjectOf("_id" to docID)
      val extraInfo = jsonObjectOf(
        "\$set" to user.copy().apply {
          remove("password")
        }
      )
      return mongoClientUsers.findOneAndUpdate("users", query, extraInfo)
    }

    return CompositeFuture.all(insertUser(user), insertUser(user2))
  }

  private suspend fun insertRelay(): Future<JsonObject> {
    val hashedPassword = relay.getString("mqttPassword").saltAndHash(mongoAuthRelays)
    val docID = mongoUserUtilRelays.createHashedUser("test", hashedPassword).await()
    val query = jsonObjectOf("_id" to docID)
    val extraInfo = jsonObjectOf(
      "\$set" to relay.copy().apply {
        remove("password")
      }
    )
    return mongoClientRelays.findOneAndUpdate("relays", query, extraInfo)
  }

  @Test
  @DisplayName("Getting the token for the initial user succeeds")
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
  @DisplayName("Registering a user succeeds")
  fun registerUserSucceeds(testContext: VertxTestContext) {
    val requestSpy = spyk(webClient.post(CRUD_PORT, CRUD_HOST, "/users"))
    every { webClient.post(any(), any(), "/users") } returns requestSpy

    val newUser = user.copy().apply {
      put("userID", "newUser")
      put("username", "newUser")
    }

    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
      body(newUser.encode())
    } When {
      post("/oauth/register")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Getting the users succeeds")
  fun getUsersSucceeds(testContext: VertxTestContext) {
    val expected =
      jsonArrayOf(
        INITIAL_USER.copy().apply { remove("password") },
        user.copy().apply { remove("password") },
        user2.copy().apply { remove("password") }
      )

    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/users"))
    every { webClient.get(any(), any(), "/users") } returns requestSpy

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

      expectThat(response.map { (it as JsonObject).apply { remove("password") } }
        .toHashSet()).isEqualTo(expected.map { it as JsonObject }.toHashSet())

      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }

      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Getting a user succeeds")
  fun getUserSucceeds(testContext: VertxTestContext) {
    val userID = user.getString("userID")
    val expected = user.copy().apply { remove("password") }

    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/users/$userID"))
    every { webClient.get(any(), any(), "/users/$userID") } returns requestSpy

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/users/$userID")
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
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Updating a user succeeds")
  fun updateUserSucceeds(testContext: VertxTestContext) {
    val userID = user.getString("userID")
    val updateJson = jsonObjectOf(
      "password" to "newPassword"
    )

    val requestSpy = spyk(webClient.put(CRUD_PORT, CRUD_HOST, "/users/$userID"))
    every { webClient.put(any(), any(), "/users/$userID") } returns requestSpy

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(updateJson.encode())
    } When {
      put("/api/users/$userID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Deleting a user succeeds")
  fun deleteUserSucceeds(testContext: VertxTestContext) {
    val userID = "test42"
    val userToRemove = jsonObjectOf(
      "userID" to userID,
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

    val requestSpy = spyk(webClient.delete(CRUD_PORT, CRUD_HOST, "/users/$userID"))
    every { webClient.delete(any(), any(), "/users/$userID") } returns requestSpy

    // Delete the user
    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
    } When {
      delete("/api/users/$userID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Registering a relay succeeds")
  fun registerRelaySucceeds(testContext: VertxTestContext) {
    val requestSpy = spyk(webClient.post(CRUD_PORT, CRUD_HOST, "/relays"))
    every { webClient.post(any(), any(), "/relays") } returns requestSpy

    val newRelay = relay.copy().apply {
      put("mqttID", "newRelay")
      put("relayID", "newRelay")
      put("mqttUsername", "newRelay")
    }

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(newRelay.encode())
    } When {
      post("/api/relays")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Getting the relays succeeds")
  fun getRelaysSucceeds(testContext: VertxTestContext) {
    val expected = jsonArrayOf(relay.copy().apply { remove("mqttPassword") })

    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/relays"))
    every { webClient.get(any(), any(), "/relays") } returns requestSpy

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

      verify { requestSpy.addQueryParam("company", ofType(String::class)) }

      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Getting a relay succeeds")
  fun getRelaySucceeds(testContext: VertxTestContext) {
    val relayID = relay.getString("relayID")
    val expected = relay.copy().apply { remove("mqttPassword") }

    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/relays/$relayID"))
    every { webClient.get(any(), any(), "/relays/$relayID") } returns requestSpy

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/relays/$relayID")
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
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Updating a relay succeeds")
  fun updateRelaySucceeds(testContext: VertxTestContext) {
    val relayID = relay.getString("relayID")
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

    val requestSpy = spyk(webClient.put(CRUD_PORT, CRUD_HOST, "/relays/$relayID"))
    every { webClient.put(any(), any(), "/relays/$relayID") } returns requestSpy

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(updateJson.encode())
    } When {
      put("/api/relays/$relayID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Deleting a relay succeeds")
  fun deleteRelaySucceeds(testContext: VertxTestContext) {
    val relayID = "testRelay42"

    val relayToRemove = jsonObjectOf(
      "mqttID" to "testRelay42",
      "mqttUsername" to "testRelay42",
      "relayID" to relayID,
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

    val requestSpy = spyk(webClient.delete(CRUD_PORT, CRUD_HOST, "/relays/$relayID"))
    every { webClient.delete(any(), any(), "/relays/$relayID") } returns requestSpy

    // Delete the relay
    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
    } When {
      delete("/api/relays/$relayID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Registering an item succeeds")
  fun registerItemSucceeds(testContext: VertxTestContext) {
    val requestSpy = spyk(webClient.post(CRUD_PORT, CRUD_HOST, "/items"))
    every { webClient.post(any(), any(), "/items") } returns requestSpy

    val newItem = item1.copy().apply {
      put("beacon", "ab:cd:ab:cd:ab:cd")
    }

    val response = Given {
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

    testContext.verify {
      expectThat(response).isNotEmpty() // it returns the id of the registered item
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Getting the items succeeds")
  fun getItemsSucceeds(testContext: VertxTestContext) {
    val expected = item1.copy()

    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/items"))
    every { webClient.get(any(), any(), "/items") } returns requestSpy

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
      expectThat(id).isEqualTo(item1ID)
      expect {
        that(obj.getString("beacon")).isEqualTo(expected.getString("beacon"))
        that(ecgCategory.first).isEqualTo(expected.getInteger("categoryID"))
        that(obj.getString("category")).isEqualTo(ecgCategory.second)
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

      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }

      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Getting the items (with query parameters) succeeds")
  fun getItemsWithQueryParametersSucceeds(testContext: VertxTestContext) {
    val categoryID = item1.getInteger("categoryID")
    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/items/?categoryID=$categoryID"))
    every { webClient.get(any(), any(), "/items/?categoryID=$categoryID") } returns requestSpy

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        queryParam("categoryID", categoryID)
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
      expectThat(id).isEqualTo(item1ID)
      expect {
        that(obj.getString("beacon")).isEqualTo(item1.getString("beacon"))
        that(obj.getString("category")).isEqualTo(ecgCategory.second)
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

      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }

      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Getting the closest items succeeds")
  fun getClosestItemsSucceeds(testContext: VertxTestContext) {
    val latitude = 42
    val longitude = -8
    val requestSpy =
      spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/items/closest/?latitude=$latitude&longitude=$longitude"))
    every { webClient.get(any(), any(), "/items/closest/?latitude=$latitude&longitude=$longitude") } returns requestSpy

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        queryParam("latitude", latitude)
        queryParam("longitude", longitude)
        get("/api/items/closest")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response.isEmpty).isFalse()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Getting an item succeeds")
  fun getItemSucceeds(testContext: VertxTestContext) {
    val expected = item1.copy()

    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/items/$item1ID"))
    every { webClient.get(any(), any(), "/items/$item1ID") } returns requestSpy

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/items/$item1ID")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      val id = response.remove("id")
      expectThat(id).isEqualTo(item1ID)
      expect {
        that(response.getString("beacon")).isEqualTo(expected.getString("beacon"))
        that(ecgCategory.first).isEqualTo(expected.getInteger("categoryID"))
        that(response.getString("category")).isEqualTo(ecgCategory.second)
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

      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }

      testContext.completeNow()
    }
  }

  @Test
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

    val requestSpy = spyk(webClient.put(CRUD_PORT, CRUD_HOST, "/items/$item1ID"))
    every { webClient.put(any(), any(), "/items/$item1ID") } returns requestSpy

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(updateJson.encode())
    } When {
      put("/api/items/$item1ID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
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

    val scan = true
    val requestSpy = spyk(webClient.put(CRUD_PORT, CRUD_HOST, "/items/$item1ID?scan=$scan"))
    every { webClient.put(any(), any(), "/items/$item1ID?scan=$scan") } returns requestSpy

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(updateJson.encode())
    } When {
      queryParam("scan", scan)
      put("/api/items/$item1ID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
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

    val requestSpy = spyk(webClient.delete(CRUD_PORT, CRUD_HOST, "/items/$id"))
    every { webClient.delete(any(), any(), "/items/$id") } returns requestSpy

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
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
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
  @DisplayName("Getting the item's status succeeds (analytics)")
  fun getStatusSucceeds(testContext: VertxTestContext) {
    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/analytics/status"))
    every { webClient.get(any(), any(), "/analytics/status") } returns requestSpy

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
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Error 404 is handled in getOneHandler")
  fun errorNotFoundIsHandledGetOneRequest(testContext: VertxTestContext) {
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
  @DisplayName("Error 404 is handled in updateHandler")
  fun errorNotFoundIsHandledUpdateRequest(testContext: VertxTestContext) {
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
      put("/api/items/100")
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
  @DisplayName("Error 404 is handled in deleteHandler")
  fun errorNotFoundIsHandledDeleteRequest(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
    } When {
      delete("/api/users/notExists")
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
  @DisplayName("The event bus bridge endpoint is available")
  fun eventBusBridgeIsAvailable(testContext: VertxTestContext) {
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
  @DisplayName("Getting the categories succeeds")
  fun getCategoriesSucceeds(testContext: VertxTestContext) {
    val expected = JsonArray(
      listOf(
        jsonObjectOf("id" to ecgCategory.first, "name" to ecgCategory.second),
        jsonObjectOf("id" to litCategory.first, "name" to litCategory.second)
      )
    )

    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/items/categories"))
    every { webClient.get(any(), any(), "/items/categories") } returns requestSpy

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
      expectThat(response.toHashSet()).isEqualTo(expected.toHashSet())
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Creating a category succeeds")
  fun createCategorySucceeds(testContext: VertxTestContext) {
    val requestSpy = spyk(webClient.post(CRUD_PORT, CRUD_HOST, "/items/categories"))
    every { webClient.post(any(), any(), any()) } returns requestSpy

    val newCategory = jsonObjectOf("name" to "newName")

    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
      body(newCategory.encode())
    } When {
      post("/api/items/categories")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isNotEmpty()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Getting a category succeeds")
  fun getCategorySucceeds(testContext: VertxTestContext) {
    val categoryID = litCategory.first
    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/items/categories/$categoryID"))
    every { webClient.get(any(), any(), "/items/categories/$categoryID") } returns requestSpy

    val category = Buffer.buffer(Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
    } When {
      get("/api/items/categories/$categoryID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonObject()

    testContext.verify {
      expectThat(category.getInteger("id")).isEqualTo(categoryID)
      expectThat(category.getString("name")).isEqualTo(litCategory.second)
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Updating a category succeeds")
  fun updateCategorySucceeds(testContext: VertxTestContext) {
    val updateJson = jsonObjectOf("name" to "newName")
    val categoryID = litCategory.first

    val requestSpy = spyk(webClient.put(CRUD_PORT, CRUD_HOST, "/items/categories/$categoryID"))
    every { webClient.put(any(), any(), "/items/categories/$categoryID") } returns requestSpy

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
      body(updateJson.encode())
    } When {
      put("/api/items/categories/$categoryID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Deleting a category succeeds")
  fun deleteCategorySucceeds(testContext: VertxTestContext) {
    val newCategory = jsonObjectOf("name" to "newName")

    val categoryID = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
      body(newCategory.encode())
    } When {
      post("/api/items/categories")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    val requestSpy = spyk(webClient.delete(CRUD_PORT, CRUD_HOST, "/items/categories/$categoryID"))
    every { webClient.delete(any(), any(), "/items/categories/$categoryID") } returns requestSpy

    val response = Given {
      spec(requestSpecification)
      header("Authorization", "Bearer $token")
      contentType(ContentType.JSON)
    } When {
      delete("/api/items/categories/$categoryID")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Creating a snapshot succeeds")
  fun createSnapshotSucceeds(testContext: VertxTestContext) {
    val requestSpy = spyk(webClient.post(CRUD_PORT, CRUD_HOST, "/items/snapshots"))
    every { webClient.post(any(), any(), any()) } returns requestSpy

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
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Getting the list of snapshots succeeds")
  fun getSnapshotsSucceeds(testContext: VertxTestContext) {
    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/items/snapshots"))
    every { webClient.get(any(), any(), "/items/snapshots") } returns requestSpy

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
      expectThat(snapshots.size()).isEqualTo(1)
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Getting a snapshot succeeds")
  fun getSnapshotSucceeds(testContext: VertxTestContext) {
    val requestSpy = spyk(webClient.get(CRUD_PORT, CRUD_HOST, "/items/snapshots/$snapshotID"))
    every { webClient.get(any(), any(), "/items/snapshots/$snapshotID") } returns requestSpy

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
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
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

    val requestSpy = spyk(webClient.delete(CRUD_PORT, CRUD_HOST, "/items/snapshots/$snapshotID"))
    every { webClient.delete(any(), any(), "/items/snapshots/$snapshotID") } returns requestSpy

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
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
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

    val requestSpy = spyk(
      webClient.get(
        CRUD_PORT,
        CRUD_HOST,
        "/items/snapshots/compare/?firstSnapshotId=$firstSnapshotId&secondSnapshotId=$secondSnapshotId"
      )
    )
    every {
      webClient.get(
        any(),
        any(),
        "/items/snapshots/compare/?firstSnapshotId=$firstSnapshotId&secondSnapshotId=$secondSnapshotId"
      )
    } returns requestSpy

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
      verify { requestSpy.addQueryParam("company", ofType(String::class)) }
      verify { requestSpy.addQueryParam("accessControlString", ofType(String::class)) }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Registering a second item succeeds with a user with ac string = biot:grp1")
  fun registerItemSucceedsWithACString(testContext: VertxTestContext) {
    val newItem = item2Grp1.copy().apply {
      put("beacon", "ee:ee:ee:ee:ee:ee")
    }
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      header("Authorization", "Bearer $tokenGrp1")
      body(newItem.encode())
    } When {
      post("/api/items")
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
        that(litCategory.first).isEqualTo(expected.getInteger("categoryID"))
        that(response.getString("category")).isEqualTo(litCategory.second)
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
  @DisplayName("Getting an item fails (404 as if the item does not exist) with an insufficient ac string")
  fun getItemFailsWithWrongACString(testContext: VertxTestContext) {
    val response =
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $tokenGrp1")
      } When {
        get("/api/items/$item1ID")
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
  @DisplayName("Updating an item fails with insufficient ac string and does not modify the item")
  fun updateItemFailsWithWrongACString(testContext: VertxTestContext) {
    val expected = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/items/$item1ID")
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
      put("/api/items/$item1ID")
    } Then {
      statusCode(403)
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
        get("/api/items/$item1ID")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response2).isNotNull()
      val id = response2.remove("id")
      expectThat(id).isEqualTo(item1ID)
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
  @DisplayName("Deleting an item fails with insufficient ac string (it completes but does not delete the item)")
  fun deleteItemFailsWithWrongACString(testContext: VertxTestContext) {
    val expected = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
        header("Authorization", "Bearer $token")
      } When {
        get("/api/items/$item1ID")
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
      delete("/api/items/$item1ID")
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
        header("Authorization", "Bearer $token")
      } When {
        get("/api/items/$item1ID")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response2).isNotNull()
      val id = response2.remove("id")
      expectThat(id).isEqualTo(item1ID)
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
        that(litCategory.first).isEqualTo(expected.getInteger("categoryID"))
        that(response.getString("category")).isEqualTo(litCategory.second)
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
  @DisplayName("Registering an item succeeds with a user with ac string = biot:grp1 without specifying it in the json creates the item correctly with the AC string of the user")
  fun registerItemSucceedsWithoutACStringUsingUserOneAsDefault(testContext: VertxTestContext) {
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
      .put("category", ecgCategory.second)
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
  @DisplayName("Registering an item succeeds with a user with ac string = biot:grp1 specifying it in the json creates the item correctly with the AC string specified in the JSON")
  fun registerItemSucceedsUsingACStringSpecified(testContext: VertxTestContext) {
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

    val expected = insertJson.copy().put("category", ecgCategory.second)
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
  @Order(45)
  @DisplayName("Emergency reset request for relays return repo url and true as forceReset flag when unknown relayID is passed")
  fun emergencyResetResetWorksWithUnknownRelayID(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        queryParam("relayID", "unknownRelayID")
        get("/api/relays/emergency")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    val expected = jsonObjectOf("repoURL" to "git@github.com:B-IoT/relays_biot.git", "forceReset" to true)

    testContext.verify {
      expectThat(response.isEmpty).isFalse()
      expect {
        that(response.getString("repoURL")).isEqualTo(expected.getString("repoURL"))
        that(response.getString("forceReset")).isEqualTo(expected.getString("forceReset"))
      }
      testContext.completeNow()
    }
  }

  @Test
  @Order(46)
  @DisplayName("Emergency reset request for relays return repo url and true as forceReset flag when no relayID is passed")
  fun emergencyResetResetWorksWithNoRelayID(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        get("/api/relays/emergency")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    val expected = jsonObjectOf("repoURL" to "git@github.com:B-IoT/relays_biot.git", "forceReset" to true)

    testContext.verify {
      expectThat(response.isEmpty).isFalse()
      expect {
        that(response.getString("repoURL")).isEqualTo(expected.getString("repoURL"))
        that(response.getString("forceReset")).isEqualTo(expected.getString("forceReset"))
      }
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
