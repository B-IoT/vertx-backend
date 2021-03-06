package ch.biot.backend.publicapi

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.HttpResponse

/**
 * Sends the given status code through the given routing context.
 *
 * @param ctx the routing context
 * @param code the status code to send
 */
internal fun sendStatusCode(ctx: RoutingContext, code: Int) = ctx.response().setStatusCode(code).end()

/**
 * Forwards the response containing a JSON object if the request is successful; otherwise, it only sends the status code.
 *
 * @param ctx the routing context
 * @param resp the [HttpResponse] containing a JSON object
 */
internal fun forwardJsonObjectOrStatusCode(ctx: RoutingContext, resp: HttpResponse<JsonObject>) {
  if (resp.statusCode() != 200) {
    sendStatusCode(ctx, resp.statusCode())
  } else {
    ctx.response()
      .putHeader("Content-Type", "application/json")
      .end(resp.body().encode())
  }
}

/**
 * Forwards the response containing a JSON array if the request is successful; otherwise, it only sends the status code.
 *
 * @param ctx the routing context
 * @param resp the [HttpResponse] containing a JSON array
 */
internal fun forwardJsonArrayOrStatusCode(ctx: RoutingContext, resp: HttpResponse<JsonArray>) {
  if (resp.statusCode() != 200) {
    sendStatusCode(ctx, resp.statusCode())
  } else {
    ctx.response()
      .putHeader("Content-Type", "application/json")
      .end(resp.body().encode())
  }
}

/**
 * Sends a HTTP 502: Bad Gateway error through the given context.
 *
 * @param ctx the routing context
 * @param error the error that occurred
 */
internal fun sendBadGateway(ctx: RoutingContext, error: Throwable) {
  PublicApiVerticle.logger.error("Oops... an error occurred!", error)
  ctx.fail(502, error)
}
