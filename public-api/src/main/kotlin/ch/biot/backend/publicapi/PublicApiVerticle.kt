/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.publicapi

import arrow.fx.coroutines.parZip
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
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
import io.vertx.ext.web.handler.sockjs.SockJSHandler
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
import io.vertx.kotlin.ext.bridge.permittedOptionsOf
import io.vertx.kotlin.ext.web.client.webClientOptionsOf
import io.vertx.kotlin.ext.web.handler.sockjs.sockJSBridgeOptionsOf
import io.vertx.kotlin.micrometer.micrometerMetricsOptionsOf
import io.vertx.kotlin.micrometer.vertxPrometheusOptionsOf
import io.vertx.micrometer.PrometheusScrapingHandler
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.net.InetAddress


internal val LOGGER = KotlinLogging.logger {}

class PublicApiVerticle : CoroutineVerticle() {

  companion object {
    const val TIMEOUT: Long = 5000

    private const val APPLICATION_JSON = "application/json"
    private const val CONTENT_TYPE = "Content-Type"

    private const val NO_AC_IN_CTX_ERROR_MSG = "Cannot get the accessControlString from the context."

    const val USERS_ENDPOINT = "users"
    private const val RELAYS_ENDPOINT = "relays"
    private const val ITEMS_ENDPOINT = "items"
    private const val ANALYTICS_ENDPOINT = "analytics"

    private const val UNAUTHORIZED_CODE = 401
    internal const val OK_CODE = 200

    private const val SERVER_COMPRESSION_LEVEL = 4

    private const val API_PREFIX = "/api"
    private const val OAUTH_PREFIX = "/oauth"

    private val environment = System.getenv()
    val CRUD_HOST: String = environment.getOrDefault("CRUD_HOST", "localhost")
    val CRUD_PORT: Int = environment.getOrDefault("CRUD_PORT", "8081").toInt()
    internal val PUBLIC_PORT = environment.getOrDefault("PUBLIC_PORT", "8080").toInt()

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
        LOGGER.error(error) { "Could not start" }
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
      setOf(
        "x-requested-with",
        "Access-Control-Allow-Origin",
        "Access-Control-Allow-Credentials",
        "origin",
        CONTENT_TYPE,
        "accept",
        "Authorization"
      )
    val allowedMethods = setOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)

    router.route().handler(
      CorsHandler.create(".*.").allowCredentials(true).allowedHeaders(allowedHeaders).allowedMethods(allowedMethods)
    )

    with(BodyHandler.create()) {
      router.post().handler(this)
      router.put().handler(this)
    }

