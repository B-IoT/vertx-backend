/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.relayscommunication

import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.INGESTION_TOPIC
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.KAFKA_PORT
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.LIVENESS_PORT
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.MONGO_PORT
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.MQTT_PORT
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.READINESS_PORT
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.RELAYS_COLLECTION
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.RELAYS_CONFIGURATION_TOPIC
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.RELAYS_MANAGEMENT_TOPIC
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.RELAYS_UPDATE_ADDRESS
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.RELAY_REPO_URL
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.UPDATE_CONFIG_INTERVAL_SECONDS
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.UPDATE_PARAMETERS_TOPIC
import io.netty.handler.codec.mqtt.MqttQoS
import io.restassured.builder.RequestSpecBuilder
import io.restassured.filter.log.RequestLoggingFilter
import io.restassured.filter.log.ResponseLoggingFilter
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.specification.RequestSpecification
import io.vertx.core.CompositeFuture
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.mongo.MongoAuthentication
import io.vertx.ext.auth.mongo.MongoUserUtil
import io.vertx.ext.mongo.MongoClient
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kafka.client.consumer.KafkaConsumer
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.net.netClientOptionsOf
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.coroutines.toReceiveChannel
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import io.vertx.kotlin.ext.mongo.indexOptionsOf
import io.vertx.kotlin.mqtt.mqttClientOptionsOf
import io.vertx.kotlin.pgclient.pgConnectOptionsOf
import io.vertx.kotlin.sqlclient.poolOptionsOf
import io.vertx.mqtt.MqttClient
import io.vertx.pgclient.PgPool
import io.vertx.pgclient.SslMode
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.*
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate
import java.util.*
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
@Testcontainers
class TestRelaysCommunicationVerticle {

  private lateinit var kafkaConsumer: KafkaConsumer<String, JsonObject>

  private lateinit var mongoClient: MongoClient
  private lateinit var mongoUserUtil: MongoUserUtil
  private lateinit var mongoUserUtilAnotherCompany: MongoUserUtil
  private lateinit var mongoAuth: MongoAuthentication
  private lateinit var mongoAuthAnotherCompany: MongoAuthentication
  private lateinit var mqttClient: MqttClient

  private lateinit var pgClient: SqlClient

  private val configuration = jsonObjectOf(
    "mqttID" to "mqtt",
    "relayID" to "mqtt",
    "ledStatus" to false,
    "latitude" to 0.1,
    "longitude" to 0.3,
    "wifi" to jsonObjectOf(
      "ssid" to "ssid",
      "password" to "pass"
    ),
    "forceReset" to true,
    "reboot" to false,
    "company" to "biot"
  )

  private val configurationAnotherCompany = jsonObjectOf(
    "mqttID" to "mqtt2",
    "relayID" to "mqtt2",
    "ledStatus" to false,
    "latitude" to 2,
    "longitude" to 3,
    "wifi" to jsonObjectOf(
      "ssid" to "ssid2",
      "password" to "pass2"
    ),
    "forceReset" to false
  )

  private val anotherCompanyName = "anotherCompany"
  private val anotherCompanyCollection = RELAYS_COLLECTION + "_$anotherCompanyName"

  private var itemBiot1Id: Int = -1
  private val itemBiot1 = jsonObjectOf(
    "beacon" to "e0:51:30:48:16:e5",
    "category" to null,
    "service" to "Bloc 1",
    "itemID" to "abc",
    "accessControlString" to "biot",
    "brand" to "ferrari",
    "model" to "GT",
    "supplier" to "sup",
    "purchaseDate" to LocalDate.of(2021, 7, 8).toString(),
    "purchasePrice" to 42.3,
    "originLocation" to "center1",
    "currentLocation" to "center2",
    "room" to "616",
    "contact" to "Monsieur Poirot",
    "currentOwner" to "Monsieur Dupont",
    "previousOwner" to "Monsieur Dupond",
    "orderNumber" to "abcdf",
    "color" to "red",
    "serialNumber" to "abcdf",
    "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
    "status" to "In maintenance",
    "comments" to "A comment",
    "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
    "lastModifiedBy" to "Monsieur Duport"
  )

  private val itemBiot2 = jsonObjectOf(
    "beacon" to "f0:15:b5:dd:24:38",
    "category" to null,
    "service" to "Bloc 2",
    "itemID" to "abc",
    "accessControlString" to "biot",
    "brand" to "ferrari",
    "model" to "GT",
    "supplier" to "sup",
    "purchaseDate" to LocalDate.of(2021, 7, 8).toString(),
    "purchasePrice" to 42.3,
    "originLocation" to "center1",
    "currentLocation" to "center2",
    "room" to "616",
    "contact" to "Monsieur Poirot",
    "currentOwner" to "Monsieur Dupont",
    "previousOwner" to "Monsieur Dupond",
    "orderNumber" to "abcdf",
    "color" to "red",
    "serialNumber" to "abcdf",
    "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
    "status" to "In maintenance",
    "comments" to "A comment",
    "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
    "lastModifiedBy" to "Monsieur Duport"
  )

  private val itemBiotInvalidMac = jsonObjectOf(
    "beacon" to "invalidMac",
    "category" to null,
    "service" to "Bloc 2",
    "itemID" to "abc",
    "accessControlString" to "biot",
    "brand" to "ferrari",
    "model" to "GT",
    "supplier" to "sup",
    "purchaseDate" to LocalDate.of(2021, 7, 8).toString(),
    "purchasePrice" to 42.3,
    "originLocation" to "center1",
    "currentLocation" to "center2",
    "room" to "616",
    "contact" to "Monsieur Poirot",
    "currentOwner" to "Monsieur Dupont",
    "previousOwner" to "Monsieur Dupond",
    "orderNumber" to "abcdf",
    "color" to "red",
    "serialNumber" to "abcdf",
    "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
    "status" to "In maintenance",
    "comments" to "A comment",
    "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
    "lastModifiedBy" to "Monsieur Duport"
  )

  private val itemBiot4 = jsonObjectOf(
    "beacon" to "f5:a8:ef:56:d7:c0",
    "category" to null,
    "service" to "Bloc 2",
    "itemID" to "abc",
    "accessControlString" to "biot",
    "brand" to "ferrari",
    "model" to "GT",
    "supplier" to "sup",
    "purchaseDate" to LocalDate.of(2021, 7, 8).toString(),
    "purchasePrice" to 42.3,
    "originLocation" to "center1",
    "currentLocation" to "center2",
    "room" to "616",
    "contact" to "Monsieur Poirot",
    "currentOwner" to "Monsieur Dupont",
    "previousOwner" to "Monsieur Dupond",
    "orderNumber" to "abcdf",
    "color" to "red",
    "serialNumber" to "abcdf",
    "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
    "status" to "In maintenance",
    "comments" to "A comment",
    "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
    "lastModifiedBy" to "Monsieur Duport"
  )

