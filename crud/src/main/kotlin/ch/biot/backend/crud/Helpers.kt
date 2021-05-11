/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud

import ch.biot.backend.crud.CRUDVerticle.Companion.INTERNAL_SERVER_ERROR_CODE
import ch.biot.backend.crud.CRUDVerticle.Companion.LOGGER
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoAuthentication
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.sqlclient.Row
import java.security.SecureRandom
import java.util.*

/**
 * Validates the JSON object and executes the given block. The request is failed through the given [RoutingContext] if
 * the JSON is null or empty.
 *
 * @param ctx the routing context corresponding to the request handled
 * @param block the block of code to execute after validation. It takes this JSON object as argument
 */
internal suspend fun JsonObject?.validateAndThen(ctx: RoutingContext, block: suspend (JsonObject) -> Unit) {
  when {
    this == null -> {
      LOGGER.warn("Bad request with null body")
      ctx.fail(400)
    }
    this.isEmpty -> {
      LOGGER.warn("Bad request with empty body")
      ctx.fail(400)
    }
    this.containsKey("company") && !this.getString("company").matches("^[a-zA-Z]+$".toRegex()) -> {
      LOGGER.warn("Bad request with wrongly formatted company")
      ctx.fail(400)
    }
    else -> block(this)
  }
}

/**
 * Cleans the JSON object, removing the "_id" field and formatting the "lastModified" field.
 *
 * @return the cleaned JSON object
 */
internal fun JsonObject.clean(): JsonObject = this.copy().apply {
  remove("_id")
  cleanLastModified()
}

/**
 * Cleans the JSON object to be shared with a relay, removing multiple fields and formatting the "lastModified" field.
 *
 * @return the cleaned JSON object
 */
internal fun JsonObject.cleanForRelay(): JsonObject = this.copy().apply {
  clean()
  remove("mqttUsername")
  remove("mqttPassword")
}

/**
 * Cleans, if present, the "lastModified" field, keeping just the date and time information.
 */
internal fun JsonObject.cleanLastModified() {
  if (containsKey("lastModified")) {
    val lastModifiedObject: JsonObject = this["lastModified"]
    put("lastModified", lastModifiedObject["\$date"])
  }
}

/**
 * Hashes the string (a password), using a randomly generated salt and the PBKDF2 algorithm.
 *
 * @param mongoAuth the [MongoAuthentication] object used to hash the string
 * @return the salted and hashed string
 */
internal fun String.saltAndHash(mongoAuth: MongoAuthentication): String {
  val salt = ByteArray(16)
  SecureRandom().nextBytes(salt)
  return mongoAuth.hash("pbkdf2", String(Base64.getEncoder().encode(salt)), this)
}

/**
 * Converts the row to a JSON representation corresponding to an item.
 */
internal fun Row.toItemJson(): JsonObject = jsonObjectOf(
  "id" to getInteger("id"),
  "beacon" to getString("beacon"),
  "category" to getString("category"),
  "service" to getString("service"),
  "timestamp" to getOffsetDateTime("time")?.toString(),
  "battery" to getInteger("battery"),
  "status" to getString("status"),
  "latitude" to getDouble("latitude"),
  "longitude" to getDouble("longitude"),
  "floor" to getInteger("floor")
// TODO extract new information
)

/**
 * Returns the right collection (or table) to use based on the user's company and baseCollectionName provided.
 *
 * @param baseCollectionName the name of the base collection, such as "relays" or "items"
 * @return the name of the collection (or table) to use
 */
internal fun RoutingContext.getCollection(baseCollectionName: String): String {
  val company = this.queryParams()["company"]
  return if (company != null && company != "biot") "${baseCollectionName}_$company" else baseCollectionName
}

/**
 * Executes the given suspend block, catching any eventual error and failing the request through the context.
 *
 * @param errorMessage the error message to display
 * @param ctx the context used to route the request
 * @param block the suspend block to execute, wrapped in a try-catch clause
 */
internal suspend fun executeWithErrorHandling(errorMessage: String, ctx: RoutingContext, block: suspend () -> Unit) =
  try {
    block()
  } catch (error: Throwable) {
    LOGGER.error(errorMessage, error)
    ctx.fail(INTERNAL_SERVER_ERROR_CODE, error)
  }