    // Users
    router.post("$OAUTH_PREFIX/register").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::registerUserHandler)
    router.post("$OAUTH_PREFIX/token").coroutineHandler(::tokenHandler)
    router.put("$API_PREFIX/$USERS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::updateUserHandler)
    router.get("$API_PREFIX/$USERS_ENDPOINT").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::getUsersHandler)
    router.get("$API_PREFIX/$USERS_ENDPOINT/me").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .handler(::getUserInfoHandler)
    router.get("$API_PREFIX/$USERS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::getUserHandler)
    router.delete("$API_PREFIX/$USERS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::deleteUserHandler)

    // Relays
    router.post("$API_PREFIX/$RELAYS_ENDPOINT").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::registerRelayHandler)
    router.put("$API_PREFIX/$RELAYS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::updateRelayHandler)
    router.get("$API_PREFIX/$RELAYS_ENDPOINT").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::getRelaysHandler)
    router.get("$API_PREFIX/$RELAYS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::getRelayHandler)
    router.delete("$API_PREFIX/$RELAYS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::deleteRelayHandler)

    // Items
    router.get("$API_PREFIX/$ITEMS_ENDPOINT/categories").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::getCategoriesHandler)
    router.get("$API_PREFIX/$ITEMS_ENDPOINT/closest").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::getClosestItemsHandler)
    router.get("$API_PREFIX/$ITEMS_ENDPOINT/snapshots").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::getSnapshotsHandler)
    router.get("$API_PREFIX/$ITEMS_ENDPOINT/snapshots/compare").handler(jwtAuthHandler)
      .coroutineHandler(::getACStringHandler)
      .coroutineHandler(::compareSnapshotsHandler)
    router.post("$API_PREFIX/$ITEMS_ENDPOINT/snapshots").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::createSnapshotHandler)
    router.get("$API_PREFIX/$ITEMS_ENDPOINT/snapshots/:id").handler(jwtAuthHandler)
      .coroutineHandler(::getACStringHandler)
      .coroutineHandler(::getSnapshotHandler)
    router.delete("$API_PREFIX/$ITEMS_ENDPOINT/snapshots/:id").handler(jwtAuthHandler)
      .coroutineHandler(::getACStringHandler)
      .coroutineHandler(::deleteSnapshotHandler)
    router.post("$API_PREFIX/$ITEMS_ENDPOINT").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::registerItemHandler)
    router.get("$API_PREFIX/$ITEMS_ENDPOINT").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::getItemsHandler)
    router.get("$API_PREFIX/$ITEMS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::getItemHandler)
    router.delete("$API_PREFIX/$ITEMS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::deleteItemHandler)
    router.put("$API_PREFIX/$ITEMS_ENDPOINT/:id").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::updateItemHandler)

    // Analytics
    router.get("$API_PREFIX/$ANALYTICS_ENDPOINT/status").handler(jwtAuthHandler).coroutineHandler(::getACStringHandler)
      .coroutineHandler(::analyticsGetStatusHandler)

    // Health checks
    router.get("/health/ready").coroutineHandler(::readinessCheckHandler)
    router.get("/health/live").handler(::livenessCheckHandler)

    // Event bus bridge
    val sockJSHandler = SockJSHandler.create(vertx)
    val sockJSBridgeOptions = sockJSBridgeOptionsOf(
      outboundPermitteds = listOf(permittedOptionsOf(addressRegex = "items\\.updates\\..+"))
    )
    router.route("/eventbus/*").coroutineHandler(::eventBusAuthHandler)
    router.mountSubRouter("/eventbus", sockJSHandler.bridge(sockJSBridgeOptions))

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

      LOGGER.info { "HTTP server listening on port $PUBLIC_PORT" }
    } catch (error: Throwable) {
      LOGGER.error(error) { "Could not start HTTP server" }
    }
  }

  // Event bus
  private suspend fun eventBusAuthHandler(ctx: RoutingContext) {
    val token = ctx.request().getParam("token")
    val user = jwtAuth.authenticate(jsonObjectOf("token" to token)).await()
    if (user == null) {
      ctx.fail(UNAUTHORIZED_CODE)
    } else {
      val principal = user.principal()
      val username: String = principal["sub"]
      val company: String = principal["company"]
      LOGGER.info { "New event bus connection by user $username from company $company" }
      ctx.next()
    }
  }

  // Analytics
  private suspend fun analyticsGetStatusHandler(ctx: RoutingContext) {
    LOGGER.info { "New getStatus request on $ANALYTICS_ENDPOINT endpoint" }

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
          LOGGER.error(cause) { "Readiness check failed" }
          ctx.response()
            .setStatusCode(503)
            .putHeader(CONTENT_TYPE, APPLICATION_JSON)
            .end(jsonObjectOf("status" to "DOWN", "reason" to cause?.message).encode())
        },
        {
          LOGGER.debug { "Readiness check complete" }
          ctx.response()
            .putHeader(CONTENT_TYPE, APPLICATION_JSON)
            .end(jsonObjectOf("status" to "UP").encode())
        }
      )
  }

  private fun livenessCheckHandler(ctx: RoutingContext) {
    LOGGER.debug { "Liveness check" }
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
    fun makeJwtToken(username: String, company: String, userID: String): String {
      // Add the company information to the custom claims of the token
      val claims = jsonObjectOf("company" to company, "userID" to userID)
      // The token expires in 7 days (10080 minutes)
      val jwtOptions = jwtOptionsOf(algorithm = "RS256", expiresInMinutes = 10080, issuer = "BioT", subject = username)
      return jwtAuth.generateToken(claims, jwtOptions)
    }

    val payload = ctx.bodyAsJson
    val username: String = payload["username"]

    LOGGER.info { "New token request for user $username" }

    webClient
      .post(CRUD_PORT, CRUD_HOST, "/users/authenticate")
      .timeout(TIMEOUT)
      .expect(ResponsePredicate.SC_SUCCESS)
      .coroutineSendBuffer(ctx.body)
      .bimap(
        { error ->
          LOGGER.error(error) { "Authentication error" }
          ctx.fail(UNAUTHORIZED_CODE)
        },
        { response ->
          val json = response.bodyAsJsonObject()
          val company = json.getString("company")
          val userID = json.getString("userID")
          val token = makeJwtToken(username, company, userID)
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
    LOGGER.info { "New getClosestItems request" }
    val acString = ctx.get<String>("accessControlString")
    if (acString == null) {

      sendBadGateway(ctx, Error(NO_AC_IN_CTX_ERROR_MSG))
    }

    webClient.get(CRUD_PORT, CRUD_HOST, "/$ITEMS_ENDPOINT/closest/?${ctx.request().query()}")
      .addQueryParam("company", ctx.user().principal()["company"])
      .addQueryParam("accessControlString", acString)
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

  private fun getUserInfoHandler(ctx: RoutingContext) {
    LOGGER.info { "New getUserInfo request" }

    val info = jsonObjectOf(
      "company" to ctx.user().principal()["company"]
    )

    ctx.response()
      .putHeader(CONTENT_TYPE, APPLICATION_JSON)
      .end(info.encode())
  }

  private suspend fun getItemHandler(ctx: RoutingContext) = getOneHandler(ctx, ITEMS_ENDPOINT)
  private suspend fun deleteItemHandler(ctx: RoutingContext) = deleteHandler(ctx, ITEMS_ENDPOINT)

  private suspend fun getCategoriesHandler(ctx: RoutingContext) = getManyHandler(ctx, "$ITEMS_ENDPOINT/categories")

  private suspend fun getSnapshotsHandler(ctx: RoutingContext) = getManyHandler(ctx, "$ITEMS_ENDPOINT/snapshots")
  private suspend fun deleteSnapshotHandler(ctx: RoutingContext) = deleteHandler(ctx, "$ITEMS_ENDPOINT/snapshots")

  /**
   * Handles a compareSnapshots request.
   */
  private suspend fun compareSnapshotsHandler(ctx: RoutingContext) {
    LOGGER.info { "New compareSnapshots request" }

    webClient
      .get(CRUD_PORT, CRUD_HOST, "/$ITEMS_ENDPOINT/snapshots/compare/?${ctx.request().query()}")
      .addQueryParam("company", ctx.user().principal()["company"])
      .timeout(TIMEOUT)
      .coroutineSend()
      .bimap(
        { error ->
          sendBadGateway(ctx, error)
        },
        { resp ->
          if (resp.statusCode() != OK_CODE) {
            sendStatusCode(ctx, resp.statusCode())
          } else {
            ctx.response()
              .putHeader(CONTENT_TYPE, APPLICATION_JSON)
              .end(resp.bodyAsJsonObject().encode())
          }
        }
      )
  }

  /**
   * Handles a getSnapshot request. A snapshot contains multiple items.
   */
  private suspend fun getSnapshotHandler(ctx: RoutingContext) {
    LOGGER.info { "New getSnapshot request" }

    webClient
      .get(CRUD_PORT, CRUD_HOST, "/$ITEMS_ENDPOINT/snapshots/${ctx.pathParam("id")}")
      .addQueryParam("company", ctx.user().principal()["company"])
      .timeout(TIMEOUT)
      .coroutineSend()
      .bimap(
        { error ->
          sendBadGateway(ctx, error)
        },
        { resp ->
          if (resp.statusCode() != OK_CODE) {
            sendStatusCode(ctx, resp.statusCode())
          } else {
            ctx.response()
              .putHeader(CONTENT_TYPE, APPLICATION_JSON)
              .end(resp.bodyAsJsonArray().encode())
          }
        }
      )
  }

  /**
   * Handles a createSnapshot request.
   */
  private suspend fun createSnapshotHandler(ctx: RoutingContext) {
    LOGGER.info { "New createSnapshot request" }

    webClient
      .post(CRUD_PORT, CRUD_HOST, "/$ITEMS_ENDPOINT/snapshots")
      .addQueryParam("company", ctx.user().principal()["company"])
      .timeout(TIMEOUT)
      .expect(ResponsePredicate.SC_OK)
      .coroutineSend()
      .bimap(
        { error ->
          sendBadGateway(ctx, error)
        },
        { response ->
          ctx.end(response.body())
        }
      )
  }

  // Helpers

  /**
   * Handles a register request for the given endpoint. The forwardResponse parameter is set to true when the response
   * from the underlying microservice needs to be forwarded to the user. If it is set to false, only the status code is
   * sent.
   * When registering an item (i.e. to the ITEMS_ENDPOINT), if the json does not contain an accessControlString, it
   * puts the accessControlString of the user that requests the registration. Otherwise, the accessControlString of the
   * JSON is taken.
   */
  private suspend fun registerHandler(ctx: RoutingContext, endpoint: String, forwardResponse: Boolean = false) {
    LOGGER.info { "New register request on /$endpoint endpoint" }

    val acString = ctx.get<String>("accessControlString")
    if (acString == null) {
      sendBadGateway(ctx, Error(NO_AC_IN_CTX_ERROR_MSG))
    }

    val json: JsonObject
    try {
      json = ctx.bodyAsJson
    } catch (e: Exception) {
      sendBadGateway(ctx, e)
      return
    }

    // Put the accessControlString in the JSON if none is present
    if (endpoint == ITEMS_ENDPOINT && !json.containsKey("accessControlString")) {
      json.put("accessControlString", acString)
    }

    webClient
      .post(CRUD_PORT, CRUD_HOST, "/$endpoint")
      .addQueryParam("company", ctx.user().principal()["company"])
      .addQueryParam("accessControlString", acString) // Used only by the ITEMS_ENDPOINT for now
      .timeout(TIMEOUT)
      .putHeader(CONTENT_TYPE, APPLICATION_JSON)
      .expect(ResponsePredicate.SC_OK)
      .coroutineSendJsonObject(json)
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

  /**
   * Handles an update request for the given endpoint.
   */
  private suspend fun updateHandler(ctx: RoutingContext, endpoint: String) {
    LOGGER.info { "New update request on /$endpoint endpoint" }
    val acString = ctx.get<String>("accessControlString")
    if (acString == null) {
      sendBadGateway(ctx, Error(NO_AC_IN_CTX_ERROR_MSG))
    }
    val query = ctx.request().query()
    val requestURI =
      if (query != null && query.isNotEmpty()) "/$endpoint/${ctx.pathParam("id")}?$query"
      else "/$endpoint/${ctx.pathParam("id")}"

    webClient
      .put(CRUD_PORT, CRUD_HOST, requestURI)
      .addQueryParam("company", ctx.user().principal()["company"])
      .addQueryParam("accessControlString", acString) // Used only by the ITEMS_ENDPOINT for now
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
    LOGGER.info { "New getMany request on /$endpoint endpoint" }

    val acString = ctx.get<String>("accessControlString")
    if (acString == null) {
      sendBadGateway(ctx, Error(NO_AC_IN_CTX_ERROR_MSG))
    }
    val query = ctx.request().query()
    val requestURI = if (query != null && query.isNotEmpty()) "/$endpoint/?$query" else "/$endpoint"

    webClient
      .get(CRUD_PORT, CRUD_HOST, requestURI)
      .addQueryParam("company", ctx.user().principal()["company"])
      .addQueryParam("accessControlString", acString) // Used only by the ITEMS_ENDPOINT for now
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
    LOGGER.info { "New getOne request on /$endpoint endpoint" }
    var acString = ctx.get<String>("accessControlString")
    if (acString == null) {
      if (endpoint == ITEMS_ENDPOINT) {
        sendBadGateway(ctx, Error(NO_AC_IN_CTX_ERROR_MSG))
      } else {
        // For the endpoints that do not use it -> !!! change when adding AC to all endpoints !!!
        acString = ""
      }
    }
    webClient
      .get(CRUD_PORT, CRUD_HOST, "/$endpoint/${ctx.pathParam("id")}")
      .addQueryParam("company", ctx.user().principal()["company"])
      .addQueryParam("accessControlString", acString) // Used only by the ITEMS_ENDPOINT for now
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
    LOGGER.info { "New delete request on /$endpoint endpoint" }

    var acString = ctx.get<String>("accessControlString")
    if (acString == null) {
      if (endpoint == ITEMS_ENDPOINT) {
        sendBadGateway(ctx, Error(NO_AC_IN_CTX_ERROR_MSG))
      } else {
        // For the endpoints that do not use it -> !!! change when adding AC to all endpoints !!!
        acString = ""
      }
    }
    webClient
      .delete(CRUD_PORT, CRUD_HOST, "/$endpoint/${ctx.pathParam("id")}")
      .addQueryParam("company", ctx.user().principal()["company"])
      .addQueryParam("accessControlString", acString) // Used only by the ITEMS_ENDPOINT for now
      .timeout(TIMEOUT)
      .coroutineSend()
      .bimap(
        { error ->
          sendBadGateway(ctx, error)
        },
        { resp ->
          if (resp.statusCode() != OK_CODE) sendStatusCode(ctx, resp.statusCode()) else ctx.end()
        }
      )

  }

  private suspend fun getACStringHandler(ctx: RoutingContext) {
    val userPrincipal = ctx.user().principal()
    val userID: String = userPrincipal.getString("userID")
    webClient
      .get(CRUD_PORT, CRUD_HOST, "/$USERS_ENDPOINT/${userID}")
      .addQueryParam("company", userPrincipal.getString("company"))
      .timeout(TIMEOUT)
      .`as`(BodyCodec.jsonObject())
      .coroutineSend()
      .bimap(
        { error ->
          sendBadGateway(ctx, error)
        },
        { resp ->
          val acString: String
          try {
            val json = resp.body()
            acString = json.getString("accessControlString")
          } catch (e: Exception) {
            sendBadGateway(ctx, e)
            return
          }
          ctx.put("accessControlString", acString)
          ctx.next()
        }
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
