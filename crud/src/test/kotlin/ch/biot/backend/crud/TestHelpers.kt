/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud

import ch.biot.backend.crud.CRUDVerticle.Companion.BAD_REQUEST_CODE
import ch.biot.backend.crud.CRUDVerticle.Companion.INTERNAL_SERVER_ERROR_CODE
import ch.biot.backend.crud.CRUDVerticle.Companion.MAX_ACCESS_CONTROL_STRING_LENGTH
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
import strikt.api.expectThat
import strikt.assertions.isFalse
import strikt.assertions.isTrue
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

  @Test
  fun validateAccessControlStringAcceptsValidStrings(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
    val company = "biot"

    expectThat(validateAccessControlString("biot", company)).isTrue()
    expectThat(validateAccessControlString("biot:group1", company)).isTrue()
    expectThat(validateAccessControlString("biot:group1:group2", company)).isTrue()
    expectThat(validateAccessControlString("biot:group1:group1", company)).isTrue()
    val longNameGroups = "a".repeat(MAX_ACCESS_CONTROL_STRING_LENGTH - "biot:".length)
    expectThat(validateAccessControlString("biot:$longNameGroups", company)).isTrue()
  }

  @Test
  fun validateAccessControlStringRejectsInvalidStrings(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
    val company = "biot"

    expectThat(validateAccessControlString("biot12", company)).isFalse()
    expectThat(validateAccessControlString("biot12:group1", company)).isFalse()
    expectThat(validateAccessControlString("biot:group1:group2 ", company)).isFalse()
    expectThat(validateAccessControlString("biot:grou p1:group1", company)).isFalse()
    expectThat(validateAccessControlString("", company)).isFalse()
    expectThat(validateAccessControlString("biot12", "biot12")).isFalse()
    expectThat(validateAccessControlString("biot:group1:group1:", company)).isFalse()
    expectThat(validateAccessControlString("biot:group1:$$123", company)).isFalse()
    expectThat(validateAccessControlString("biot:group1:*", company)).isFalse()
    expectThat(validateAccessControlString("biot:group1:#", company)).isFalse()
    expectThat(validateAccessControlString("biot:group1:çava", company)).isFalse()

    val longNameGroups = "a".repeat(MAX_ACCESS_CONTROL_STRING_LENGTH - "biot:".length + 1)
    expectThat(validateAccessControlString("biot:$longNameGroups", company)).isFalse() // Too long i.e. 2049 chars
  }

  @Test
  fun hasAcStringAccessAuthorizesCorrectStrings(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
    val company = "biot"

    val itemAcString = "biot:grp1:grp2:grp3"

    expectThat(hasAcStringAccess("biot", itemAcString)).isTrue()
    expectThat(hasAcStringAccess("biot:grp1", itemAcString)).isTrue()
    expectThat(hasAcStringAccess("biot:grp1:grp2", itemAcString)).isTrue()
    expectThat(hasAcStringAccess("biot:grp1:grp2:grp3", itemAcString)).isTrue()
  }

  @Test
  fun hasAcStringAccessRefusesIncorrectStrings(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
    val company = "biot"

    val itemAcString = "biot:grp1:grp2:grp3"

    expectThat(hasAcStringAccess("bio", itemAcString)).isFalse()
    expectThat(hasAcStringAccess("biot:gr", itemAcString)).isFalse()
    expectThat(hasAcStringAccess("biot:g", itemAcString)).isFalse()
    expectThat(hasAcStringAccess("biot:grp2", itemAcString)).isFalse()
    expectThat(hasAcStringAccess("biot:gpr1:grp5", itemAcString)).isFalse()
    expectThat(hasAcStringAccess("biot:grp1:grp2:grp", itemAcString)).isFalse()
    expectThat(hasAcStringAccess("biot:grp1:grp2:grp3:grp4", itemAcString)).isFalse()
    expectThat(hasAcStringAccess("biot:grp2:grp1", itemAcString)).isFalse()
    expectThat(hasAcStringAccess("biot:grp1:grp6", itemAcString)).isFalse()
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
