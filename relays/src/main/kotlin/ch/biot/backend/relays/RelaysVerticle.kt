package ch.biot.backend.relays

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.openapi.RouterBuilder
import org.slf4j.LoggerFactory


class RelaysVerticle : AbstractVerticle() {

  companion object {
    private const val PORT = 3000
  }

  private val logger = LoggerFactory.getLogger(RelaysVerticle::class.java)

  override fun start(startPromise: Promise<Void>?) {
    RouterBuilder.create(vertx, "../swagger-api/swagger.yaml").onComplete { ar ->
      if (ar.succeeded()) {
        // Spec loaded with success
        val routerBuilder = ar.result()

        routerBuilder.operation("registerRelay").handler(::registerHandler)
        routerBuilder.operation("getRelays").handler(::getRelaysHandler)
        routerBuilder.operation("getRelay").handler(::getRelayHandler)
        routerBuilder.operation("updateRelay").handler(::updateHandler)

        val router: Router = routerBuilder.createRouter()
        vertx.createHttpServer().requestHandler(router).listen(PORT)
        startPromise?.complete()
      } else {
        // Something went wrong during router builder initialization
        logger.error("Could not initialize router builder", ar.cause())
      }
    }
  }

  private fun registerHandler(ctx: RoutingContext) {
    logger.info("New register request")
    // TODO MongoDB
    ctx.end()
  }

  private fun getRelaysHandler(ctx: RoutingContext) {
    logger.info("New getRelays request")
    // TODO MongoDB
    ctx.end()
  }

  private fun getRelayHandler(ctx: RoutingContext) {
    logger.info("New getRelay request")
    // TODO MongoDBs
    ctx.end()
  }

  private fun updateHandler(ctx: RoutingContext) {
    logger.info("New update request")
    // TODO MongoDBs
    ctx.end()
  }
}
