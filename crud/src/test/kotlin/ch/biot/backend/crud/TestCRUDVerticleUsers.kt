/*
 * Copyright (c) 2021 BioT. All rights reserved.
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
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import io.vertx.kotlin.ext.mongo.indexOptionsOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import strikt.api.expectThat
import strikt.assertions.*
import java.io.File

@ExtendWith(VertxExtension::class)
@Testcontainers
class TestCRUDVerticleUsers {

  private lateinit var mongoClient: MongoClient
  private lateinit var mongoUserUtil: MongoUserUtil
  private lateinit var mongoAuth: MongoAuthentication

  private val password = "password"
  private val existingUser = jsonObjectOf(
    "userID" to "test",
    "username" to "test",
    "password" to "test",
    "company" to "biot"
  )
  private val newUser = jsonObjectOf(
    "userID" to "test2",
    "username" to "test2",
    "password" to password,
    "company" to "biot"
  )

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    mongoClient =
      MongoClient.createShared(
        vertx,
        jsonObjectOf("host" to "localhost", "port" to CRUDVerticle.MONGO_PORT, "db_name" to "clients")
      )

    val usernameField = "username"
    val passwordField = "password"
    val mongoAuthOptions = mongoAuthenticationOptionsOf(
      collectionName = "users",
      passwordCredentialField = passwordField,
      passwordField = passwordField,
      usernameCredentialField = usernameField,
      usernameField = usernameField
    )

    mongoUserUtil = MongoUserUtil.create(
      mongoClient, mongoAuthOptions, mongoAuthorizationOptionsOf()
    )
    mongoAuth = MongoAuthentication.create(mongoClient, mongoAuthOptions)

    try {
      mongoClient.createIndexWithOptions("users", jsonObjectOf("userID" to 1), indexOptionsOf().unique(true)).await()
      mongoClient.createIndexWithOptions("users", jsonObjectOf("username" to 1), indexOptionsOf().unique(true)).await()
      dropAllUsers().await()
      insertUser().await()
      vertx.deployVerticle(CRUDVerticle(), testContext.succeedingThenComplete())
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  private fun dropAllUsers() = mongoClient.removeDocuments("users", jsonObjectOf())

  private suspend fun insertUser(): Future<JsonObject> {
    val hashedPassword = password.saltAndHash(mongoAuth)
    val docID = mongoUserUtil.createHashedUser("test", hashedPassword).await()
    val query = jsonObjectOf("_id" to docID)
    val extraInfo = jsonObjectOf(
      "\$set" to existingUser
    )
    return mongoClient.findOneAndUpdate("users", query, extraInfo)
  }

  @AfterEach
  fun cleanup(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    try {
      dropAllUsers().await()
      mongoClient.close().await()
      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  @DisplayName("registerUser correctly registers a new user")
  fun registerIsCorrect(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(newUser.encode())
    } When {
      post("/users")
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
  @DisplayName("registerUser fails with wrongly formatted company")
  fun registerUserFailsWithWronglyFormattedCompany(testContext: VertxTestContext) {
    val wrongUser = jsonObjectOf(
      "userID" to "wrong",
      "username" to "wrong",
      "password" to "wrong",
      "company" to "wrong company"
    )

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(wrongUser.encode())
    } When {
      post("/users")
    } Then {
      statusCode(400)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo("Bad Request")
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getUsers correctly retrieves all users")
  fun getUsersIsCorrect(testContext: VertxTestContext) {
    val expected = jsonArrayOf(existingUser.copy().apply { remove("password") })

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        get("/users")
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
  @DisplayName("getUser correctly retrieves the desired user")
  fun getUserIsCorrect(testContext: VertxTestContext) {
    val expected = existingUser.copy().apply { remove("password") }

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        accept(ContentType.JSON)
      } When {
        get("/users/test")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      val password = response.remove("password")
      expectThat(response).isEqualTo(expected)
      expectThat(password).isNotNull()
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("updateUser correctly updates the desired user")
  fun updateUserIsCorrect(testContext: VertxTestContext) {
    val newPassword = "newPassword"
    val updateJson = jsonObjectOf(
      "password" to newPassword
    )

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(updateJson.encode())
    } When {
      put("/users/test")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEmpty()
    }

    val updatedUser = existingUser.copy().apply {
      put("password", newPassword)
    }

    val expected = jsonObjectOf("company" to existingUser["company"])

    val responseAuth = Buffer.buffer(
        Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        body(updatedUser.encode())
        accept(ContentType.JSON)
      } When {
        post("/users/authenticate")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(responseAuth).isNotNull()
      expectThat(responseAuth.isEmpty).isFalse()

      val uuid = responseAuth.remove("sessionUuid")

      expectThat(responseAuth).isEqualTo(expected)
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("authenticate correctly authenticates and returns the user's company and an UUID")
  fun authenticateIsCorrect(testContext: VertxTestContext) {
    val userJson = jsonObjectOf(
      "username" to "username",
      "password" to "password",
      "company" to "test"
    )
    val expected = jsonObjectOf("company" to "test")

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(userJson.encode())
    } When {
      post("/users")
    }

    val response = Buffer.buffer(
        Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        body(userJson.encode())
        accept(ContentType.JSON)
      } When {
        post("/users/authenticate")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response.isEmpty).isFalse()

      val uuid = response.remove("sessionUuid")

      expectThat(response).isEqualTo(expected)
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("authenticate session correctly authenticates a session with the valid UUID returned by authenticate")
  fun authenticateSessionWithCorrectUUIDIsCorrect(testContext: VertxTestContext) {
    val userJson = jsonObjectOf(
      "username" to "username",
      "password" to "password",
      "company" to "test"
    )

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(userJson.encode())
    } When {
      post("/users")
    }

    val response1 = Buffer.buffer(
      Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        body(userJson.encode())
        accept(ContentType.JSON)
      } When {
        post("/users/authenticate")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response1).isNotNull()
      expectThat(response1.isEmpty).isFalse()
      expectThat(response1.containsKey("sessionUuid")).isTrue()
    }
    val sessionUuid: String = response1["sessionUuid"]

    val expected = jsonObjectOf("company" to "test", "sessionUuid" to sessionUuid)
    val sessionAuthJson = userJson.copy().apply {
      remove("password")
      put("sessionUuid", sessionUuid)
    }

    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        body(sessionAuthJson.encode())
        accept(ContentType.JSON)
      } When {
        post("/users/authenticate/session")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response.isEmpty).isFalse()

      expectThat(response).isEqualTo(expected)
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("authenticate session fails to authenticate a session with an invalid UUID")
  fun authenticateSessionWithIncorrectUUIDFails(testContext: VertxTestContext) {
    val userJson = jsonObjectOf(
      "username" to "username",
      "password" to "password",
      "company" to "test"
    )

    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(userJson.encode())
    } When {
      post("/users")
    }

    val response1 = Buffer.buffer(
      Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        body(userJson.encode())
        accept(ContentType.JSON)
      } When {
        post("/users/authenticate")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }
    ).toJsonObject()

    testContext.verify {
      expectThat(response1).isNotNull()
      expectThat(response1.isEmpty).isFalse()
      expectThat(response1.containsKey("sessionUuid")).isTrue()
    }
    val sessionUuid: String = response1["sessionUuid"]

    val expected = jsonObjectOf("company" to "test", "sessionUuid" to sessionUuid)
    val sessionAuthJson = userJson.copy().apply {
      remove("password")
      put("sessionUuid", "wrongUUID")
    }

      Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        body(sessionAuthJson.encode())
        accept(ContentType.JSON)
      } When {
        post("/users/authenticate/session")
      } Then {
        statusCode(401)
      }

    testContext.verify {
      testContext.completeNow()
    }
  }


  @Test
  @DisplayName("deleteUser correctly deletes a user")
  fun deleteIsCorrect(testContext: VertxTestContext) {
    val userToRemove = jsonObjectOf(
      "userID" to "test42",
      "username" to "test42",
      "password" to password,
      "company" to "biot"
    )

    // Register the user
    Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      accept(ContentType.JSON)
      body(userToRemove.encode())
    } When {
      post("/users")
    } Then {
      statusCode(200)
    }

    // Delete the user
    val response = Given {
      spec(requestSpecification)
    } When {
      delete("/users/${userToRemove.getString("userID")}")
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
