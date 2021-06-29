/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud.updates

import io.vertx.core.eventbus.EventBus
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.eventbus.deliveryOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await

/**
 * Manager used to send real-time updates over the event bus to the connected clients.
 */
class UpdatesManager(private val eventBus: EventBus) {

  companion object {
    private const val ADDRESS = "items.updates"
    private const val OK = "OK"
    private const val SEND_TIMEOUT = 10000L
    private const val MAX_RETRIES = 5
  }

  /**
   * Publishes an item update of the given type for the given item with the given content.
   *
   * @param type the update type
   * @param id the item id
   * @param content the new item content. Null if the type is [UpdateType.DELETE].
   */
  suspend fun publishItemUpdate(type: UpdateType, id: Int, content: JsonObject? = null) {
    suspend fun sendWithRetry(message: JsonObject, maxRetries: Int, attempts: Int = 0) {
      if (maxRetries == attempts) {
        throw UnhandledPublishedMessageException("The published update message $message was not handled")
      }

      try {
        val answer =
          eventBus.request<String>(ADDRESS, message, deliveryOptionsOf(sendTimeout = SEND_TIMEOUT)).await().body()
        if (answer != OK) {
          sendWithRetry(message, maxRetries, attempts + 1)
        }
      } catch (error: Throwable) {
        sendWithRetry(message, maxRetries, attempts + 1)
      }
    }

    val message = jsonObjectOf(
      "type" to type.toString(),
      "id" to id
    ).apply {
      if (type != UpdateType.DELETE) {
        put("content", content)
      }
    }

    sendWithRetry(message, MAX_RETRIES)
  }
}
