/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.relayscommunication

import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await

private const val RELAY_ID_COLLECTION = "relaysIDs"

/**
 * Cleans the JSON object by removing useless fields and formatting the "lastModified" field.
 *
 * @return the cleaned JSON object
 */
internal fun JsonObject.clean(): JsonObject = this.copy().apply {
  remove("_id")
  remove("mqttID")
  remove("mqttUsername")
  remove("mqttPassword")
  if (containsKey("beacon")) {
    remove("beacon") // TODO remove when supported
  }
  remove("ledStatus") // TODO remove when supported
  if (containsKey("lastModified")) {
    val lastModifiedObject: JsonObject = this["lastModified"]
    put("lastModified", lastModifiedObject["\$date"])
  }
}

/**
 * Reads the next relayID to use for a new relay. It is a Future otherwise it cannot be used in the tests.
 *
 * @return the next relayID
 */
internal fun MongoClient.readNextRelayID(): Future<Int> =
  this.findOne(RELAY_ID_COLLECTION, jsonObjectOf(), jsonObjectOf()).compose { relayIDObject ->
    LOGGER.info { "Read relayIDJson $relayIDObject" }
    Future.succeededFuture(relayIDObject.getInteger("id"))
  }


/**
 * Increments the next relayID and returns it.
 */
internal suspend fun MongoClient.incrementNextRelayID() {
  val updateJson = jsonObjectOf(
    "\$set" to jsonObjectOf("id" to readNextRelayID().await() + 1),
    "\$currentDate" to jsonObjectOf("lastModified" to true)
  )
  val incrementedRelayID =
    this.findOneAndUpdate(RELAY_ID_COLLECTION, jsonObjectOf(), updateJson).await().getInteger("id")
  LOGGER.info { "Incremented next relayID to $incrementedRelayID" }
}
