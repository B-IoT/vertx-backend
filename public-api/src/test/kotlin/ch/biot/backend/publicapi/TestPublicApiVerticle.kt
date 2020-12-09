/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

package ch.biot.backend.publicapi

import ch.biot.backend.crud.CRUDVerticle
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
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import strikt.api.expectThat
import strikt.assertions.*
import java.io.File


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
    "company" to "biot"
  )

  private lateinit var token: String

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext) {
    vertx.deployVerticle(PublicApiVerticle())
      .compose {
        vertx.deployVerticle(CRUDVerticle())
      }
      .onSuccess {
        testContext.completeNow()
      }
      .onFailure(testContext::failNow)
  }

  @Test
  @Order(1)
  @DisplayName("Registering a user succeeds")
  fun registerUserSucceeds(testContext: VertxTestContext) {
    val response = Given {
      spec(requestSpecification)
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
  @Order(2)
  @DisplayName("Getting the token for a registered user succeeds")
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
  @Order(3)
  @DisplayName("Getting the token with wrong credentials failss")
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
  @Order(4)
  @DisplayName("Getting the users succeeds")
  fun getUsersSucceeds(testContext: VertxTestContext) {
    val expected = jsonArrayOf(user.copy().apply { remove("password") })

    val response = Buffer.buffer(Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
    } When {
      get("/api/users")
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
  @Order(5)
  @DisplayName("Getting a user succeeds")
  fun getUserSucceeds(testContext: VertxTestContext) {
    val expected = user.copy().apply { remove("password") }

    val response = Buffer.buffer(Given {
      spec(requestSpecification)
      accept(ContentType.JSON)
      header("Authorization", "Bearer $token")
    } When {
      get("/api/users/${user.getString("userID")}")
    } Then {
      statusCode(200)
    } Extract {
      asString()
    }).toJsonObject()

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
      "company" to "biot2"
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

  // TODO add tests for relays

  companion object {

    private val requestSpecification: RequestSpecification = RequestSpecBuilder()
      .addFilters(listOf(ResponseLoggingFilter(), RequestLoggingFilter()))
      .setBaseUri("http://localhost")
      .setPort(4000)
      .build()

    private val instance: KDockerComposeContainer by lazy { defineDockerCompose() }

    class KDockerComposeContainer(file: File) : DockerComposeContainer<KDockerComposeContainer>(file)

    private fun defineDockerCompose() = KDockerComposeContainer(File("../docker-compose.yml")).withExposedService(
      "mongo_1",
      27017
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
