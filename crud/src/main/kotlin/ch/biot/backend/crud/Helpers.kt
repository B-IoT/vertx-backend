package ch.biot.backend.crud

import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoAuthentication
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.sqlclient.Row
import java.security.SecureRandom
import java.util.*

internal fun JsonObject?.validateAndThen(ctx: RoutingContext, block: (JsonObject) -> Unit) {
  when {
    this == null -> {
      CRUDVerticle.logger.warn("Bad request with null body")
      ctx.fail(400)
    }
    this.isEmpty -> {
      CRUDVerticle.logger.warn("Bad request with empty body")
      ctx.fail(400)
    }
    else -> block(this)
  }
}

internal fun JsonObject.clean(): JsonObject = this.copy().apply {
  remove("_id")
  cleanLastModified()
}

internal fun JsonObject.cleanForRelay(): JsonObject = this.copy().apply {
  clean()
  remove("mqttUsername")
  remove("mqttPassword")
  remove("latitude")
  remove("longitude")
}

internal fun JsonObject.cleanLastModified() {
  if (containsKey("lastModified")) {
    val lastModifiedObject: JsonObject = this["lastModified"]
    put("lastModified", lastModifiedObject["\$date"])
  }
}

internal fun String.saltAndHash(mongoAuth: MongoAuthentication): String {
  val salt = ByteArray(16)
  SecureRandom().nextBytes(salt)
  return mongoAuth.hash("pbkdf2", String(Base64.getEncoder().encode(salt)), this)
}

internal fun Row.buildItemJson(): JsonObject = jsonObjectOf(
  "id" to getInteger("id"),
  "beacon" to getString("beacon"),
  "category" to getString("category"),
  "service" to getString("service"),
  "timestamp" to getOffsetDateTime("time")?.toString(),
  "battery" to getInteger("battery"),
  "status" to getString("status"),
  "latitude" to getDouble("latitude"),
  "longitude" to getDouble("longitude")
)
