/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.publicapi

import arrow.core.Either
import ch.biot.backend.publicapi.PublicApiVerticle.Companion.OK_CODE
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.FileSystem
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.HttpRequest
import io.vertx.ext.web.client.HttpResponse
import io.vertx.kotlin.coroutines.await

internal const val NO_AC_IN_CTX_ERROR_MSG = "Cannot get the accessControlString from the context."

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
  if (resp.statusCode() != OK_CODE) {
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
  if (resp.statusCode() != OK_CODE) {
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
  LOGGER.error(error) { "Oops... an error occurred!" }
  ctx.fail(502, error)
}

/**
 * Gets the access control string from the context or fails by sending a 502 Bad Gateway.
 *
 * @return the access control string if any, otherwise null
 */
internal fun RoutingContext.getAccessControlStringOrFail(): String? {
  val acString = this.get<String>("accessControlString")
  if (acString == null) {
    sendBadGateway(this, Error(NO_AC_IN_CTX_ERROR_MSG))
    return null
  }

  return acString
}

/**
 * Suspend equivalent of [FileSystem.readFile].
 */
internal suspend fun FileSystem.coroutineReadFile(path: String): Buffer = readFile(path).await()

/**
 * Suspend equivalent of [HttpRequest.send].
 */
internal suspend fun <T> HttpRequest<T>.coroutineSend(): Either<InternalErrorException, HttpResponse<T>> =
  try {
    val result = send().await()
    Either.Right(result)
  } catch (error: Throwable) {
    Either.Left(InternalErrorException("Internal server error:\n${error.stackTraceToString()}", error.cause))
  }

/**
 * Suspend equivalent of [HttpRequest.sendBuffer].
 */
internal suspend fun <T> HttpRequest<T>.coroutineSendBuffer(buffer: Buffer): Either<InternalErrorException, HttpResponse<T>> =
  try {
    val result = sendBuffer(buffer).await()
    Either.Right(result)
  } catch (error: Throwable) {
    Either.Left(InternalErrorException("Internal server error:\n${error.stackTraceToString()}", error.cause))
  }

/**
 * Suspend equivalent of [HttpRequest.sendJsonObject].
 */
internal suspend fun <T> HttpRequest<T>.coroutineSendJsonObject(json: JsonObject): Either<InternalErrorException, HttpResponse<T>> =
  try {
    val result = sendJsonObject(json).await()
    Either.Right(result)
  } catch (error: Throwable) {
    Either.Left(InternalErrorException("Internal server error:\n${error.stackTraceToString()}", error.cause))
  }
