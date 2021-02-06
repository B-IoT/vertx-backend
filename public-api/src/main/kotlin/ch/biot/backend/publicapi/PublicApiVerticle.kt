/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

package ch.biot.backend.publicapi

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.vertx.circuitbreaker.CircuitBreaker
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.predicate.ResponsePredicate
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.JWTAuthHandler
import io.vertx.kotlin.circuitbreaker.circuitBreakerOptionsOf
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.ext.auth.jwt.jwtAuthOptionsOf
import io.vertx.kotlin.ext.auth.jwtOptionsOf
import io.vertx.kotlin.ext.auth.pubSecKeyOptionsOf
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.math.max
import kotlin.math.pow


class PublicApiVerticle : AbstractVerticle() {

  companion object {
    private const val TIMEOUT: Long = 5000

    private const val API_PREFIX = "/api"
    private const val OAUTH_PREFIX = "/oauth"
    private const val CRUD_PORT = 3000
    internal const val PUBLIC_PORT = 4000

    internal val logger = LoggerFactory.getLogger(PublicApiVerticle::class.java)
  }

  // Caches for fast answers
  private lateinit var usersCache: Cache<String, JsonObject>
  private lateinit var relaysCache: Cache<String, JsonObject>
  private lateinit var itemsCache: Cache<Int, JsonObject>

  // Cache used to answer requests when the circuit breaker is closed
  private lateinit var itemsRecoveryCache: Cache<Int, JsonObject>

  // Circuit breaker for the /items endpoint
  private lateinit var itemsCircuitBreaker: CircuitBreaker

  private lateinit var webClient: WebClient
  private lateinit var jwtAuth: JWTAuth

