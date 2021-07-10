/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud.updates

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vertx.core.eventbus.EventBus
import io.vertx.kotlin.core.json.jsonObjectOf
import org.junit.jupiter.api.Test
import strikt.api.expectThrows

class TestUpdatesManager {

  @Test
  fun publishItemUpdateUsesTheEventBus() {
    val mockEventBus = mockk<EventBus>()
    val updatesManager = UpdatesManager(mockEventBus)
    every { mockEventBus.publish(any(), any(), any()) } returns mockEventBus

    val updateType = UpdateType.POST
    val company = "company"
    val id = 42
    val content = jsonObjectOf("test" to "a test")
    val expectedJson = jsonObjectOf(
      "type" to updateType.toString(),
      "id" to id,
      "content" to content
    )

    updatesManager.publishItemUpdate(updateType, company, id, content)

    verify { mockEventBus.publish("items.updates.company", expectedJson, any()) }
    confirmVerified(mockEventBus)
  }

  @Test
  fun publishItemUpdateFailsWithExceptionAfterRetries() {
    val mockEventBus = mockk<EventBus>()
    val updatesManager = UpdatesManager(mockEventBus)
    every { mockEventBus.publish(any(), any(), any()) } throws Throwable()

    val updateType = UpdateType.POST
    val company = "company"
    val id = 42
    val content = jsonObjectOf("test" to "a test")
    val expectedJson = jsonObjectOf(
      "type" to updateType.toString(),
      "id" to id,
      "content" to content
    )

    expectThrows<PublishMessageException> {
      updatesManager.publishItemUpdate(updateType, company, id, content)
    }

    verify(exactly = 5) { mockEventBus.publish("items.updates.company", expectedJson, any()) }
    confirmVerified(mockEventBus)
  }
}
