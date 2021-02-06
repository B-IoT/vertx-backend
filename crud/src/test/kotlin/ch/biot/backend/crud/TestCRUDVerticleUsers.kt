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
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import io.vertx.kotlin.ext.mongo.indexOptionsOf
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
  fun setup(vertx: Vertx, testContext: VertxTestContext) {
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

    mongoClient.createIndexWithOptions("users", jsonObjectOf("userID" to 1), indexOptionsOf().unique(true))
      .compose {
        mongoClient.createIndexWithOptions("users", jsonObjectOf("username" to 1), indexOptionsOf().unique(true))
      }.compose {
        dropAllUsers()
      }.compose {
        insertUser()
      }.onSuccess {
        vertx.deployVerticle(CRUDVerticle(), testContext.succeedingThenComplete())
      }.onFailure(testContext::failNow)
  }

  private fun dropAllUsers() = mongoClient.removeDocuments("users", jsonObjectOf())

  private fun insertUser(): Future<JsonObject> {
    val hashedPassword = password.saltAndHash(mongoAuth)
    return mongoUserUtil.createHashedUser("test", hashedPassword).compose { docID ->
      val query = jsonObjectOf("_id" to docID)
      val extraInfo = jsonObjectOf(
        "\$set" to existingUser
      )
      mongoClient.findOneAndUpdate("users", query, extraInfo)
    }
  }

  @AfterEach
  fun cleanup(testContext: VertxTestContext) {
    dropAllUsers().compose {
      mongoClient.close()
    }.onSuccess { testContext.completeNow() }
      .onFailure(testContext::failNow)
  }

  @Test
  @DisplayName("registerUser correctly registers a new user")
  fun registerIsCorrect(testContext: VertxTestContext) {
    val expected = newUser.copy().apply { remove("password") }

    val response = Buffer.buffer(Given {
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
    }).toJsonObject()

    testContext.verify {
      val password = response.remove("password")
      expectThat(response).isEqualTo(expected)
      expectThat(password).isNotNull()
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("getUsers correctly retrieves all users")
  fun getUsersIsCorrect(testContext: VertxTestContext) {
    val expected = jsonArrayOf(existingUser.copy().apply { remove("password") })

    val response = Buffer.buffer(Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
    } When {
      get("/users")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonArray()

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

    val response = Buffer.buffer(Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
    } When {
      get("/users/test")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonObject()

    testContext.verify {
      val password = response.remove("password")
      expectThat(response).isEqualTo(expected)
      expectThat(password).isNotNull()
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("updateUser correctly updates the desired user")
  fun updateUserIsCorrect(vertx: Vertx, testContext: VertxTestContext) {
    val updateJson = jsonObjectOf(
      "company" to "test2"
    )

    val response = Buffer.buffer(Given {
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
    }).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      expect {
        that(response.getString("company")).isEqualTo(updateJson.getString("company"))
        that(response.getString("userID")).isEqualTo(existingUser.getString("userID"))
        that(response.getString("username")).isEqualTo(existingUser.getString("username"))
        that(response.containsKey("lastModified")).isTrue()
      }
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("authenticate correctly authenticates and returns the user's company")
  fun authenticateIsCorrect(vertx: Vertx, testContext: VertxTestContext) {
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

    val response = Given {
      spec(requestSpecification)
      contentType(ContentType.JSON)
      body(userJson.encode())
    } When {
      post("/users/authenticate")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }

    testContext.verify {
      expectThat(response).isEqualTo(userJson["company"])
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