  private val itemAnother1 = jsonObjectOf(
    "beacon" to "12:23:34:ae:b5:d2",
    "category" to null,
    "service" to "Bloc 2",
    "itemID" to "abc",
    "accessControlString" to anotherCompanyName,
    "brand" to "ferrari",
    "model" to "GT",
    "supplier" to "sup",
    "purchaseDate" to LocalDate.of(2021, 7, 8).toString(),
    "purchasePrice" to 42.3,
    "originLocation" to "center1",
    "currentLocation" to "center2",
    "room" to "616",
    "contact" to "Monsieur Poirot",
    "currentOwner" to "Monsieur Dupont",
    "previousOwner" to "Monsieur Dupond",
    "orderNumber" to "abcdf",
    "color" to "red",
    "serialNumber" to "abcdf",
    "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
    "status" to "In maintenance",
    "comments" to "A comment",
    "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
    "lastModifiedBy" to "Monsieur Duport"
  )
  private val itemAnother2 = jsonObjectOf(
    "beacon" to "01:a2:d4:fe:56:21",
    "categoryID" to null,
    "service" to "Bloc 2",
    "itemID" to "abc",
    "accessControlString" to anotherCompanyName,
    "brand" to "ferrari",
    "model" to "GT",
    "supplier" to "sup",
    "purchaseDate" to LocalDate.of(2021, 7, 8).toString(),
    "purchasePrice" to 42.3,
    "originLocation" to "center1",
    "currentLocation" to "center2",
    "room" to "616",
    "contact" to "Monsieur Poirot",
    "currentOwner" to "Monsieur Dupont",
    "previousOwner" to "Monsieur Dupond",
    "orderNumber" to "abcdf",
    "color" to "red",
    "serialNumber" to "abcdf",
    "maintenanceDate" to LocalDate.of(2021, 8, 8).toString(),
    "status" to "In maintenance",
    "comments" to "A comment",
    "lastModifiedDate" to LocalDate.of(2021, 12, 25).toString(),
    "lastModifiedBy" to "Monsieur Duport"
  )


  @BeforeEach
  fun setup(vertx: Vertx, testContext: VertxTestContext) = runBlocking(vertx.dispatcher()) {
    val kafkaConfig = mapOf(
      "bootstrap.servers" to "localhost:$KAFKA_PORT",
      "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
      "value.deserializer" to "io.vertx.kafka.client.serialization.JsonObjectDeserializer",
      "auto.offset.reset" to "earliest",
      "enable.auto.commit" to "false",
      "group.id" to "ingester-test-${System.currentTimeMillis()}"
    )

    kafkaConsumer = KafkaConsumer.create(vertx, kafkaConfig)

    mqttClient = MqttClient.create(
      vertx,
      mqttClientOptionsOf(
        clientId = configuration["mqttID"],
        username = "relayBiot_${configuration.getString("mqttID")}",
        password = "relayBiot_${configuration.getString("mqttID")}".sha3256Hash(),
        ssl = true,
        maxMessageSize = 100_000
      )
    )

    mongoClient =
      MongoClient.createShared(vertx, jsonObjectOf("host" to "localhost", "port" to MONGO_PORT, "db_name" to "clients"))

    val usernameField = "mqttUsername"
    val passwordField = "mqttPassword"
    val mongoAuthOptions = mongoAuthenticationOptionsOf(
      collectionName = RELAYS_COLLECTION,
      passwordCredentialField = passwordField,
      passwordField = passwordField,
      usernameCredentialField = usernameField,
      usernameField = usernameField
    )

    mongoUserUtil = MongoUserUtil.create(
      mongoClient, mongoAuthOptions, mongoAuthorizationOptionsOf()
    )
    mongoAuth = MongoAuthentication.create(mongoClient, mongoAuthOptions)

    val usernameFieldAnotherCompany = "mqttUsername"
    val passwordFieldAnotherCompany = "mqttPassword"
    val mongoAuthOptionsAnotherCompany = mongoAuthenticationOptionsOf(
      collectionName = anotherCompanyCollection,
      passwordCredentialField = passwordFieldAnotherCompany,
      passwordField = passwordFieldAnotherCompany,
      usernameCredentialField = usernameFieldAnotherCompany,
      usernameField = usernameFieldAnotherCompany
    )

    mongoUserUtilAnotherCompany = MongoUserUtil.create(
      mongoClient, mongoAuthOptionsAnotherCompany, mongoAuthorizationOptionsOf()
    )
    mongoAuthAnotherCompany = MongoAuthentication.create(mongoClient, mongoAuthOptionsAnotherCompany)

    try {
      mongoClient
        .createIndexWithOptions(RELAYS_COLLECTION, jsonObjectOf("relayID" to 1), indexOptionsOf().unique(true)).await()

      mongoClient.createIndexWithOptions(
        RELAYS_COLLECTION,
        jsonObjectOf("mqttID" to 1),
        indexOptionsOf().unique(true)
      ).await()

      mongoClient.createIndexWithOptions(
        RELAYS_COLLECTION, jsonObjectOf("mqttUsername" to 1),
        indexOptionsOf().unique(true)
      ).await()

      mongoClient
        .createIndexWithOptions(anotherCompanyCollection, jsonObjectOf("relayID" to 1), indexOptionsOf().unique(true))
        .await()

      mongoClient.createIndexWithOptions(
        anotherCompanyCollection,
        jsonObjectOf("mqttID" to 1),
        indexOptionsOf().unique(true)
      ).await()

      mongoClient.createIndexWithOptions(
        anotherCompanyCollection, jsonObjectOf("mqttUsername" to 1),
        indexOptionsOf().unique(true)
      ).await()

      dropAllRelays()
      insertRelays()
      vertx.deployVerticle(RelaysCommunicationVerticle()).await()
      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }

    // Initialize TimescaleDB
    val pgConnectOptions =
      pgConnectOptionsOf(
        port = RelaysCommunicationVerticle.TIMESCALE_PORT,
        host = RelaysCommunicationVerticle.TIMESCALE_HOST,
        database = "biot",
        user = "biot",
        password = "biot",
        sslMode = if (RelaysCommunicationVerticle.TIMESCALE_HOST != "localhost") SslMode.REQUIRE else null, // SSL is disabled when testing
        trustAll = true,
        cachePreparedStatements = true
      )
    pgClient = PgPool.client(vertx, pgConnectOptions, poolOptionsOf())

    pgClient.query(
      """
      CREATE TABLE IF NOT EXISTS items_$anotherCompanyName
(
    id SERIAL PRIMARY KEY,
    beacon VARCHAR(17) UNIQUE,
    categoryID INTEGER,
    service VARCHAR(100),
    itemID VARCHAR(50),
    accessControlString VARCHAR(2048),
    brand VARCHAR(100),
    model VARCHAR(100),
    supplier VARCHAR(100),
    purchaseDate DATE,
    purchasePrice DECIMAL(15, 6),
    originLocation VARCHAR(100),
    currentLocation VARCHAR(100),
    room VARCHAR(100),
    contact VARCHAR(100),
    currentOwner VARCHAR(100),
    previousOwner VARCHAR(100),
    orderNumber VARCHAR(100),
    color VARCHAR(100),
    serialNumber VARCHAR(100),
    maintenanceDate DATE,
    status VARCHAR(100),
    comments VARCHAR(200),
    lastModifiedDate DATE,
    lastModifiedBy VARCHAR(100),
    FOREIGN KEY(categoryID) REFERENCES categories(id) ON UPDATE CASCADE ON DELETE SET NULL
);
    """.trimIndent()
    ).execute().await()

    insertItems()
  }

