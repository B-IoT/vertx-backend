/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.publicapi

import arrow.fx.coroutines.parZip
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.web.Route
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
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.auth.jwt.jwtAuthOptionsOf
import io.vertx.kotlin.ext.auth.jwtOptionsOf
import io.vertx.kotlin.ext.auth.pubSecKeyOptionsOf
import io.vertx.kotlin.ext.web.client.webClientOptionsOf
import io.vertx.kotlin.micrometer.micrometerMetricsOptionsOf
import io.vertx.kotlin.micrometer.vertxPrometheusOptionsOf
import io.vertx.micrometer.PrometheusScrapingHandler
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.InetAddress

class PublicApiVerticle : CoroutineVerticle() {

  companion object {
    private const val TIMEOUT: Long = 5000

    private const val APPLICATION_JSON = "application/json"
    private const val CONTENT_TYPE = "Content-Type"

    private const val USERS_ENDPOINT = "users"
    private const val RELAYS_ENDPOINT = "relays"
    private const val ITEMS_ENDPOINT = "items"
    private const val ANALYTICS_ENDPOINT = "analytics"

    private const val UNAUTHORIZED_CODE = 401

    private const val SERVER_COMPRESSION_LEVEL = 4

    private const val API_PREFIX = "/api"
    private const val OAUTH_PREFIX = "/oauth"

    private val environment = System.getenv()
    private val CRUD_HOST: String = environment.getOrDefault("CRUD_HOST", "localhost")
    private val CRUD_PORT: Int = environment.getOrDefault("CRUD_PORT", "8080").toInt()
    internal val PUBLIC_PORT = environment.getOrDefault("PUBLIC_PORT", "8080").toInt()

    internal val LOGGER = LoggerFactory.getLogger(PublicApiVerticle::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
      val ipv4 = InetAddress.getLocalHost().hostAddress
      val options = vertxOptionsOf(
        clusterManager = HazelcastClusterManager(),
        eventBusOptions = eventBusOptionsOf(host = ipv4, clusterPublicHost = ipv4),
        metricsOptions = micrometerMetricsOptionsOf(
          enabled = true,
          prometheusOptions = vertxPrometheusOptionsOf(enabled = true, publishQuantiles = true)
        )
      )

      Vertx.clusteredVertx(options).onSuccess {
        it.deployVerticle(PublicApiVerticle())
      }.onFailure { error ->
        LOGGER.error("Could not start", error)
      }
    }
  }

  private lateinit var webClient: WebClient
  private lateinit var jwtAuth: JWTAuth

  override suspend fun start() {
    val fs = vertx.fileSystem()

    // Read public and private keys from the file system. They are used for JWT authentication.
    val (publicKeyJWT, privateKeyJWT) = parZip(
      vertx.dispatcher(),
      { fs.coroutineReadFile("public_key_jwt.pem") },
      { fs.coroutineReadFile("private_key_jwt.pem") }) { pub, priv ->
      pub to priv
    }

    jwtAuth = JWTAuth.create(
      vertx,
      jwtAuthOptionsOf(
        pubSecKeys = listOf(
          pubSecKeyOptionsOf(
            algorithm = "RS256",
            buffer = publicKeyJWT
          ),
          pubSecKeyOptionsOf(
            algorithm = "RS256",
            buffer = privateKeyJWT
          )
        )
      )
    )

    val jwtAuthHandler = JWTAuthHandler.create(jwtAuth)

    val router = Router.router(vertx)

    // Metrics
    router.route("/metrics").handler(PrometheusScrapingHandler.create())

    // CORS allowed headers and methods
    val allowedHeaders =
      setOf("x-requested-with", "Access-Control-Allow-Origin", "origin", CONTENT_TYPE, "accept", "Authorization")
    val allowedMethods = setOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)

    router.route().handler(CorsHandler.create("*").allowedHeaders(allowedHeaders).allowedMethods(allowedMethods))

    with(BodyHandler.create()) {
      router.post().handler(this)
      router.put().handler(this)
    }