  override fun start(startPromise: Promise<Void>?) {
    val fs = vertx.fileSystem()

    // Read public and private keys from the file system. They are used for JWT authentication
    // Blocking is not an issue since this action is done only once at startup
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

    val context = vertx.orCreateContext
    val vertxExecutor: (Runnable) -> Unit = { cmd -> context.runOnContext { cmd.run() } }

    // Users
    router.post("$OAUTH_PREFIX/register").handler(::registerUserHandler)
    router.post("$OAUTH_PREFIX/token").handler(::tokenHandler)
    router.put("$API_PREFIX/users/:id").handler(jwtAuthHandler).handler(::updateUserHandler)
    router.get("$API_PREFIX/users").handler(jwtAuthHandler).handler(::getUsersHandler)
    router.get("$API_PREFIX/users/:id").handler(jwtAuthHandler).handler(::getUserHandler)
    router.delete("$API_PREFIX/users/:id").handler(jwtAuthHandler).handler(::deleteUserHandler)

    usersCache = Caffeine.newBuilder().executor(vertxExecutor).expireAfterWrite(Duration.ofHours(16)).build()

    // Relays
    router.post("$API_PREFIX/relays").handler(jwtAuthHandler).handler(::registerRelayHandler)
    router.put("$API_PREFIX/relays/:id").handler(jwtAuthHandler).handler(::updateRelayHandler)
    router.get("$API_PREFIX/relays").handler(jwtAuthHandler).handler(::getRelaysHandler)
    router.get("$API_PREFIX/relays/:id").handler(jwtAuthHandler).handler(::getRelayHandler)
    router.delete("$API_PREFIX/relays/:id").handler(jwtAuthHandler).handler(::deleteRelayHandler)

    relaysCache = Caffeine.newBuilder().executor(vertxExecutor).expireAfterWrite(Duration.ofHours(16)).build()

    // Items
    router.post("$API_PREFIX/items").handler(jwtAuthHandler).handler(::registerItemHandler)
    router.put("$API_PREFIX/items/:id").handler(jwtAuthHandler).handler(::updateItemHandler)
    router.get("$API_PREFIX/items").handler(jwtAuthHandler).handler(::getItemsHandler)
    router.get("$API_PREFIX/items/:id").handler(jwtAuthHandler).handler(::getItemHandler)
    router.delete("$API_PREFIX/items/:id").handler(jwtAuthHandler).handler(::deleteItemHandler)

    itemsCache = Caffeine.newBuilder().executor(vertxExecutor).expireAfterWrite(Duration.ofSeconds(15)).build()
    itemsRecoveryCache = Caffeine.newBuilder().executor(vertxExecutor).expireAfterWrite(Duration.ofDays(1)).build()

    val maxBackoff = 32000.0 // in milliseconds
    val itemsCircuitBreakerName = "items-circuit-breaker"
    itemsCircuitBreaker = CircuitBreaker.create(
      itemsCircuitBreakerName, vertx, circuitBreakerOptionsOf(
        maxFailures = 5,
        maxRetries = 2,
        timeout = 5000,
        resetTimeout = 10000
      )
    ).retryPolicy { retryCount ->
      // Exponential backoff with jitter
      max(2.0.pow(retryCount) * 100L + (0..1000).random(), maxBackoff).toLong()
    }.openHandler {
      logBreakerUpdate("open", itemsCircuitBreakerName)
    }.halfOpenHandler {
      logBreakerUpdate("half open", itemsCircuitBreakerName)
    }.closeHandler {
      logBreakerUpdate("closed", itemsCircuitBreakerName)
    }

    // TODO Analytics

    webClient = WebClient.create(vertx)

    vertx.createHttpServer().requestHandler(router).listen(PUBLIC_PORT).onComplete {
      startPromise?.complete()
    }
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
      .post(CRUD_PORT, "localhost", "/users/authenticate")
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

  private fun registerItemHandler(ctx: RoutingContext) = registerHandler(ctx, "items")
  private fun updateItemHandler(ctx: RoutingContext) = updateHandler(ctx, "items")
  private fun getItemsHandler(ctx: RoutingContext) = getManyHandler(ctx, "items")
  private fun getItemHandler(ctx: RoutingContext) = getOneHandler(ctx, "items")
  private fun deleteItemHandler(ctx: RoutingContext) = deleteHandler(ctx, "items")

  // Helpers

  /**
   * Handles a register request for the given endpoint.
   */
  private fun registerHandler(ctx: RoutingContext, endpoint: String) {
    logger.info("New register request on /$endpoint endpoint")

    webClient
      .post(CRUD_PORT, "localhost", "/$endpoint")
      .timeout(TIMEOUT)
      .putHeader("Content-Type", "application/json")
      .expect(ResponsePredicate.SC_OK)
      .sendBuffer(ctx.body)
      .onSuccess { response ->
        val element = response.body()
        cacheBuffer(element, endpoint)
        ctx.end(element)
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
      .timeout(TIMEOUT)
      .putHeader("Content-Type", "application/json")
      .expect(ResponsePredicate.SC_OK)
      .sendBuffer(ctx.body)
      .onSuccess { response ->
        val element = response.body()
        cacheBuffer(element, endpoint)
        ctx.end(element)
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

    if (endpoint == "items") {
      itemsCircuitBreaker.executeWithFallback({ promise ->
        webClient
          .get(CRUD_PORT, "localhost", "/$endpoint")
          .putHeader("Accept", "application/json")
          .timeout(TIMEOUT)
          .`as`(BodyCodec.jsonArray())
          .send()
          .onSuccess { resp ->
            // Cache all items
            resp.body().forEach {
              val item = it as JsonObject
              val id = item.getInteger("id")
              itemsRecoveryCache.put(id, item)
              itemsCache.put(id, item)
            }
            forwardJsonArrayOrStatusCode(ctx, resp)
            promise.complete()
          }
          .onFailure { error ->
            tryToRecoverItemsFromCache(ctx)
            promise.fail(error)
          }
      }) {
        tryToRecoverItemsFromCache(ctx)
      }
    } else {
      webClient
        .get(CRUD_PORT, "localhost", "/$endpoint")
        .putHeader("Accept", "application/json")
        .timeout(TIMEOUT)
        .`as`(BodyCodec.jsonArray())
        .send()
        .onSuccess { resp ->
          cacheJsonArrayResponse(resp, endpoint)
          forwardJsonArrayOrStatusCode(ctx, resp)
        }
        .onFailure { error ->
          sendBadGateway(ctx, error)
        }
    }
  }

  /**
   * Handles a getOne request for the given endpoint.
   */
  private fun getOneHandler(ctx: RoutingContext, endpoint: String) {
    logger.info("New getOne request on /$endpoint endpoint")

    val idString = ctx.pathParam("id")

    if (endpoint == "items") {
      val id: Int = idString.toInt()
      itemsCircuitBreaker.executeWithFallback({ promise ->
        webClient
          .get(CRUD_PORT, "localhost", "/$endpoint/$id")
          .putHeader("Accept", "application/json")
          .timeout(TIMEOUT)
          .`as`(BodyCodec.jsonObject())
          .send()
          .onSuccess { resp ->
            // Cache the item
            val item = resp.body()
            itemsRecoveryCache.put(id, item)
            itemsCache.put(id, item)
            forwardJsonObjectOrStatusCode(ctx, resp)
            promise.complete()
          }
          .onFailure { error ->
            tryToRecoverItemFromCache(ctx, id)
            promise.fail(error)
          }
      }) {
        tryToRecoverItemFromCache(ctx, id)
      }
    } else {
      webClient
        .get(CRUD_PORT, "localhost", "/$endpoint/$idString")
        .putHeader("Accept", "application/json")
        .timeout(TIMEOUT)
        .`as`(BodyCodec.jsonObject())
        .send()
        .onSuccess { resp ->
          cacheJsonObjectResponse(resp, endpoint)
          forwardJsonObjectOrStatusCode(ctx, resp)
        }
        .onFailure { error ->
          sendBadGateway(ctx, error)
        }
    }
  }

  /**
   * Handles a delete request for the given endpoint.
   */
  private fun deleteHandler(ctx: RoutingContext, endpoint: String) {
    logger.info("New delete request on /$endpoint endpoint")

    val id = ctx.pathParam("id")

    webClient
      .delete(CRUD_PORT, "localhost", "/$endpoint/$id")
      .timeout(TIMEOUT)
      .expect(ResponsePredicate.SC_OK)
      .send()
      .onSuccess {
        deleteElementFromCache(id, endpoint)
        ctx.end()
      }
      .onFailure { error ->
        sendBadGateway(ctx, error)
      }
  }

  /**
   * Tries to recover by getting the item with the given id from the cache, otherwise it fails.
   */
  private fun tryToRecoverItemFromCache(ctx: RoutingContext, itemID: Int) {
    val result = itemsRecoveryCache.getIfPresent(itemID)
    if (result == null) {
      logger.error("Service not working and no cached data for the item $itemID")
      ctx.fail(502)
    } else {
      ctx.response()
        .putHeader("Content-Type", "application/json")
        .end(result.encode())
    }
  }

  /**
   * Tries to recover by getting all items from the cache, otherwise it fails.
   */
  private fun tryToRecoverItemsFromCache(ctx: RoutingContext) {
    val result = JsonArray(itemsRecoveryCache.asMap().values.toList())
    if (result.isEmpty) {
      logger.error("Service not working and no cached data for the items")
      ctx.fail(502)
    } else {
      ctx.response()
        .putHeader("Content-Type", "application/json")
        .end(result.encode())
    }
  }

  /**
   * Caches the given buffer from a request to the given endpoint.
   */
  private fun cacheBuffer(buffer: Buffer, endpoint: String) {
    val json = buffer.toJsonObject()
    when (endpoint) {
      "users" ->
        usersCache.put(json.getString("userID"), json)
      "relays" ->
        relaysCache.put(json.getString("relayID"), json)
      "items" ->
        itemsCache.put(json.getInteger("id"), json)
      else -> throw AssertionError("Impossible")
    }
  }

  /**
   * Caches the given json object from a request to the given endpoint.
   */
  private fun cacheJsonObjectResponse(response: HttpResponse<JsonObject>, endpoint: String) =
    cacheBuffer(response.body().toBuffer(), endpoint)

  /**
   * Caches the given json array from a request to the given endpoint.
   */
  private fun cacheJsonArrayResponse(response: HttpResponse<JsonArray>, endpoint: String) {
    response.body().forEach {
      val item = it as JsonObject
      cacheBuffer(item.toBuffer(), endpoint)
    }
  }

  /**
   * Deletes the element corresponding to the given id from the given endpoint's cache.
   */
  private fun deleteElementFromCache(id: String, endpoint: String) {
    when (endpoint) {
      "users" ->
        usersCache.invalidate(id)
      "relays" ->
        relaysCache.invalidate(id)
      "items" ->
        itemsCache.invalidate(id.toInt())
      else -> throw AssertionError("Impossible")
    }
  }
}
