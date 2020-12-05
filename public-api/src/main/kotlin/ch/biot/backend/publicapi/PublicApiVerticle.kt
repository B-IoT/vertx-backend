/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

package ch.biot.backend.publicapi

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.http.HttpMethod
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.predicate.ResponsePredicate
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.JWTAuthHandler
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.ext.auth.jwt.jwtAuthOptionsOf
import io.vertx.kotlin.ext.auth.jwtOptionsOf
import io.vertx.kotlin.ext.auth.pubSecKeyOptionsOf
import org.slf4j.LoggerFactory


class PublicApiVerticle : AbstractVerticle() {

  companion object {
    private const val API_PREFIX = "/api"
    private const val OAUTH_PREFIX = "/oauth"
    private const val PORT = 4000

    private val logger = LoggerFactory.getLogger(PublicApiVerticle::class.java)
  }

  private lateinit var webClient: WebClient
  private lateinit var jwtAuth: JWTAuth

  override fun start(startPromise: Promise<Void>?) {
    val fs = vertx.fileSystem()

    val publicKey = fs.readFileBlocking("public_key.pem").toString(Charsets.UTF_8)
    val privateKey = fs.readFileBlocking("private_key.pem").toString(Charsets.UTF_8)

    jwtAuth = JWTAuth.create(
      vertx, jwtAuthOptionsOf(
        pubSecKeys = listOf(
          pubSecKeyOptionsOf(
            algorithm = "RS256",
            buffer = publicKey
          ), pubSecKeyOptionsOf(
            algorithm = "RS256",
            buffer = privateKey
          )
        )
      )
    )

    val jwtAuthHandler = JWTAuthHandler.create(jwtAuth)

    val router = Router.router(vertx)

    val allowedHeaders =
      setOf("x-requested-with", "Access-Control-Allow-Origin", "origin", "Content-Type", "accept", "Authorization")
    val allowedMethods = setOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT)

    router.route().handler(CorsHandler.create("*").allowedHeaders(allowedHeaders).allowedMethods(allowedMethods))

    with(BodyHandler.create()) {
      router.post().handler(this)
      router.put().handler(this)
    }

    // Users
    router.post("$OAUTH_PREFIX/register").handler(this::registerHandler)
    router.post("$OAUTH_PREFIX/token").handler(this::tokenHandler)

    // TODO Relays
    router.post("$API_PREFIX/relays").handler(jwtAuthHandler)
    router.put("$API_PREFIX/relays/:id").handler(jwtAuthHandler)
    router.get("$API_PREFIX/relays").handler(jwtAuthHandler)
    router.get("$API_PREFIX/relays/:id").handler(jwtAuthHandler)

    // TODO Items

    // TODO Analytics

    webClient = WebClient.create(vertx)

    vertx.createHttpServer().requestHandler(router).listen(PORT).onComplete {
      startPromise?.complete()
    }
  }

  private fun registerHandler(ctx: RoutingContext) {
    webClient
      .post(3001, "localhost", "/register")
      .putHeader("Content-Type", "application/json")
      .sendJson(ctx.bodyAsJson)
      .onSuccess { response ->
        sendStatusCode(ctx, response.statusCode())
      }
      .onFailure { error ->
        sendBadGateway(ctx, error)
      }
  }

  private fun tokenHandler(ctx: RoutingContext) {
    val payload = ctx.bodyAsJson
    val username: String = payload["username"]

    webClient
      .post(3001, "localhost", "/authenticate")
      .expect(ResponsePredicate.SC_SUCCESS)
      .sendJson(payload)
      .onSuccess {
        val token = makeJwtToken(username)
        ctx.response().putHeader("Content-Type", "application/jwt").end(token)
      }
      .onFailure { error ->
        logger.error("Authentication error", error)
        ctx.fail(401);
      }
  }

  private fun makeJwtToken(username: String): String {
    // Expires in 7 days
    val jwtOptions = jwtOptionsOf(algorithm = "RS256", expiresInMinutes = 10080, issuer = "BIoT", subject = username)
    return jwtAuth.generateToken(jsonObjectOf(), jwtOptions)
  }

  private fun sendStatusCode(ctx: RoutingContext, code: Int) {
    ctx.response().setStatusCode(code).end()
  }

  private fun sendBadGateway(ctx: RoutingContext, error: Throwable) {
    logger.error("An error occurred while handling /register request", error)
    ctx.fail(502)
  }
}