  private suspend fun insertItems() {
    val result = pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          itemBiot1["beacon"],
          itemBiot1["categoryID"],
          itemBiot1["service"],
          itemBiot1["itemID"],
          itemBiot1["accessControlString"],
          itemBiot1["brand"],
          itemBiot1["model"],
          itemBiot1["supplier"],
          LocalDate.parse(itemBiot1["purchaseDate"]),
          itemBiot1["purchasePrice"],
          itemBiot1["originLocation"],
          itemBiot1["currentLocation"],
          itemBiot1["room"],
          itemBiot1["contact"],
          itemBiot1["currentOwner"],
          itemBiot1["previousOwner"],
          itemBiot1["orderNumber"],
          itemBiot1["color"],
          itemBiot1["serialNumber"],
          LocalDate.parse(itemBiot1["maintenanceDate"]),
          itemBiot1["status"],
          itemBiot1["comments"],
          LocalDate.parse(itemBiot1["lastModifiedDate"]),
          itemBiot1["lastModifiedBy"]
        )
      ).await()

    itemBiot1Id = result.iterator().next().getInteger("id")

    pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          itemBiot2["beacon"],
          itemBiot2["categoryID"],
          itemBiot2["service"],
          itemBiot2["itemID"],
          itemBiot2["accessControlString"],
          itemBiot2["brand"],
          itemBiot2["model"],
          itemBiot2["supplier"],
          LocalDate.parse(itemBiot2["purchaseDate"]),
          itemBiot2["purchasePrice"],
          itemBiot2["originLocation"],
          itemBiot2["currentLocation"],
          itemBiot2["room"],
          itemBiot2["contact"],
          itemBiot2["currentOwner"],
          itemBiot2["previousOwner"],
          itemBiot2["orderNumber"],
          itemBiot2["color"],
          itemBiot2["serialNumber"],
          LocalDate.parse(itemBiot2["maintenanceDate"]),
          itemBiot2["status"],
          itemBiot2["comments"],
          LocalDate.parse(itemBiot2["lastModifiedDate"]),
          itemBiot2["lastModifiedBy"]
        )
      ).await()

    pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          itemBiotInvalidMac["beacon"],
          itemBiotInvalidMac["categoryID"],
          itemBiotInvalidMac["service"],
          itemBiotInvalidMac["itemID"],
          itemBiotInvalidMac["accessControlString"],
          itemBiotInvalidMac["brand"],
          itemBiotInvalidMac["model"],
          itemBiotInvalidMac["supplier"],
          LocalDate.parse(itemBiotInvalidMac["purchaseDate"]),
          itemBiotInvalidMac["purchasePrice"],
          itemBiotInvalidMac["originLocation"],
          itemBiotInvalidMac["currentLocation"],
          itemBiotInvalidMac["room"],
          itemBiotInvalidMac["contact"],
          itemBiotInvalidMac["currentOwner"],
          itemBiotInvalidMac["previousOwner"],
          itemBiotInvalidMac["orderNumber"],
          itemBiotInvalidMac["color"],
          itemBiotInvalidMac["serialNumber"],
          LocalDate.parse(itemBiotInvalidMac["maintenanceDate"]),
          itemBiotInvalidMac["status"],
          itemBiotInvalidMac["comments"],
          LocalDate.parse(itemBiotInvalidMac["lastModifiedDate"]),
          itemBiotInvalidMac["lastModifiedBy"]
        )
      ).await()

    pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          itemBiot4["beacon"],
          itemBiot4["categoryID"],
          itemBiot4["service"],
          itemBiot4["itemID"],
          itemBiot4["accessControlString"],
          itemBiot4["brand"],
          itemBiot4["model"],
          itemBiot4["supplier"],
          LocalDate.parse(itemBiot4["purchaseDate"]),
          itemBiot4["purchasePrice"],
          itemBiot4["originLocation"],
          itemBiot4["currentLocation"],
          itemBiot4["room"],
          itemBiot4["contact"],
          itemBiot4["currentOwner"],
          itemBiot4["previousOwner"],
          itemBiot4["orderNumber"],
          itemBiot4["color"],
          itemBiot4["serialNumber"],
          LocalDate.parse(itemBiot4["maintenanceDate"]),
          itemBiot4["status"],
          itemBiot4["comments"],
          LocalDate.parse(itemBiot4["lastModifiedDate"]),
          itemBiot4["lastModifiedBy"]
        )
      ).await()

    pgClient.preparedQuery(insertItem("items_$anotherCompanyName"))
      .execute(
        Tuple.of(
          itemAnother1["beacon"],
          itemAnother1["categoryID"],
          itemAnother1["service"],
          itemAnother1["itemID"],
          itemAnother1["accessControlString"],
          itemAnother1["brand"],
          itemAnother1["model"],
          itemAnother1["supplier"],
          LocalDate.parse(itemAnother1["purchaseDate"]),
          itemAnother1["purchasePrice"],
          itemAnother1["originLocation"],
          itemAnother1["currentLocation"],
          itemAnother1["room"],
          itemAnother1["contact"],
          itemAnother1["currentOwner"],
          itemAnother1["previousOwner"],
          itemAnother1["orderNumber"],
          itemAnother1["color"],
          itemAnother1["serialNumber"],
          LocalDate.parse(itemAnother1["maintenanceDate"]),
          itemAnother1["status"],
          itemAnother1["comments"],
          LocalDate.parse(itemAnother1["lastModifiedDate"]),
          itemAnother1["lastModifiedBy"]
        )
      ).await()

    pgClient.preparedQuery(insertItem("items_$anotherCompanyName"))
      .execute(
        Tuple.of(
          itemAnother2["beacon"],
          itemAnother2["categoryID"],
          itemAnother2["service"],
          itemAnother2["itemID"],
          itemAnother2["accessControlString"],
          itemAnother2["brand"],
          itemAnother2["model"],
          itemAnother2["supplier"],
          LocalDate.parse(itemAnother2["purchaseDate"]),
          itemAnother2["purchasePrice"],
          itemAnother2["originLocation"],
          itemAnother2["currentLocation"],
          itemAnother2["room"],
          itemAnother2["contact"],
          itemAnother2["currentOwner"],
          itemAnother2["previousOwner"],
          itemAnother2["orderNumber"],
          itemAnother2["color"],
          itemAnother2["serialNumber"],
          LocalDate.parse(itemAnother2["maintenanceDate"]),
          itemAnother2["status"],
          itemAnother2["comments"],
          LocalDate.parse(itemAnother2["lastModifiedDate"]),
          itemAnother2["lastModifiedBy"]
        )
      ).await()

  }

  /**
   * Generates a list of 1026 mac addresses as string with the following format "aabbccddeeff"
   */
  private fun gen1026UniqueMacs(): List<String> {
    val possibleChar = "0123456789abcdef"
    val res = arrayListOf<String>()
    for (i in 1..1026) {
      var newMac = ""
      do {
        for (j in 0..12) {
          newMac += possibleChar[possibleChar.indices.random()]
        }
      } while (res.contains(newMac))
      res.add(newMac)
    }
    assert(res.size == 1026)
    return res
  }

  private suspend fun add1026Items() {
    val macs1026 = gen1026UniqueMacs()
    for (i in 0 until 1026) {
      pgClient.preparedQuery(insertItem("items"))
        .execute(
          Tuple.of(
            macs1026[i],
            itemBiot1["categoryID"],
            itemBiot1["service"],
            "${itemBiot1.getString("itemID")}_$i",
            itemBiot1["accessControlString"],
            itemBiot1["brand"],
            itemBiot1["model"],
            itemBiot1["supplier"],
            LocalDate.parse(itemBiot1["purchaseDate"]),
            itemBiot1["purchasePrice"],
            itemBiot1["originLocation"],
            itemBiot1["currentLocation"],
            itemBiot1["room"],
            itemBiot1["contact"],
            itemBiot1["currentOwner"],
            itemBiot1["previousOwner"],
            itemBiot1["orderNumber"],
            itemBiot1["color"],
            itemBiot1["serialNumber"],
            LocalDate.parse(itemBiot1["maintenanceDate"]),
            itemBiot1["status"],
            itemBiot1["comments"],
            LocalDate.parse(itemBiot1["lastModifiedDate"]),
            itemBiot1["lastModifiedBy"]
          )
        ).await()
    }
  }

  private fun dropAllItems(): CompositeFuture {
    return CompositeFuture.all(
      pgClient.query("DELETE FROM items").execute(),
      pgClient.query("DELETE FROM items_$anotherCompanyName").execute(),
      pgClient.query("DROP TABLE items_$anotherCompanyName").execute()
    )
  }

  private suspend fun dropAllRelays() {
    mongoClient.removeDocuments(RELAYS_COLLECTION, jsonObjectOf()).await()
    mongoClient.removeDocuments(anotherCompanyCollection, jsonObjectOf()).await()
  }

  private suspend fun insertRelays(): JsonObject {
    fun computeUsernameAndPassword(mqttID: String, mongoAuthentication: MongoAuthentication): Pair<String, String> {
      val username = "relayBiot_$mqttID"
      val password = "relayBiot_$mqttID".sha3256Hash()
      val salt = ByteArray(16)
      SecureRandom().nextBytes(salt)
      return username to mongoAuthentication.hash("pbkdf2", String(Base64.getEncoder().encode(salt)), password)
    }

    val mqttID = configuration.getString("mqttID")
    val (username1, password1) = computeUsernameAndPassword(mqttID, mongoAuth)
    val docID = mongoUserUtil.createHashedUser(username1, password1).await()
    val query = jsonObjectOf("_id" to docID)
    val extraInfo = jsonObjectOf(
      "\$set" to configuration
    )
    mongoClient.findOneAndUpdate(RELAYS_COLLECTION, query, extraInfo).await()

    val mqttID2 = configurationAnotherCompany.getString("mqttID")
    val (username2, password2) = computeUsernameAndPassword(mqttID2, mongoAuthAnotherCompany)
    val docID2 = mongoUserUtilAnotherCompany.createHashedUser(username2, password2).await()
    val query2 = jsonObjectOf("_id" to docID2)
    val extraInfo2 = jsonObjectOf(
      "\$set" to configurationAnotherCompany
    )
    return mongoClient.findOneAndUpdate(anotherCompanyCollection, query2, extraInfo2).await()
  }

  // TimescaleDB PostgreSQL queries for items
  private fun insertItem(itemsTable: String, customId: Boolean = false) =
    if (customId) "INSERT INTO $itemsTable (id, beacon, categoryid, service, itemid, accessControlString, brand, model, supplier, purchasedate, purchaseprice, originlocation, currentlocation, room, contact, currentowner, previousowner, ordernumber, color, serialnumber, maintenancedate, status, comments, lastmodifieddate, lastmodifiedby) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24, $25) RETURNING id"
    else "INSERT INTO $itemsTable (beacon, categoryid, service, itemid, accessControlString, brand, model, supplier, purchasedate, purchaseprice, originlocation, currentlocation, room, contact, currentowner, previousowner, ordernumber, color, serialnumber, maintenancedate, status, comments, lastmodifieddate, lastmodifiedby) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24) RETURNING id"

  private fun updateItem(itemsTable: String, updatedColumns: List<String>, accessControlString: String): String {
    val columnsWithValues = updatedColumns.mapIndexed { index, colName -> "$colName = \$${index + 2}" }.joinToString()
    return "UPDATE $itemsTable SET $columnsWithValues WHERE id = $1 AND (accessControlString LIKE '$accessControlString:%' OR accessControlString LIKE '$accessControlString')"
  }

  @AfterEach
  fun cleanup(vertx: Vertx, testContext: VertxTestContext) = runBlocking(vertx.dispatcher()) {
    try {
      dropAllRelays()
      dropAllItems()
      mongoClient.close().await()
      pgClient.close().await()
      testContext.completeNow()
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @Test
  @DisplayName("A MQTT client upon subscription receives the last configuration")
  fun clientSubscribesAndReceivesLastConfig(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            testContext.verify {
              val expected = configuration.copy().apply {
                remove("mqttID")
                remove("mqttUsername")
                remove("ledStatus")
                put(
                  "whiteList",
                  "e051304816e5f015b5dd2438f5a8ef56d7c0"
                ) //itemBiot1, itemBiot2, itemBiot4 mac addresses without :
                put("connected", true)
                put("company", "biot")
              }
              expectThat(msg.payload().toJsonObject()).isEqualTo(expected)
              testContext.completeNow()
            }
          }
        }.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("After the connection of the client, the relay is connected = true in the DB immediately")
  fun clientSubscribesAndIsConnectedInMongo(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            testContext.verify {
              mongoClient.findOne(RELAYS_COLLECTION, jsonObjectOf("mqttID" to configuration["mqttID"]), jsonObjectOf())
                .onSuccess { relayJson ->
                  expectThat(relayJson).isNotNull()
                  expectThat(relayJson.getBoolean("connected")).isTrue()
                  testContext.completeNow()
                }.onFailure {
                  testContext.failNow("Error while accessing MongoDB")
                }
            }
          }
        }.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("When the client disconnects, the relay is connected = false in the DB after max 20 sec")
  fun clientSubscribesAndDisconnectsIsCorrectInMongo(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            mqttClient.disconnect().onSuccess {
              vertx.setTimer(UPDATE_CONFIG_INTERVAL_SECONDS + 5_000) {
                mongoClient.findOne(
                  RELAYS_COLLECTION,
                  jsonObjectOf("mqttID" to configuration["mqttID"]),
                  jsonObjectOf()
                )
                  .onSuccess { relayJson ->
                    testContext.verify {
                      expectThat(relayJson.getBoolean("connected")).isFalse()
                      testContext.completeNow()
                    }
                  }.onFailure {
                    testContext.failNow("Error while accessing MongoDB")
                  }
              }
            }.onFailure(testContext::failNow)
          }
        }.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("A MQTT client without authentication is refused connection")
  fun clientWithoutAuthIsRefusedConnection(vertx: Vertx, testContext: VertxTestContext) =
    runBlocking(vertx.dispatcher()) {
      val client = MqttClient.create(vertx, mqttClientOptionsOf(ssl = true))

      try {
        client.connect(MQTT_PORT, MQTT_HOST).await()
        testContext.failNow("The client was able to connect without authentication")
      } catch (error: Throwable) {
        testContext.completeNow()
      }
    }

  @Test
  @DisplayName("A MQTT client with a wrong password is refused connection")
  fun clientWithWrongPasswordIsRefusedConnection(vertx: Vertx, testContext: VertxTestContext) =
    runBlocking(vertx.dispatcher()) {
      val client = MqttClient.create(
        vertx,
        mqttClientOptionsOf(
          clientId = configuration["mqttID"],
          username = "relayBiot_${configuration.getString("mqttID")}",
          password = "wrongPassword",
          ssl = true
        )
      )

      try {
        client.connect(MQTT_PORT, MQTT_HOST).await()
        testContext.failNow("The client was able to connect with a wrong password")
      } catch (error: Throwable) {
        testContext.completeNow()
      }
    }

  @Test
  @DisplayName("A MQTT client not associated to a company is refused connection")
  fun clientWithNoCompanyIsRefusedConnection(vertx: Vertx, testContext: VertxTestContext) =
    runBlocking(vertx.dispatcher()) {
      val client = MqttClient.create(
        vertx,
        mqttClientOptionsOf(
          clientId = "unknownID",
          username = "relayBiot_${configuration.getString("mqttID")}",
          password = "password",
          ssl = true
        )
      )

      try {
        client.connect(MQTT_PORT, MQTT_HOST).await()
        testContext.failNow("The client was able to connect without a company associated")
      } catch (error: Throwable) {
        testContext.completeNow()
      }
    }

  @Test // TODO remove once the relays are delivered to HJU
  @DisplayName("A MQTT client receives the last configuration on update.parameters once an update is received via the event bus")
  fun clientReceivesUpdateOnUpdateParameters(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val message = jsonObjectOf("mqttID" to "mqtt")
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            val json = msg.payload().toJsonObject()
            testContext.verify {
              val messageWithoutMqttID = configuration.copy().apply {
                remove("mqttID")
                remove("mqttUsername")
                remove("ledStatus")
                put(
                  "whiteList",
                  "e051304816e5f015b5dd2438f5a8ef56d7c0"
                ) //itemBiot1, itemBiot2, itemBiot4 mac addresses without :
                put("connected", true)
              }
              expectThat(json).isEqualTo(messageWithoutMqttID)
              testContext.completeNow()
            }
          }
        }.connect(MQTT_PORT, MQTT_HOST).await()

        mqttClient.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
        vertx.eventBus().send(RELAYS_UPDATE_ADDRESS, message)
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("A MQTT client receives the last configuration on relay.management once an update is received via the event bus")
  fun clientReceivesUpdateOnRelayManagement(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val message = jsonObjectOf("mqttID" to "mqtt")
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == RELAYS_MANAGEMENT_TOPIC) {
            val json = msg.payload().toJsonObject()
            testContext.verify {
              val messageWithoutMqttID = configuration.copy().apply {
                remove("mqttID")
                remove("mqttUsername")
                remove("ledStatus")
                put(
                  "whiteList",
                  "e051304816e5f015b5dd2438f5a8ef56d7c0"
                ) //itemBiot1, itemBiot2, itemBiot4 mac addresses without :
                put("connected", true)
              }
              expectThat(json).isEqualTo(messageWithoutMqttID)
              testContext.completeNow()
            }
          }
        }.connect(MQTT_PORT, MQTT_HOST).await()

        mqttClient.subscribe(RELAYS_MANAGEMENT_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
        vertx.eventBus().send(RELAYS_UPDATE_ADDRESS, message)
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("A well-formed MQTT JSON message is ingested and streamed to Kafka")
  fun mqttMessageIsIngested(vertx: Vertx, testContext: VertxTestContext) = runBlocking(vertx.dispatcher()) {
    val message = jsonObjectOf(
      "relayID" to "abc",
      "beacons" to jsonArrayOf(
        jsonObjectOf(
          "mac" to "aa:aa:aa:aa:aa:aa",
          "rssi" to -60.0,
          "battery" to 42,
          "temperature" to 25,
          "status" to 0
        ),
        jsonObjectOf(
          "mac" to "bb:aa:aa:aa:aa:aa",
          "rssi" to -59.0,
          "battery" to 100,
          "temperature" to 20,
          "status" to 1
        )
      ),
      "latitude" to 2.3,
      "longitude" to 2.3,
      "floor" to 1
    )

    try {
      mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
      mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
      kafkaConsumer.subscribe(INGESTION_TOPIC).await()
      kafkaConsumer.handler { record ->
        testContext.verify {
          val relayID = message.getString("relayID")
          expectThat(record.key()).isEqualTo(relayID)
          val json = record.value()
          expect {
            that(json.getString("relayID")).isEqualTo(relayID)
            that(json.getString("timestamp")).isNotNull()
            that(json.getJsonArray("beacons")).isEqualTo(message.getJsonArray("beacons"))
            that(json.getDouble("latitude")).isEqualTo(message.getDouble("latitude"))
            that(json.getDouble("longitude")).isEqualTo(message.getDouble("longitude"))
            that(json.getInteger("floor")).isEqualTo(message.getInteger("floor"))
            that(json.getDouble("temperature")).isEqualTo(message.getDouble("temperature"))
          }
          testContext.completeNow()
        }
      }
    } catch (error: Throwable) {
      testContext.failNow(error)
    }
  }

  @ExperimentalCoroutinesApi // for channel.isEmpty
  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because it is missing fields")
  fun invalidMqttMessageIsNotIngestedWrongFields(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val message = jsonObjectOf(
        "relayID" to "abc",
        "beacons" to jsonArrayOf(
          jsonObjectOf(
            "mac" to "aa:aa:aa:aa:aa:aa",
            "rssi" to -60.0,
            "battery" to 42,
            "temperature" to 25,
            "status" to 0
          ),
          jsonObjectOf(
            "mac" to "bb:aa:aa:aa:aa:aa",
            "rssi" to -59.0,
            "battery" to 100,
            "temperature" to 20,
            "status" to 1
          )
        ),
        "longitude" to 2.3,
        "floor" to 1
      )

      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toReceiveChannel(vertx)
        testContext.verify {
          if (stream.isEmpty) {
            testContext.completeNow()
          } else {
            testContext.failNow("The message was ingested")
          }
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @ExperimentalCoroutinesApi
  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because it is missing fields in the beacons field")
  fun invalidMqttMessageIsNotIngestedWrongBeaconsFields(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val message = jsonObjectOf(
        "relayID" to "abc",
        "beacons" to jsonArrayOf(
          jsonObjectOf(
            "mac" to "aa:aa:aa:aa:aa:aa",
            "rssi" to -60.0,
            "temperature" to 25,
            "status" to 0
          ),
          jsonObjectOf(
            "mac" to "bb:aa:aa:aa:aa:aa",
            "rssi" to -59.0,
            "battery" to 100,
            "temperature" to 20,
            "status" to 1
          )
        ),
        "longitude" to 2.3,
        "floor" to 1
      )

      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toReceiveChannel(vertx)
        testContext.verify {
          if (stream.isEmpty) {
            testContext.completeNow()
          } else {
            testContext.failNow("The message was ingested")
          }
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @ExperimentalCoroutinesApi
  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because it has zero coordinates")
  fun invalidMqttMessageIsNotIngestedWrongCoordinates(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val message = jsonObjectOf(
        "relayID" to "abc",
        "beacons" to jsonArrayOf(
          jsonObjectOf(
            "mac" to "aa:aa:aa:aa:aa:aa",
            "rssi" to -60.0,
            "battery" to 42,
            "temperature" to 25,
            "status" to 0
          ),
          jsonObjectOf(
            "mac" to "bb:aa:aa:aa:aa:aa",
            "rssi" to -59.0,
            "battery" to 100,
            "temperature" to 20,
            "status" to 1
          )
        ),
        "latitude" to 2.3,
        "longitude" to 0,
        "floor" to 1
      )

      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toReceiveChannel(vertx)
        testContext.verify {
          if (stream.isEmpty) {
            testContext.completeNow()
          } else {
            testContext.failNow("The message was ingested")
          }
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @ExperimentalCoroutinesApi
  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because it has empty MACs")
  fun invalidMqttMessageIsNotIngestedEmptyMac(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val message = jsonObjectOf(
        "relayID" to "abc",
        "beacons" to jsonArrayOf(
          jsonObjectOf(
            "mac" to "",
            "rssi" to -60.0,
            "battery" to 42,
            "temperature" to 25,
            "status" to 0
          ),
          jsonObjectOf(
            "mac" to "bb:aa:aa:aa:aa:aa",
            "rssi" to -59.0,
            "battery" to 0,
            "temperature" to 20,
            "status" to 1
          )
        ),
        "latitude" to 2.3,
        "longitude" to 2.3,
        "floor" to 1
      )

      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toReceiveChannel(vertx)
        testContext.verify {
          if (stream.isEmpty) {
            testContext.completeNow()
          } else {
            testContext.failNow("The message was ingested")
          }
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @ExperimentalCoroutinesApi
  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because a beacon has a too large battery level")
  fun invalidMqttMessageIsNotIngestedInvalidBatteryTooLarge(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val message = jsonObjectOf(
        "relayID" to "abc",
        "beacons" to jsonArrayOf(
          jsonObjectOf(
            "mac" to "aa:aa:aa:aa:aa:aa",
            "rssi" to -60.0,
            "battery" to 101,
            "temperature" to 25,
            "status" to 0
          ),
          jsonObjectOf(
            "mac" to "bb:aa:aa:aa:aa:aa",
            "rssi" to -59.0,
            "battery" to 100,
            "temperature" to 20,
            "status" to 1
          )
        ),
        "latitude" to 2.3,
        "longitude" to 2.3,
        "floor" to 1
      )

      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toReceiveChannel(vertx)
        testContext.verify {
          if (stream.isEmpty) {
            testContext.completeNow()
          } else {
            testContext.failNow("The message was ingested")
          }
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @ExperimentalCoroutinesApi
  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because a beacon has a too small battery level")
  fun invalidMqttMessageIsNotIngestedInvalidBatteryTooSmall(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val message = jsonObjectOf(
        "relayID" to "abc",
        "beacons" to jsonArrayOf(
          jsonObjectOf(
            "mac" to "aa:aa:aa:aa:aa:aa",
            "rssi" to -60.0,
            "battery" to -10,
            "temperature" to 25,
            "status" to 0
          ),
          jsonObjectOf(
            "mac" to "bb:aa:aa:aa:aa:aa",
            "rssi" to -59.0,
            "battery" to 100,
            "temperature" to 20,
            "status" to 1
          )
        ),
        "latitude" to 2.3,
        "longitude" to 2.3,
        "floor" to 1
      )

      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toReceiveChannel(vertx)
        testContext.verify {
          if (stream.isEmpty) {
            testContext.completeNow()
          } else {
            testContext.failNow("The message was ingested")
          }
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @ExperimentalCoroutinesApi
  @Test
  @DisplayName("An invalid MQTT JSON message is not ingested because a beacon has an invalid status")
  fun invalidMqttMessageIsNotIngestedInvalidStatus(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val message = jsonObjectOf(
        "relayID" to "abc",
        "beacons" to jsonArrayOf(
          jsonObjectOf(
            "mac" to "aa:aa:aa:aa:aa:aa",
            "rssi" to -60.0,
            "battery" to -10,
            "temperature" to 25,
            "status" to -1
          ),
          jsonObjectOf(
            "mac" to "bb:aa:aa:aa:aa:aa",
            "rssi" to -59.0,
            "battery" to 100,
            "temperature" to 20,
            "status" to 2
          )
        ),
        "latitude" to 2.3,
        "longitude" to 2.3,
        "floor" to 1
      )

      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toReceiveChannel(vertx)
        testContext.verify {
          if (stream.isEmpty) {
            testContext.completeNow()
          } else {
            testContext.failNow("The message was ingested")
          }
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("A valid MQTT JSON message is ingested when a sentinel temperature value is specified")
  fun validMqttMessageIsIngestedSentinelTemperature(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val message = jsonObjectOf(
        "relayID" to "abc",
        "beacons" to jsonArrayOf(
          jsonObjectOf(
            "mac" to "aa:aa:aa:aa:aa:aa",
            "rssi" to -60.0,
            "battery" to 10,
            "temperature" to -256,
            "status" to 1
          ),
          jsonObjectOf(
            "mac" to "bb:aa:aa:aa:aa:aa",
            "rssi" to -59.0,
            "battery" to 100,
            "temperature" to 20,
            "status" to 2
          )
        ),
        "latitude" to 2.3,
        "longitude" to 2.3,
        "floor" to 1
      )

      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        testContext.verify {
          kafkaConsumer.handler {
            testContext.completeNow()
          }
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("A valid MQTT JSON message is ingested when a sentinel battery value is specified")
  fun validMqttMessageIsIngestedSentinelBattery(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val message = jsonObjectOf(
        "relayID" to "abc",
        "beacons" to jsonArrayOf(
          jsonObjectOf(
            "mac" to "aa:aa:aa:aa:aa:aa",
            "rssi" to -60.0,
            "battery" to 10,
            "temperature" to 24,
            "status" to 1
          ),
          jsonObjectOf(
            "mac" to "bb:aa:aa:aa:aa:aa",
            "rssi" to -59.0,
            "battery" to -1,
            "temperature" to 20,
            "status" to 2
          )
        ),
        "latitude" to 2.3,
        "longitude" to 2.3,
        "floor" to 1
      )

      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        testContext.verify {
          kafkaConsumer.handler {
            testContext.completeNow()
          }
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("A valid MQTT JSON message is ingested when a sentinel status value is specified")
  fun validMqttMessageIsIngestedSentinelStatus(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val message = jsonObjectOf(
        "relayID" to "abc",
        "beacons" to jsonArrayOf(
          jsonObjectOf(
            "mac" to "aa:aa:aa:aa:aa:aa",
            "rssi" to -60.0,
            "battery" to 10,
            "temperature" to 24,
            "status" to -1
          ),
          jsonObjectOf(
            "mac" to "bb:aa:aa:aa:aa:aa",
            "rssi" to -59.0,
            "battery" to 99,
            "temperature" to 20,
            "status" to 2
          )
        ),
        "latitude" to 2.3,
        "longitude" to 2.3,
        "floor" to 1
      )

      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        testContext.verify {
          kafkaConsumer.handler {
            testContext.completeNow()
          }
        }
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }


  @Test
  @DisplayName("A MQTT client for another company than biot gets the right last config at subscription")
  fun clientFromAnotherCompanyGetsRightConfig(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val client = MqttClient.create(
        vertx,
        mqttClientOptionsOf(
          clientId = configurationAnotherCompany["mqttID"],
          username = "relayBiot_${configurationAnotherCompany.getString("mqttID")}",
          password = "relayBiot_${configurationAnotherCompany.getString("mqttID")}".sha3256Hash(),
          ssl = true,
        )
      )

      try {
        client.connect(MQTT_PORT, MQTT_HOST).await()
        client.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            testContext.verify {
              val expected = configurationAnotherCompany.copy().apply {
                remove("mqttID")
                remove("mqttUsername")
                remove("ledStatus")
                put("whiteList", "122334aeb5d201a2d4fe5621") // itemAnother1 and itemAnother2 mac addresses without :
                put("connected", true)
                put("company", anotherCompanyName)
              }
              expectThat(msg.payload().toJsonObject()).isEqualTo(expected)
              testContext.completeNow()
            }
          }
        }.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  @DisplayName("A MQTT client receives the config after 20 seconds if one item's beacon changed")
  fun clientSubscribesAndReceivesLastConfigAfter20SecWhenModified(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      var msgCounter = 0
      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            when (msgCounter) {
              0 -> {
                // First msg at subscription
                testContext.verify {
                  val expected = configuration.copy().apply {
                    remove("mqttID")
                    remove("mqttUsername")
                    remove("ledStatus")
                    put(
                      "whiteList",
                      "e051304816e5f015b5dd2438f5a8ef56d7c0"
                    ) //itemBiot1, itemBiot2, itemBiot4 mac addresses without :
                    put("connected", true)
                  }
                  expectThat(msg.payload().toJsonObject()).isEqualTo(expected)
                }
                msgCounter += 1
                pgClient.preparedQuery(updateItem("items", listOf("beacon"), "biot"))
                  .execute(Tuple.tuple(listOf(itemBiot1Id, "aa:bb:cc:dd:ee:ff")))

              }
              1 -> {
                testContext.verify {
                  val expected = configuration.copy().apply {
                    remove("mqttID")
                    remove("mqttUsername")
                    remove("ledStatus")
                    put(
                      "whiteList",
                      "aabbccddeefff015b5dd2438f5a8ef56d7c0"
                    ) //itemBiot1, itemBiot2, itemBiot4 mac addresses without :
                    put("connected", true)
                  }
                  expectThat(msg.payload().toJsonObject()).isEqualTo(expected)
                }
              }
              else -> {
                testContext.failNow("received more than 2 msgs")
              }
            }
          }
        }.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }

      vertx.setTimer(UPDATE_CONFIG_INTERVAL_SECONDS + 5_000) {
        testContext.completeNow()
      }
    }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  @DisplayName("A MQTT client does NOT receive the config after 25 seconds if no item's beacon changed")
  fun clientSubscribesAndDoesNotReceiveLastConfigAfter25SecWhenUnmodified(
    vertx: Vertx,
    testContext: VertxTestContext
  ): Unit =
    runBlocking(vertx.dispatcher()) {
      var msgCounter = 0
      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            when (msgCounter) {
              0 -> {
                // First msg at subscription
                testContext.verify {
                  val expected = configuration.copy().apply {
                    remove("mqttID")
                    remove("mqttUsername")
                    remove("ledStatus")
                    put(
                      "whiteList",
                      "e051304816e5f015b5dd2438f5a8ef56d7c0"
                    ) //itemBiot1, itemBiot2, itemBiot4 mac addresses without :
                    put("connected", true)
                  }
                  expectThat(msg.payload().toJsonObject()).isEqualTo(expected)
                }
                msgCounter += 1
              }
              else -> {
                testContext.failNow("received more than 1 msgs")
              }
            }
          }
        }.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
      vertx.setTimer(UPDATE_CONFIG_INTERVAL_SECONDS + 5_000) {
        testContext.completeNow()
      }
    }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  @DisplayName("A MQTT client never receives more than 1024 mac addresses in the whitelist")
  fun clientSubscribesAndNeverReceivesMoreThan1024Macs(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      var msgCounter = 0
      add1026Items()
      try {
        mqttClient.connect(MQTT_PORT, "localhost").await()
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            // First msg at subscription
            testContext.verify {
              expectThat(
                msg.payload().toJsonObject().getString("whiteList").length
              ).isLessThanOrEqualTo(1024 * 6 * 2)
              testContext.completeNow()
            }
            msgCounter += 1
          }
        }.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("Liveness check works")
  fun livenessCheckWorks(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val client = vertx.createNetClient(netClientOptionsOf())
        client.connect(LIVENESS_PORT, MQTT_HOST).await()
        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("Readiness check works")
  fun readinessCheckWorks(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        val client = vertx.createNetClient(netClientOptionsOf())
        client.connect(READINESS_PORT, MQTT_HOST).await()
        testContext.completeNow()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  @Test
  @DisplayName("Emergency request returns repo url + True as flag if relayID = relay_0")
  fun emergencyRequestFlagTrueWhenDefault(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
      } When {
        queryParam("relayID", "relay_0")
        get("/relays-emergency")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response.getBoolean("forceReset")).isTrue()
      expectThat(response.getString("repoURL")).isEqualTo(RELAY_REPO_URL)
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Emergency request returns repo url + False as flag if no relayID is passed")
  fun emergencyRequestFlagFalseWhenNoRelayID(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
      } When {
        get("/relays-emergency")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response.getBoolean("forceReset")).isFalse()
      expectThat(response.getString("repoURL")).isEqualTo(RELAY_REPO_URL)
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Emergency request returns repo url + False as flag if relayID is not in DB")
  fun emergencyRequestFlagFalseWhenUnknown(testContext: VertxTestContext) {
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
      } When {
        queryParam("relayID", "unknownRelay")
        get("/relays-emergency")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response.getBoolean("forceReset")).isFalse()
      expectThat(response.getString("repoURL")).isEqualTo(RELAY_REPO_URL)
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Emergency request returns repo url + the flag from the db if relayID is in DB")
  fun emergencyRequestFlagCorrectWhenKnown(testContext: VertxTestContext) {
    val relayId = configuration.getString("relayID")
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
      } When {
        queryParam("relayID", relayId)
        get("/relays-emergency")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response.getBoolean("forceReset")).isEqualTo(configuration.getBoolean("forceReset"))
      expectThat(response.getString("repoURL")).isEqualTo(RELAY_REPO_URL)
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("Emergency request returns repo url + the flag from the db if relayID is in DB 2")
  fun emergencyRequestFlagCorrectWhenKnown2(testContext: VertxTestContext) {
    val relayId = configurationAnotherCompany.getString("relayID")
    val response = Buffer.buffer(
      Given {
        spec(requestSpecification)
        contentType(ContentType.JSON)
        accept(ContentType.JSON)
      } When {
        queryParam("relayID", relayId)
        get("/relays-emergency")
      } Then {
        statusCode(200)
      } Extract {
        asString()
      }).toJsonObject()

    testContext.verify {
      expectThat(response).isNotNull()
      expectThat(response.getBoolean("forceReset")).isEqualTo(configurationAnotherCompany.getBoolean("forceReset"))
      expectThat(response.getString("repoURL")).isEqualTo(RELAY_REPO_URL)
      testContext.completeNow()
    }
  }

  @Test
  @DisplayName("A client receives the configuration when signaling that it is ready and the server increments the next relayID when receiving the ack")
  fun clientReceivesConfigurationWhenReadyAndServerIncrementsNextRelayIDAfterAck(
    vertx: Vertx,
    testContext: VertxTestContext
  ): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        mqttClient.connect(MQTT_PORT, MQTT_HOST).await()

        mqttClient.publishHandler { msg ->
          if (msg.topicName() == RELAYS_CONFIGURATION_TOPIC) {
            val message = msg.payload().toJsonObject()
            if (!message.containsKey("relayMessage") && !message.containsKey("configuration")) {
              // Skip the messages broadcast back by the server
              testContext.verify {
                val expected = jsonObjectOf(
                  "relayID" to "relay_1",
                  "mqttID" to "relay_1",
                  "mqttUsername" to "relayBiot_relay_1",
                  "mqttPassword" to "relayBiot_relay_1".sha3256Hash()
                )
                expectThat(message).isEqualTo(expected)
              }

              val clientWrittenConfigAckMessage = jsonObjectOf(
                "relayMessage" to "Written config",
                "content" to msg.payload().toString(),
                "path" to "/home/pi/biot/config/.config"
              )
              mqttClient.publish(
                RELAYS_CONFIGURATION_TOPIC,
                clientWrittenConfigAckMessage.toBuffer(),
                MqttQoS.AT_LEAST_ONCE,
                false,
                false
              ).onSuccess {
                vertx.setTimer(5_000) {
                  mongoClient.readNextRelayID().onSuccess { nextRelayID ->
                    testContext.verify {
                      expectThat(nextRelayID).isEqualTo(2)
                      testContext.completeNow()
                    }
                  }.onFailure(testContext::failNow)
                }
              }.onFailure(testContext::failNow)
            }
          }
        }.subscribe(RELAYS_CONFIGURATION_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()

        mqttClient.publish(
          RELAYS_CONFIGURATION_TOPIC,
          jsonObjectOf("configuration" to "ready").toBuffer(),
          MqttQoS.AT_LEAST_ONCE,
          false,
          false
        ).await()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }
    }

  companion object {

    private const val MQTT_HOST = "localhost"

    private val requestSpecification: RequestSpecification = RequestSpecBuilder()
      .addFilters(listOf(ResponseLoggingFilter(), RequestLoggingFilter()))
      .setBaseUri("http://localhost")
      .setPort(RelaysCommunicationVerticle.HTTP_PORT)
      .build()

    private val instance: KDockerComposeContainer by lazy { defineDockerCompose() }

    class KDockerComposeContainer(file: File) : DockerComposeContainer<KDockerComposeContainer>(file)

    private fun defineDockerCompose() =
      KDockerComposeContainer(File("../docker-compose.yml")).withExposedService("mongo_1", MONGO_PORT)

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
