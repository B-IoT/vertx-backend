/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud

import ch.biot.backend.crud.CRUDVerticle.Companion.BAD_REQUEST_CODE
import ch.biot.backend.crud.CRUDVerticle.Companion.INTERNAL_SERVER_ERROR_CODE
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File

@ExtendWith(VertxExtension::class)
@Testcontainers
class TestHelpers {

  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext) = runBlocking(vertx.dispatcher()) {
    try {
      vertx.deployVerticle(CRUDVerticle()).await()
      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  fun executeWithErrorHandlingHandlesErrors(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
    val routingContext = mockk<RoutingContext>()
    val suspendBlock = suspend {
      throw Throwable("throwable")
    }
    every { routingContext.fail(INTERNAL_SERVER_ERROR_CODE, any()) } returns Unit

    executeWithErrorHandling("An error", routingContext, suspendBlock)

    verify { routingContext.fail(INTERNAL_SERVER_ERROR_CODE, any()) }
    confirmVerified(routingContext)
  }

  @Test
  fun validateAndThenFailsOnNullJson(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
    val routingContext = mockk<RoutingContext>()
    every { routingContext.fail(BAD_REQUEST_CODE) } returns Unit

    val json: JsonObject? = null

    json.validateAndThen(routingContext) {
      // empty
    }

    verify { routingContext.fail(BAD_REQUEST_CODE) }
    confirmVerified(routingContext)
  }

  @Test
  fun validateAndThenFailsOnEmptyJson(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
    val routingContext = mockk<RoutingContext>()
    every { routingContext.fail(BAD_REQUEST_CODE) } returns Unit

    val json = jsonObjectOf()

    json.validateAndThen(routingContext) {
      // empty
    }

    verify { routingContext.fail(BAD_REQUEST_CODE) }
    confirmVerified(routingContext)
  }

  companion object {

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
