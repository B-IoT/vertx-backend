package ch.biot.backend.publicapi

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.HttpResponse

internal fun sendStatusCode(ctx: RoutingContext, code: Int) = ctx.response().setStatusCode(code).end()

internal fun forwardJsonObjectOrStatusCode(ctx: RoutingContext, resp: HttpResponse<JsonObject>) {
  if (resp.statusCode() != 200) {
    sendStatusCode(ctx, resp.statusCode())
  } else {
    ctx.response()
      .putHeader("Content-Type", "application/json")
      .end(resp.body().encode())
  }
}

internal fun forwardJsonArrayOrStatusCode(ctx: RoutingContext, resp: HttpResponse<JsonArray>) {
  if (resp.statusCode() != 200) {
    sendStatusCode(ctx, resp.statusCode())
  } else {
    ctx.response()
      .putHeader("Content-Type", "application/json")
      .end(resp.body().encode())
  }
}

internal fun sendBadGateway(ctx: RoutingContext, error: Throwable) {
  PublicApiVerticle.logger.error("Oops... an error occurred!", error)
  ctx.fail(502)
}
