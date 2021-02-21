/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

package ch.biot.backend.publicapi

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.Vertx
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
import io.vertx.kotlin.core.eventbus.eventBusOptionsOf
import io.vertx.kotlin.core.http.httpServerOptionsOf
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.net.pemKeyCertOptionsOf
import io.vertx.kotlin.core.vertxOptionsOf
import io.vertx.kotlin.ext.auth.jwt.jwtAuthOptionsOf
import io.vertx.kotlin.ext.auth.jwtOptionsOf
import io.vertx.kotlin.ext.auth.pubSecKeyOptionsOf
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import org.slf4j.LoggerFactory
import java.net.InetAddress


class PublicApiVerticle : AbstractVerticle() {

  companion object {
    private const val TIMEOUT: Long = 5000

    private const val API_PREFIX = "/api"
    private const val OAUTH_PREFIX = "/oauth"
    private val CRUD_HOST: String = System.getenv().getOrDefault("CRUD_HOST", "localhost")
    private val CRUD_PORT: Int = System.getenv().getOrDefault("CRUD_PORT", "8080").toInt()
    internal val PUBLIC_PORT = System.getenv().getOrDefault("PUBLIC_PORT", "8080").toInt()

    internal val logger = LoggerFactory.getLogger(PublicApiVerticle::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
      val ipv4 = InetAddress.getLocalHost().hostAddress
      val options = vertxOptionsOf(
        clusterManager = HazelcastClusterManager(),
        eventBusOptions = eventBusOptionsOf(host = ipv4, clusterPublicHost = ipv4)
      )

      Vertx.clusteredVertx(options).onSuccess {
        it.deployVerticle(PublicApiVerticle())
      }.onFailure { error ->
        logger.error("Could not start", error)
      }
    }
  }

  private lateinit var webClient: WebClient
  private lateinit var jwtAuth: JWTAuth

  override fun start(startPromise: Promise<Void>?) {
    val fs = vertx.fileSystem()

    // Read public and private keys from the file system. They are used for JWT authentication
    // Blocking is not an issue since this action is done only once at startup
    val publicKeyJWT = fs.readFileBlocking("public_key_jwt.pem")
    val privateKeyJWT = fs.readFileBlocking("private_key_jwt.pem")

    jwtAuth = JWTAuth.create(
      vertx, jwtAuthOptionsOf(
        pubSecKeys = listOf(
          pubSecKeyOptionsOf(
            algorithm = "RS256",
            buffer = publicKeyJWT
          ), pubSecKeyOptionsOf(
            algorithm = "RS256",
            buffer = privateKeyJWT
          )
        )
      )
    )

    val jwtAuthHandler = JWTAuthHandler.create(jwtAuth)

    val router = Router.router(vertx)

    // CORS allowed headers and methods
    val allowedHeaders =
      setOf("x-requested-with", "Access-Control-Allow-Origin", "origin", "Content-Type", "accept", "Authorization")
    val allowedMethods = setOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)

    router.route().handler(CorsHandler.create("*").allowedHeaders(allowedHeaders).allowedMethods(allowedMethods))

    with(BodyHandler.create()) {
      router.post().handler(this)
      router.put().handler(this)
    }

    // Users
    router.post("$OAUTH_PREFIX/register")
      .handler(
        CorsHandler.create("((http://)|(https://))localhost\\:\\d+").allowedHeaders(allowedHeaders)
          .allowedMethods(setOf(HttpMethod.POST))
      )
      .handler(::registerUserHandler)
    router.post("$OAUTH_PREFIX/token").handler(::tokenHandler)
    router.put("$API_PREFIX/users/:id").handler(jwtAuthHandler).handler(::updateUserHandler)
    router.get("$API_PREFIX/users").handler(jwtAuthHandler).handler(::getUsersHandler)
    router.get("$API_PREFIX/users/:id").handler(jwtAuthHandler).handler(::getUserHandler)
    router.delete("$API_PREFIX/users/:id").handler(jwtAuthHandler).handler(::deleteUserHandler)

    // Relays
    router.post("$API_PREFIX/relays").handler(jwtAuthHandler).handler(::registerRelayHandler)
    router.put("$API_PREFIX/relays/:id").handler(jwtAuthHandler).handler(::updateRelayHandler)
    router.get("$API_PREFIX/relays").handler(jwtAuthHandler).handler(::getRelaysHandler)
    router.get("$API_PREFIX/relays/:id").handler(jwtAuthHandler).handler(::getRelayHandler)
    router.delete("$API_PREFIX/relays/:id").handler(jwtAuthHandler).handler(::deleteRelayHandler)

    // Items
    router.post("$API_PREFIX/items").handler(jwtAuthHandler).handler(::registerItemHandler)
    router.put("$API_PREFIX/items/:id").handler(jwtAuthHandler).handler(::updateItemHandler)
    router.get("$API_PREFIX/items").handler(jwtAuthHandler).handler(::getItemsHandler)
    router.get("$API_PREFIX/items/:id").handler(jwtAuthHandler).handler(::getItemHandler)
    router.delete("$API_PREFIX/items/:id").handler(jwtAuthHandler).handler(::deleteItemHandler)

    // TODO Analytics

