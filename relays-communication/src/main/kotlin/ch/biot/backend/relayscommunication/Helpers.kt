package ch.biot.backend.relayscommunication

import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get

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
  remove("latitude")
  remove("longitude")
  if (containsKey("lastModified")) {
    val lastModifiedObject: JsonObject = this["lastModified"]
    put("lastModified", lastModifiedObject["\$date"])
  }
}
