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
import io.vertx.ext.web.codec.BodyCodec
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
    private const val CRUD_PORT = 3000

    internal val logger = LoggerFactory.getLogger(PublicApiVerticle::class.java)
  }

  private lateinit var webClient: WebClient
  private lateinit var jwtAuth: JWTAuth

  override fun start(startPromise: Promise<Void>?) {
    val fs = vertx.fileSystem()

    // Read public and private keys from the file system. They are used for JWT authentication
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

    // CORS allowed headers and methods
    val allowedHeaders =
      setOf("x-requested-with", "Access-Control-Allow-Origin", "origin", "Content-Type", "accept", "Authorization")
    val allowedMethods = setOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT)

    router.route().handler(CorsHandler.create("*").allowedHeaders(allowedHeaders).allowedMethods(allowedMethods))

    with(BodyHandler.create()) {
      router.post().handler(this)
      router.put().handler(this)
    }

    // Users
    router.post("$OAUTH_PREFIX/register").handler(::registerUserHandler)
    router.post("$OAUTH_PREFIX/token").handler(::tokenHandler)
    router.put("$API_PREFIX/users/:id").handler(jwtAuthHandler).handler(::updateUserHandler)
    router.get("$API_PREFIX/users").handler(jwtAuthHandler).handler(::getUsersHandler)
    router.get("$API_PREFIX/users/:id").handler(jwtAuthHandler).handler(::getUserHandler)

    // Relays
    router.post("$API_PREFIX/relays").handler(jwtAuthHandler).handler(::registerRelayHandler)
    router.put("$API_PREFIX/relays/:id").handler(jwtAuthHandler).handler(::updateRelayHandler)
    router.get("$API_PREFIX/relays").handler(jwtAuthHandler).handler(::getRelaysHandler)
    router.get("$API_PREFIX/relays/:id").handler(jwtAuthHandler).handler(::getRelayHandler)

    // Items
    router.post("$API_PREFIX/items").handler(jwtAuthHandler).handler(::registerItemHandler)
    router.put("$API_PREFIX/items/:id").handler(jwtAuthHandler).handler(::updateItemHandler)
    router.get("$API_PREFIX/items").handler(jwtAuthHandler).handler(::getItemsHandler)
    router.get("$API_PREFIX/items/:id").handler(jwtAuthHandler).handler(::getItemHandler)

    // TODO Analytics

    webClient = WebClient.create(vertx)

    vertx.createHttpServer().requestHandler(router).listen(PORT).onComplete {
      startPromise?.complete()
    }
  }

  // Users

  private fun registerUserHandler(ctx: RoutingContext) = registerHandler(ctx, "users")
  private fun updateUserHandler(ctx: RoutingContext) = updateHandler(ctx, "users")
  private fun getUsersHandler(ctx: RoutingContext) = getManyHandler(ctx, "users")
  private fun getUserHandler(ctx: RoutingContext) = getOneHandler(ctx, "users")

  /**
   * Handles the token request.
   */
  private fun tokenHandler(ctx: RoutingContext) {
    fun makeJwtToken(username: String, company: String): String {
      // Add the company information to the custom claims of the token
      val claims = jsonObjectOf("company" to company)
      // The token expires in 7 days
      val jwtOptions = jwtOptionsOf(algorithm = "RS256", expiresInMinutes = 10080, issuer = "BIoT", subject = username)
      return jwtAuth.generateToken(claims, jwtOptions)
    }

    logger.info("New token request")

    val payload = ctx.bodyAsJson
    val username: String = payload["username"]

    webClient
      .post(CRUD_PORT, "localhost", "/users/authenticate")
      .expect(ResponsePredicate.SC_SUCCESS)
      .sendJsonObject(payload)
      .onSuccess { response ->
        val company = response.bodyAsString()
        val token = makeJwtToken(username, company)
        ctx.response().putHeader("Content-Type", "application/jwt").end(token)
      }
      .onFailure { error ->
        logger.error("Authentication error", error)
        ctx.fail(401)
      }
  }

  // Relays

  private fun registerRelayHandler(ctx: RoutingContext) = registerHandler(ctx, "relays")
  private fun updateRelayHandler(ctx: RoutingContext) = updateHandler(ctx, "relays")
  private fun getRelaysHandler(ctx: RoutingContext) = getManyHandler(ctx, "relays")
  private fun getRelayHandler(ctx: RoutingContext) = getOneHandler(ctx, "relays")

  // Items

  private fun registerItemHandler(ctx: RoutingContext) = registerHandler(ctx, "items", forwardResponse = true)
  private fun updateItemHandler(ctx: RoutingContext) = updateHandler(ctx, "items")
  private fun getItemsHandler(ctx: RoutingContext) = getManyHandler(ctx, "items")
  private fun getItemHandler(ctx: RoutingContext) = getOneHandler(ctx, "items")

  // Helpers

  /**
   * Handles a register request for the given endpoint. The forwardResponse parameter is set to true when the response
   * from the underlying microservice needs to be forwarded to the user. If it is set to false, only the status code is
   * sent.
   */
  private fun registerHandler(ctx: RoutingContext, endpoint: String, forwardResponse: Boolean = false) {
    logger.info("New register request on /$endpoint endpoint")

    webClient
      .post(CRUD_PORT, "localhost", "/$endpoint")
      .putHeader("Content-Type", "application/json")
      .expect(ResponsePredicate.SC_OK)
      .sendBuffer(ctx.body)
      .onSuccess { response ->
        if (forwardResponse) ctx.end(response.body())
        else sendStatusCode(ctx, response.statusCode())
      }
      .onFailure { error ->
        sendBadGateway(ctx, error)
      }
  }

  /**
   * Handles an update request for the given endpoint.
   */
  private fun updateHandler(ctx: RoutingContext, endpoint: String) {
    logger.info("New update request on /$endpoint endpoint")

    webClient
      .put(CRUD_PORT, "localhost", "/$endpoint/${ctx.pathParam("id")}")
      .putHeader("Content-Type", "application/json")
      .expect(ResponsePredicate.SC_OK)
      .sendBuffer(ctx.body)
      .onSuccess {
        ctx.end()
      }
      .onFailure { error ->
        sendBadGateway(ctx, error)
      }
  }

  /**
   * Handles a getMany request for the given endpoint.
   */
  private fun getManyHandler(ctx: RoutingContext, endpoint: String) {
    logger.info("New getMany request on /$endpoint endpoint")

    webClient
      .get(CRUD_PORT, "localhost", "/$endpoint")
      .`as`(BodyCodec.jsonArray())
      .send()
      .onSuccess { resp ->
        forwardJsonArrayOrStatusCode(ctx, resp)
      }
      .onFailure { error ->
        sendBadGateway(ctx, error)
      }
  }

  /**
   * Handles a getOne request for the given endpoint.
   */
  private fun getOneHandler(ctx: RoutingContext, endpoint: String) {
    logger.info("New getOne request on /$endpoint endpoint")

    webClient
      .get(CRUD_PORT, "localhost", "/$endpoint/${ctx.pathParam("id")}")
      .`as`(BodyCodec.jsonObject())
      .send()
      .onSuccess { resp ->
        forwardJsonObjectOrStatusCode(ctx, resp)
      }
      .onFailure { error ->
        sendBadGateway(ctx, error)
      }
  }
}
