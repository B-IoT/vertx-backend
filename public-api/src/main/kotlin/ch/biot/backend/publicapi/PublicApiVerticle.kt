package ch.biot.backend.publicapi

import io.reactivex.Completable
import io.vertx.core.http.HttpMethod
import io.vertx.kotlin.ext.auth.jwt.jwtAuthOptionsOf
import io.vertx.kotlin.ext.auth.pubSecKeyOptionsOf
import io.vertx.reactivex.ext.auth.jwt.JWTAuth
import io.vertx.reactivex.ext.web.Router
import io.vertx.reactivex.ext.web.client.WebClient
import io.vertx.reactivex.ext.web.handler.BodyHandler
import io.vertx.reactivex.ext.web.handler.CorsHandler
import io.vertx.reactivex.ext.web.handler.JWTAuthHandler
import org.slf4j.LoggerFactory


class PublicApiVerticle : io.vertx.reactivex.core.AbstractVerticle() {

  companion object {
    private const val API_PREFIX = "/api/v1"
    private const val PORT = 4000
  }

  private val logger = LoggerFactory.getLogger(PublicApiVerticle::class.java)

  private lateinit var webClient: WebClient
  private lateinit var jwtAuth: JWTAuth

  override fun rxStart(): Completable {
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

    // TODO routes

    webClient = WebClient.create(vertx)

    return vertx.createHttpServer().requestHandler(router).rxListen(PORT).ignoreElement()
  }

}