    // Users
    router.post("$OAUTH_PREFIX/register")
      .handler(
        // Only allow creating a user from inside the network
        CorsHandler.create("((http://)|(https://))localhost\\:\\d+").allowedHeaders(allowedHeaders)
          .allowedMethods(setOf(HttpMethod.POST))
      )
      .coroutineHandler(::registerUserHandler)
    router.post("$OAUTH_PREFIX/token").coroutineHandler(::tokenHandler)
    router.put("$API_PREFIX/$USERS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::updateUserHandler)
    router.get("$API_PREFIX/$USERS_ENDPOINT").handler(jwtAuthHandler).coroutineHandler(::getUsersHandler)
    router.get("$API_PREFIX/$USERS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getUserHandler)
    router.delete("$API_PREFIX/$USERS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::deleteUserHandler)

    // Relays
    router.post("$API_PREFIX/$RELAYS_ENDPOINT").handler(jwtAuthHandler).coroutineHandler(::registerRelayHandler)
    router.put("$API_PREFIX/$RELAYS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::updateRelayHandler)
    router.get("$API_PREFIX/$RELAYS_ENDPOINT").handler(jwtAuthHandler).coroutineHandler(::getRelaysHandler)
    router.get("$API_PREFIX/$RELAYS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getRelayHandler)
    router.delete("$API_PREFIX/$RELAYS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::deleteRelayHandler)

    // Items
    router.post("$API_PREFIX/$ITEMS_ENDPOINT").handler(jwtAuthHandler).coroutineHandler(::registerItemHandler)
    router.put("$API_PREFIX/$ITEMS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::updateItemHandler)
    router.get("$API_PREFIX/$ITEMS_ENDPOINT").handler(jwtAuthHandler).coroutineHandler(::getItemsHandler)
    router.get("$API_PREFIX/$ITEMS_ENDPOINT/categories").handler(jwtAuthHandler)
      .coroutineHandler(::getCategoriesHandler)
    router.get("$API_PREFIX/$ITEMS_ENDPOINT/closest").handler(jwtAuthHandler).coroutineHandler(::getClosestItemsHandler)
    router.get("$API_PREFIX/$ITEMS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getItemHandler)
    router.delete("$API_PREFIX/$ITEMS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::deleteItemHandler)

    // Analytics
    router.get("$API_PREFIX/$ANALYTICS_ENDPOINT/status").handler(jwtAuthHandler)
      .coroutineHandler(::analyticsGetStatusHandler)

    // Health checks
    router.get("/health/ready").coroutineHandler(::readinessCheckHandler)
    router.get("/health/live").handler(::livenessCheckHandler)

    webClient = WebClient.create(vertx, webClientOptionsOf(tryUseCompression = true))

    try {
      vertx.createHttpServer(
        httpServerOptionsOf(
          ssl = CRUD_HOST != "localhost", // disabled when testing
          pemKeyCertOptions = pemKeyCertOptionsOf(certPath = "certificate.pem", keyPath = "certificate_key.pem"),
          compressionSupported = true,
          compressionLevel = SERVER_COMPRESSION_LEVEL,
          decompressionSupported = true
        )
      )
        .requestHandler(router)
        .listen(PUBLIC_PORT)
        .await()

      LOGGER.info("HTTP server listening on port $PUBLIC_PORT")
    } catch (error: Throwable) {
      LOGGER.error("Could not start HTTP server", error)
    }
  }

  // Analytics
  private suspend fun analyticsGetStatusHandler(ctx: RoutingContext) {
    LOGGER.info("New getStatus request on $ANALYTICS_ENDPOINT endpoint")

    webClient.get(CRUD_PORT, CRUD_HOST, "/$ANALYTICS_ENDPOINT/status")
      .addQueryParam("company", ctx.user().principal()["company"])
      .timeout(TIMEOUT)
      .`as`(BodyCodec.jsonObject())
      .coroutineSend()
      .bimap(
        { error ->
          sendBadGateway(ctx, error)
        },
        { resp ->
          forwardJsonObjectOrStatusCode(ctx, resp)
        }
      )
  }

  // Health checks

  private suspend fun readinessCheckHandler(ctx: RoutingContext) {
    webClient
      .get(CRUD_PORT, CRUD_HOST, "/health/ready")
      .expect(ResponsePredicate.SC_OK)
      .timeout(TIMEOUT)
      .coroutineSend()
      .bimap(
        { error ->
          val cause = error.cause
          LOGGER.error("Readiness check failed", cause)
          ctx.response()
            .setStatusCode(503)
            .putHeader(CONTENT_TYPE, APPLICATION_JSON)
            .end(jsonObjectOf("status" to "DOWN", "reason" to cause?.message).encode())
        },
        {
          LOGGER.info("Readiness check complete")
          ctx.response()
            .putHeader(CONTENT_TYPE, APPLICATION_JSON)
            .end(jsonObjectOf("status" to "UP").encode())
        }
      )
  }

  private fun livenessCheckHandler(ctx: RoutingContext) {
    LOGGER.info("Liveness check")
    ctx.response()
      .putHeader(CONTENT_TYPE, APPLICATION_JSON)
      .end(jsonObjectOf("status" to "UP").encode())
  }

  // Users

  private suspend fun registerUserHandler(ctx: RoutingContext) = registerHandler(ctx, USERS_ENDPOINT)
  private suspend fun updateUserHandler(ctx: RoutingContext) = updateHandler(ctx, USERS_ENDPOINT)
  private suspend fun getUsersHandler(ctx: RoutingContext) = getManyHandler(ctx, USERS_ENDPOINT)
  private suspend fun getUserHandler(ctx: RoutingContext) = getOneHandler(ctx, USERS_ENDPOINT)
  private suspend fun deleteUserHandler(ctx: RoutingContext) = deleteHandler(ctx, USERS_ENDPOINT)

  /**
   * Handles the token request.
   */
  private suspend fun tokenHandler(ctx: RoutingContext) {
    fun makeJwtToken(username: String, company: String): String {
      // Add the company information to the custom claims of the token
      val claims = jsonObjectOf("company" to company)
      // The token expires in 7 days (10080 minutes)
      val jwtOptions = jwtOptionsOf(algorithm = "RS256", expiresInMinutes = 10080, issuer = "BIoT", subject = username)
      return jwtAuth.generateToken(claims, jwtOptions)
    }

    LOGGER.info("New token request")

    val payload = ctx.bodyAsJson
    val username: String = payload["username"]

    webClient
      .post(CRUD_PORT, CRUD_HOST, "/users/authenticate")
      .timeout(TIMEOUT)
      .expect(ResponsePredicate.SC_SUCCESS)
      .coroutineSendBuffer(ctx.body)
      .bimap(
        { error ->
          LOGGER.error("Authentication error", error)
          ctx.fail(UNAUTHORIZED_CODE)
        },
        { response ->
          val company = response.bodyAsString()
          val token = makeJwtToken(username, company)
          ctx.response().putHeader(CONTENT_TYPE, "application/jwt").end(token)
        }
      )
  }

  // Relays

  private suspend fun registerRelayHandler(ctx: RoutingContext) = registerHandler(ctx, RELAYS_ENDPOINT)
  private suspend fun updateRelayHandler(ctx: RoutingContext) = updateHandler(ctx, RELAYS_ENDPOINT)
  private suspend fun getRelaysHandler(ctx: RoutingContext) = getManyHandler(ctx, RELAYS_ENDPOINT)
  private suspend fun getRelayHandler(ctx: RoutingContext) = getOneHandler(ctx, RELAYS_ENDPOINT)
  private suspend fun deleteRelayHandler(ctx: RoutingContext) = deleteHandler(ctx, RELAYS_ENDPOINT)

  // Items

  private suspend fun registerItemHandler(ctx: RoutingContext) =
    registerHandler(ctx, ITEMS_ENDPOINT, forwardResponse = true)

  private suspend fun updateItemHandler(ctx: RoutingContext) = updateHandler(ctx, ITEMS_ENDPOINT)
  private suspend fun getItemsHandler(ctx: RoutingContext) = getManyHandler(ctx, ITEMS_ENDPOINT)
  private suspend fun getClosestItemsHandler(ctx: RoutingContext) {
    LOGGER.info("New getClosestItems request")

    webClient.get(CRUD_PORT, CRUD_HOST, "/$ITEMS_ENDPOINT/closest/?${ctx.request().query()}")
      .addQueryParam("company", ctx.user().principal()["company"])
      .timeout(TIMEOUT)
      .`as`(BodyCodec.jsonObject())
      .coroutineSend()
      .bimap(
        { error ->
          sendBadGateway(ctx, error)
        },
        { resp ->
          forwardJsonObjectOrStatusCode(ctx, resp)
        }
      )
  }

  private suspend fun getItemHandler(ctx: RoutingContext) = getOneHandler(ctx, ITEMS_ENDPOINT)
  private suspend fun deleteItemHandler(ctx: RoutingContext) = deleteHandler(ctx, ITEMS_ENDPOINT)
  private suspend fun getCategoriesHandler(ctx: RoutingContext) = getManyHandler(ctx, "$ITEMS_ENDPOINT/categories")

  // Helpers

  /**
   * Handles a register request for the given endpoint. The forwardResponse parameter is set to true when the response
   * from the underlying microservice needs to be forwarded to the user. If it is set to false, only the status code is
   * sent.
   */
  private suspend fun registerHandler(ctx: RoutingContext, endpoint: String, forwardResponse: Boolean = false) {
    LOGGER.info("New register request on /$endpoint endpoint")

    webClient
      .post(CRUD_PORT, CRUD_HOST, "/$endpoint").apply {
        if (endpoint != USERS_ENDPOINT) {
          // The users endpoint is not considered because, as the user is being created, there is no authenticated user associated to the context
          addQueryParam("company", ctx.user().principal()["company"])
        }
        timeout(TIMEOUT)
        putHeader(CONTENT_TYPE, APPLICATION_JSON)
        expect(ResponsePredicate.SC_OK)
        coroutineSendBuffer(ctx.body)
          .bimap(
            { error ->
              sendBadGateway(ctx, error)
            },
            { response ->
              if (forwardResponse) ctx.end(response.body())
              else sendStatusCode(ctx, response.statusCode())
            }
          )
      }
  }

  /**
   * Handles an update request for the given endpoint.
   */
  private suspend fun updateHandler(ctx: RoutingContext, endpoint: String) {
    LOGGER.info("New update request on /$endpoint endpoint")

    webClient
      .put(CRUD_PORT, CRUD_HOST, "/$endpoint/${ctx.pathParam("id")}")
      .addQueryParam("company", ctx.user().principal()["company"])
      .timeout(TIMEOUT)
      .putHeader(CONTENT_TYPE, APPLICATION_JSON)
      .expect(ResponsePredicate.SC_OK)
      .coroutineSendBuffer(ctx.body)
      .bimap(
        { error ->
          sendBadGateway(ctx, error)
        },
        { ctx.end() }
      )
  }

  /**
   * Handles a getMany request for the given endpoint.
   */
  private suspend fun getManyHandler(ctx: RoutingContext, endpoint: String) {
    LOGGER.info("New getMany request on /$endpoint endpoint")

    val query = ctx.request().query()
    val requestURI = if (query != null && query.isNotEmpty()) "/$endpoint/?$query" else "/$endpoint"

    webClient
      .get(CRUD_PORT, CRUD_HOST, requestURI)
      .addQueryParam("company", ctx.user().principal()["company"])
      .timeout(TIMEOUT)
      .`as`(BodyCodec.jsonArray())
      .coroutineSend()
      .bimap(
        { error ->
          sendBadGateway(ctx, error)
        },
        { resp ->
          forwardJsonArrayOrStatusCode(ctx, resp)
        }
      )
  }

  /**
   * Handles a getOne request for the given endpoint.
   */
  private suspend fun getOneHandler(ctx: RoutingContext, endpoint: String) {
    LOGGER.info("New getOne request on /$endpoint endpoint")

    webClient
      .get(CRUD_PORT, CRUD_HOST, "/$endpoint/${ctx.pathParam("id")}")
      .addQueryParam("company", ctx.user().principal()["company"])
      .timeout(TIMEOUT)
      .`as`(BodyCodec.jsonObject())
      .coroutineSend()
      .bimap(
        { error ->
          sendBadGateway(ctx, error)
        },
        { resp ->
          forwardJsonObjectOrStatusCode(ctx, resp)
        }
      )
  }

  /**
   * Handles a delete request for the given endpoint.
   */
  private suspend fun deleteHandler(ctx: RoutingContext, endpoint: String) {
    LOGGER.info("New delete request on /$endpoint endpoint")

    webClient
      .delete(CRUD_PORT, CRUD_HOST, "/$endpoint/${ctx.pathParam("id")}")
      .addQueryParam("company", ctx.user().principal()["company"])
      .timeout(TIMEOUT)
      .expect(ResponsePredicate.SC_OK)
      .coroutineSend()
      .bimap(
        { error ->
          sendBadGateway(ctx, error)
        },
        { ctx.end() }
      )
  }

  /**
   * An extension method for simplifying coroutines usage with Vert.x Web routers.
   */
  private fun Route.coroutineHandler(fn: suspend (RoutingContext) -> Unit): Route =
    handler { ctx ->
      launch(ctx.vertx().dispatcher()) {
        try {
          fn(ctx)
        } catch (e: Exception) {
          ctx.fail(e)
        }
      }
    }
}
