/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud

import ch.biot.backend.crud.CRUDVerticle.Companion.BAD_REQUEST_CODE
import ch.biot.backend.crud.CRUDVerticle.Companion.INTERNAL_SERVER_ERROR_CODE
import ch.biot.backend.crud.queries.searchForTable
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoAuthentication
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.security.SecureRandom
import java.time.LocalDate
import java.util.*

/**
 * Validates the JSON object and executes the given block. The request is failed through the given [RoutingContext] if
 * the JSON is null or empty.
 *
 * @param ctx the routing context corresponding to the request handled
 * @param block the block of code to execute after validation. It takes this JSON object as argument
 */
suspend fun JsonObject?.validateAndThen(ctx: RoutingContext, block: suspend (JsonObject) -> Unit) {
  when {
    this == null -> {
      LOGGER.warn { "Bad request with null body" }
      ctx.fail(BAD_REQUEST_CODE)
    }
    this.isEmpty -> {
      LOGGER.warn { "Bad request with empty body" }
      ctx.fail(BAD_REQUEST_CODE)
    }
    this.containsKey("company") && !this.getString("company").matches("^[a-zA-Z]+$".toRegex()) -> {
      LOGGER.warn { "Bad request with wrongly formatted company" }
      ctx.fail(BAD_REQUEST_CODE)
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
fun String.saltAndHash(mongoAuth: MongoAuthentication): String {
  val salt = ByteArray(16)
  SecureRandom().nextBytes(salt)
  return mongoAuth.hash("pbkdf2", String(Base64.getEncoder().encode(salt)), this)
}

/**
 * Converts the json to a JSON representation corresponding to an item.
 */
fun JsonObject.toItemJson(): JsonObject = jsonObjectOf(
  "id" to getInteger("id"),
  "beacon" to getString("beacon"),
  "category" to getString("category"),
  "service" to getString("service"),
  "itemID" to getString("itemid"),
  "brand" to getString("brand"),
  "model" to getString("model"),
  "supplier" to getString("supplier"),
  "purchaseDate" to getString("purchasedate"),
  "purchasePrice" to getDouble("purchaseprice"),
  "originLocation" to getString("originlocation"),
  "currentLocation" to getString("currentlocation"),
  "room" to getString("room"),
  "contact" to getString("contact"),
  "currentOwner" to getString("currentowner"),
  "previousOwner" to getString("previousowner"),
  "orderNumber" to getString("ordernumber"),
  "color" to getString("color"),
  "serialNumber" to getString("serialnumber"),
  "maintenanceDate" to getString("maintenancedate"),
  "status" to getString("status"),
  "comments" to getString("comments"),
  "lastModifiedDate" to getString("lastmodifieddate"),
  "lastModifiedBy" to getString("lastmodifiedby")
)

/**
 * Converts the row to a JSON representation corresponding to an item.
 */
fun Row.toItemJson(includeBeaconData: Boolean = true): JsonObject {
  val json = jsonObjectOf(
    "id" to getInteger("id"),
    "beacon" to getString("beacon"),
    "category" to getString("category"),
    "service" to getString("service"),
    "itemID" to getString("itemid"),
    "brand" to getString("brand"),
    "model" to getString("model"),
    "supplier" to getString("supplier"),
    "purchaseDate" to getLocalDate("purchasedate")?.toString(),
    "purchasePrice" to getDouble("purchaseprice"),
    "originLocation" to getString("originlocation"),
    "currentLocation" to getString("currentlocation"),
    "room" to getString("room"),
    "contact" to getString("contact"),
    "currentOwner" to getString("currentowner"),
    "previousOwner" to getString("previousowner"),
    "orderNumber" to getString("ordernumber"),
    "color" to getString("color"),
    "serialNumber" to getString("serialnumber"),
    "maintenanceDate" to getLocalDate("maintenancedate")?.toString(),
    "comments" to getString("comments"),
    "lastModifiedDate" to getLocalDate("lastmodifieddate")?.toString(),
    "lastModifiedBy" to getString("lastmodifiedby"),
    "status" to getString("status")
  )

  if (includeBeaconData) {
    json.mergeIn(
      jsonObjectOf(
        "timestamp" to getOffsetDateTime("time")?.toString(),
        "battery" to getInteger("battery"),
        "beaconStatus" to getString("beaconstatus"),
        "latitude" to getDouble("latitude"),
        "longitude" to getDouble("longitude"),
        "floor" to getInteger("floor"),
        "temperature" to getDouble("temperature")
      )
    )
  }

  return json
}

/**
 * Extracts the relevant item information from a given json, returning a list of pairs from column name to column value.
 */
internal fun extractItemInformation(json: JsonObject, keepNulls: Boolean = true): List<Pair<String, Any?>> {
  val beacon: String? = json["beacon"]
  val category: String? = json["category"]
  val service: String? = json["service"]
  val itemID: String? = json["itemID"]
  val brand: String? = json["brand"]
  val model: String? = json["model"]
  val supplier: String? = json["supplier"]
  val purchaseDate: String? = json["purchaseDate"]
  val purchasePrice: Double? = json.getDouble("purchasePrice")
  val originLocation: String? = json["originLocation"]
  val currentLocation: String? = json["currentLocation"]
  val room: String? = json["room"]
  val contact: String? = json["contact"]
  val currentOwner: String? = json["currentOwner"]
  val previousOwner: String? = json["previousOwner"]
  val orderNumber: String? = json["orderNumber"]
  val color: String? = json["color"]
  val serialNumber: String? = json["serialNumber"]
  val maintenanceDate: String? = json["maintenanceDate"]
  val status: String? = json["status"]
  val comments: String? = json["comments"]
  val lastModifiedDate: String? = json["lastModifiedDate"]
  val lastModifiedBy: String? = json["lastModifiedBy"]

  val infoList = listOf(
    "beacon" to beacon,
    "category" to category,
    "service" to service,
    "itemid" to itemID,
    "brand" to brand,
    "model" to model,
    "supplier" to supplier,
    "purchasedate" to purchaseDate?.let(LocalDate::parse),
    "purchaseprice" to purchasePrice,
    "originlocation" to originLocation,
    "currentlocation" to currentLocation,
    "room" to room,
    "contact" to contact,
    "currentowner" to currentOwner,
    "previousowner" to previousOwner,
    "ordernumber" to orderNumber,
    "color" to color,
    "serialnumber" to serialNumber,
    "maintenancedate" to maintenanceDate?.let(LocalDate::parse),
    "status" to status,
    "comments" to comments,
    "lastmodifieddate" to lastModifiedDate?.let(LocalDate::parse),
    "lastmodifiedby" to lastModifiedBy
  )

  return if (keepNulls) {
    infoList
  } else {
    infoList.toList().filter { pair -> pair.second != null }
  }
}

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
suspend fun executeWithErrorHandling(errorMessage: String, ctx: RoutingContext, block: suspend () -> Unit) =
  try {
    block()
  } catch (error: Throwable) {
    LOGGER.error(error) { errorMessage }
    ctx.fail(INTERNAL_SERVER_ERROR_CODE, error)
  }

/**
 * Returns true if the given table exists in the database, false otherwise.
 *
 * @param tableName the table name
 */
suspend fun SqlClient.tableExists(tableName: String): Boolean =
  preparedQuery(searchForTable()).execute(Tuple.of(tableName)).await().iterator().hasNext()
