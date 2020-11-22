package ch.biot.backend.ingestion

import io.netty.handler.codec.mqtt.MqttQoS
import io.reactivex.rxkotlin.subscribeBy
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.reactivex.core.RxHelper
import io.vertx.reactivex.core.buffer.Buffer
import io.vertx.reactivex.kafka.admin.KafkaAdminClient
import io.vertx.reactivex.kafka.client.consumer.KafkaConsumer
import io.vertx.reactivex.mqtt.MqttClient
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.io.File
import java.util.concurrent.TimeUnit


@ExtendWith(VertxExtension::class)
@Testcontainers
class TestIngestionVerticle {

  private lateinit var kafkaConsumer: KafkaConsumer<String, JsonObject>
  private lateinit var mqttClient: MqttClient

  @BeforeEach
  fun setup(vertx: io.vertx.reactivex.core.Vertx, testContext: VertxTestContext) {
    val kafkaConfig = mapOf(
      "bootstrap.servers" to "localhost:9092",
      "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
      "value.deserializer" to "io.vertx.kafka.client.serialization.JsonObjectDeserializer",
      "auto.offset.reset" to "earliest",
      "enable.auto.commit" to "false",
      "group.id" to "ingester-test-${System.currentTimeMillis()}"
    )

    kafkaConsumer = KafkaConsumer.create(vertx, kafkaConfig)
    val kafkaAdminClient = KafkaAdminClient.create(vertx, kafkaConfig)

    mqttClient = MqttClient.create(vertx)

    vertx
      .rxDeployVerticle(IngestionVerticle())
      .delay(2000, TimeUnit.MILLISECONDS, RxHelper.scheduler(vertx))
      .flatMapCompletable { kafkaAdminClient.rxDeleteTopics(listOf("incoming.update")) }
      .onErrorComplete()
      .subscribe(testContext::completeNow, testContext::failNow)
  }

  @Test
  @DisplayName("Ingest a well-formed MQTT JSON message")
  fun mqttIngest(testContext: VertxTestContext) {
    val message = json {
      obj(
        "relayID" to "abc",
        "timestamp" to "time",
        "battery" to 10,
        "rssi" to -60.0,
        "mac" to "mac",
        "isPushed" to false
      )
    }

    mqttClient.rxConnect(8883, "localhost")
      .flatMap {
        mqttClient.rxPublish(
          "incoming.update",
          Buffer.newInstance(message.toBuffer()),
          MqttQoS.AT_LEAST_ONCE,
          false,
          false
        )
      }
      .subscribeBy(onError = testContext::failNow)

    kafkaConsumer
      .rxSubscribe("incoming.update").subscribe()

    kafkaConsumer
      .toFlowable()
      .subscribe(
        { record ->
          println(record)
          testContext.verify {
            expectThat(record.key()).isEqualTo("mac")
            val json = record.value()
            expect {
              that(json.getString("relayID")).isEqualTo("abc")
              that(json.getString("timestamp")).isEqualTo("time")
              that(json.getInteger("battery")).isEqualTo(10)
              that(json.getDouble("rssi")).isEqualTo(-60.0)
              that(json.getString("mac")).isEqualTo("mac")
              that(json.getBoolean("isPushed")).isEqualTo(false)
            }
            testContext.completeNow()
          }
        },
        testContext::failNow
      )
  }


  companion object {

    private val instance: KDockerComposeContainer by lazy { defineDockerCompose() }

    class KDockerComposeContainer(file: File) : DockerComposeContainer<KDockerComposeContainer>(file)

    private fun defineDockerCompose() = KDockerComposeContainer(File("../docker-compose.yml"))

    @BeforeAll
    @JvmStatic
    fun beforeAll() {
      instance.start()
    }

    @AfterAll
    @JvmStatic
    fun afterAll() {
      instance.stop()
    }

  }
}
