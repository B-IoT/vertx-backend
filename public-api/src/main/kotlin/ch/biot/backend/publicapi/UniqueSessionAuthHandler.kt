/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.publicapi

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.User
import io.vertx.ext.auth.authentication.TokenCredentials
import io.vertx.ext.auth.impl.jose.JWT
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.predicate.ResponsePredicate
import io.vertx.ext.web.handler.HttpException
import io.vertx.ext.web.handler.impl.HTTPAuthorizationHandler
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf

class UniqueSessionAuthHandler(authProvider: JWTAuth, private val webClient: WebClient) :
  HTTPAuthorizationHandler<JWTAuth>(authProvider, Type.BEARER, null) {
  override fun authenticate(context: RoutingContext, handler: Handler<AsyncResult<User>>?) {
    handler?.let {
      LOGGER.debug { "Custom auth handler for session" }
      parseAuthorization(context) { parseAuthorization: AsyncResult<String> ->
        if (parseAuthorization.failed()) {
          handler.handle(Future.failedFuture(parseAuthorization.cause()))
          return@parseAuthorization
        }
        val token = parseAuthorization.result()
        val claimsJson: JsonObject = JWT.parse(token)["payload"]
        val json = jsonObjectOf(
          "username" to claimsJson["username"],
          "company" to claimsJson["company"],
          "sessionUuid" to claimsJson["sessionUuid"]
        )

        webClient
          .post(PublicApiVerticle.CRUD_PORT, PublicApiVerticle.CRUD_HOST, "/users/authenticate/session")
          .timeout(PublicApiVerticle.TIMEOUT)
          .expect(ResponsePredicate.SC_SUCCESS)
          .sendJsonObject(json)
          .onSuccess {
            authProvider.authenticate(
              TokenCredentials(token)
            ) { authn: AsyncResult<User> ->
              if (authn.failed()) {
                // Will not fail if the other JWT authentication passes
                handler.handle(
                  Future.failedFuture(
                    HttpException(
                      401,
                      authn.cause()
                    )
                  )
                )
              } else {
                handler.handle(authn)
              }
            }
          }.onFailure {
            // The session is not valid (i.e. the user connected elsewhere in the meantime
            handler.handle(
              Future.failedFuture(
                HttpException(
                  403,
                  "Session expired: this user logged in elsewhere."
                )
              )
            )
          }
      }
    }
  }
}