    // Health checks
    router.get("/health/ready").handler(::readinessCheck)
    router.get("/health/live").handler(::livenessCheck)

    webClient = WebClient.create(vertx)

    vertx.createHttpServer(
      httpServerOptionsOf(
        ssl = true,
        pemKeyCertOptions = pemKeyCertOptionsOf(certPath = "certificate.pem", keyPath = "certificate_key.pem")
      )
    ).requestHandler(router).listen(PUBLIC_PORT)
      .onSuccess {
        logger.info("HTTP server listening on port $PUBLIC_PORT")
        startPromise?.complete()
      }.onFailure { error ->
        startPromise?.fail(error.cause)
      }
  }

  // Health checks

  private fun readinessCheck(ctx: RoutingContext) {
    webClient
      .get(CRUD_PORT, CRUD_HOST, "/health/ready")
      .expect(ResponsePredicate.SC_OK)
      .timeout(TIMEOUT)
      .send()
      .onSuccess {
        logger.info("Readiness check complete")
        ctx.response()
          .putHeader("Content-Type", "application/json")
          .end(jsonObjectOf("status" to "UP").encode())
      }
      .onFailure { error ->
        val cause = error.cause
        logger.error("Readiness check failed", cause)
        ctx.response()
          .setStatusCode(503)
          .putHeader("Content-Type", "application/json")
          .end(jsonObjectOf("status" to "DOWN", "reason" to cause?.message).encode())
      }
  }

  private fun livenessCheck(ctx: RoutingContext) {
    logger.info("Liveness check")
    ctx.response()
      .putHeader("Content-Type", "application/json")
      .end(jsonObjectOf("status" to "UP").encode())
  }

  // Users

  private fun registerUserHandler(ctx: RoutingContext) = registerHandler(ctx, "users")
  private fun updateUserHandler(ctx: RoutingContext) = updateHandler(ctx, "users")
  private fun getUsersHandler(ctx: RoutingContext) = getManyHandler(ctx, "users")
  private fun getUserHandler(ctx: RoutingContext) = getOneHandler(ctx, "users")
  private fun deleteUserHandler(ctx: RoutingContext) = deleteHandler(ctx, "users")

  /**
   * Handles the token request.
   */
  private fun tokenHandler(ctx: RoutingContext) {
    fun makeJwtToken(username: String, company: String): String {
      // Add the company information to the custom claims of the token
      val claims = jsonObjectOf("company" to company)
      // The token expires in 7 days (10080 minutes)
      val jwtOptions = jwtOptionsOf(algorithm = "RS256", expiresInMinutes = 10080, issuer = "BIoT", subject = username)
      return jwtAuth.generateToken(claims, jwtOptions)
    }

    logger.info("New token request")

    val payload = ctx.bodyAsJson
    val username: String = payload["username"]

    webClient
      .post(CRUD_PORT, CRUD_HOST, "/users/authenticate")
      .timeout(TIMEOUT)
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
  private fun deleteRelayHandler(ctx: RoutingContext) = deleteHandler(ctx, "relays")

  // Items

  private fun registerItemHandler(ctx: RoutingContext) = registerHandler(ctx, "items", forwardResponse = true)
  private fun updateItemHandler(ctx: RoutingContext) = updateHandler(ctx, "items")
  private fun getItemsHandler(ctx: RoutingContext) = getManyHandler(ctx, "items")
  private fun getItemHandler(ctx: RoutingContext) = getOneHandler(ctx, "items")
  private fun deleteItemHandler(ctx: RoutingContext) = deleteHandler(ctx, "items")

  // Helpers

  /**
   * Handles a register request for the given endpoint. The forwardResponse parameter is set to true when the response
   * from the underlying microservice needs to be forwarded to the user. If it is set to false, only the status code is
   * sent.
   */
  private fun registerHandler(ctx: RoutingContext, endpoint: String, forwardResponse: Boolean = false) {
    logger.info("New register request on /$endpoint endpoint")

    webClient
      .post(CRUD_PORT, CRUD_HOST, "/$endpoint")
      .timeout(TIMEOUT)
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
      .put(CRUD_PORT, CRUD_HOST, "/$endpoint/${ctx.pathParam("id")}")
      .timeout(TIMEOUT)
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
      .get(CRUD_PORT, CRUD_HOST, "/$endpoint")
      .timeout(TIMEOUT)
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
      .get(CRUD_PORT, CRUD_HOST, "/$endpoint/${ctx.pathParam("id")}")
      .timeout(TIMEOUT)
      .`as`(BodyCodec.jsonObject())
      .send()
      .onSuccess { resp ->
        forwardJsonObjectOrStatusCode(ctx, resp)
      }
      .onFailure { error ->
        sendBadGateway(ctx, error)
      }
  }

  /**
   * Handles a delete request for the given endpoint.
   */
  private fun deleteHandler(ctx: RoutingContext, endpoint: String) {
    logger.info("New delete request on /$endpoint endpoint")

    webClient
      .delete(CRUD_PORT, CRUD_HOST, "/$endpoint/${ctx.pathParam("id")}")
      .timeout(TIMEOUT)
      .expect(ResponsePredicate.SC_OK)
      .send()
      .onSuccess {
        ctx.end()
      }
      .onFailure { error ->
        sendBadGateway(ctx, error)
      }
  }
}
