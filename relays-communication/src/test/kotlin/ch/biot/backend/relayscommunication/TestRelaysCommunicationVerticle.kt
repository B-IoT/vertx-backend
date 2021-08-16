/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.relayscommunication

import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.INGESTION_TOPIC
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.KAFKA_PORT
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.MONGO_PORT
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.RELAYS_COLLECTION
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.RELAYS_UPDATE_ADDRESS
import ch.biot.backend.relayscommunication.RelaysCommunicationVerticle.Companion.UPDATE_PARAMETERS_TOPIC
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.CompositeFuture
import io.vertx.core.Vertx
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
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.coroutines.toChannel
import io.vertx.kotlin.ext.auth.mongo.mongoAuthenticationOptionsOf
import io.vertx.kotlin.ext.auth.mongo.mongoAuthorizationOptionsOf
import io.vertx.kotlin.ext.mongo.indexOptionsOf
import io.vertx.kotlin.mqtt.mqttClientOptionsOf
import io.vertx.kotlin.pgclient.pgConnectOptionsOf
import io.vertx.kotlin.sqlclient.poolOptionsOf
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions
import io.vertx.pgclient.PgPool
import io.vertx.pgclient.SslMode
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isLessThanOrEqualTo
import strikt.assertions.isNotNull
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate
import java.util.*
import java.util.concurrent.TimeUnit


internal val LOGGER = KotlinLogging.logger {}

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

  private var msgCounter = 0

  private val mqttPassword = "password"
  private val configuration = jsonObjectOf(
    "mqttID" to "mqtt",
    "mqttUsername" to "test",
    "relayID" to "relay",
    "ledStatus" to false,
    "latitude" to 0.1,
    "longitude" to 0.3,
    "wifi" to jsonObjectOf(
      "ssid" to "ssid",
      "password" to "pass"
    )
  )

  private val configurationAnotherCompany = jsonObjectOf(
    "mqttID" to "mqtt2",
    "mqttUsername" to "test2",
    "relayID" to "relay2",
    "ledStatus" to false,
    "latitude" to 2,
    "longitude" to 3,
    "wifi" to jsonObjectOf(
      "ssid" to "ssid2",
      "password" to "pass2"
    )
  )

  private val anotherCompanyName = "anotherCompany"
  private val anotherCompanyCollection = RELAYS_COLLECTION + "_$anotherCompanyName"

  private var itemBiot1Id: Int = -1
  private val itemBiot1 = jsonObjectOf(
    "beacon" to "e0:51:30:48:16:e5",
    "category" to "ECG",
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
    "category" to "ECG",
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
    "category" to "ECG",
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
    "category" to "ECG",
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
    "category" to "ECG",
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
    "category" to "ECG",
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
        username = configuration["mqttUsername"],
        password = mqttPassword,
        willFlag = true,
        willMessage = jsonObjectOf("company" to "biot").encode(),
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

    pgClient.query("""
      CREATE TABLE IF NOT EXISTS items_$anotherCompanyName
      (
          id SERIAL PRIMARY KEY,
          beacon VARCHAR(17) UNIQUE,
          category VARCHAR(100),
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
          lastModifiedBy VARCHAR(100)
      );
    """.trimIndent()).execute().await()

    insertItems()
  }

  private suspend fun insertItems() {
    val result = pgClient.preparedQuery(insertItem("items"))
      .execute(
        Tuple.of(
          itemBiot1["beacon"],
          itemBiot1["category"],
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
          itemBiot2["category"],
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
              itemBiotInvalidMac["category"],
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
              itemBiot4["category"],
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
              itemAnother1["category"],
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
              itemAnother2["category"],
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

  private suspend fun add1026Items() {
    for (i in 0 until 1026){
      pgClient.preparedQuery(insertItem("items"))
        .execute(
          Tuple.of(
            macs1026[i],
            itemBiot1["category"],
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
      pgClient.query("DELETE FROM items_$anotherCompanyName").execute()
    )
  }

  private suspend fun dropAllRelays() {
    mongoClient.removeDocuments(RELAYS_COLLECTION, jsonObjectOf()).await()
    mongoClient.removeDocuments(anotherCompanyCollection, jsonObjectOf()).await()
  }

  private suspend fun insertRelays(): JsonObject {
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)
    val hashedPassword = mongoAuth.hash("pbkdf2", String(Base64.getEncoder().encode(salt)), mqttPassword)
    val docID = mongoUserUtil.createHashedUser("test", hashedPassword).await()
    val query = jsonObjectOf("_id" to docID)
    val extraInfo = jsonObjectOf(
      "\$set" to configuration
    )
    mongoClient.findOneAndUpdate(RELAYS_COLLECTION, query, extraInfo).await()

    val salt2 = ByteArray(16)
    SecureRandom().nextBytes(salt2)
    val hashedPassword2 =
      mongoAuthAnotherCompany.hash("pbkdf2", String(Base64.getEncoder().encode(salt2)), mqttPassword)
    val docID2 = mongoUserUtilAnotherCompany.createHashedUser("test2", hashedPassword2).await()
    val query2 = jsonObjectOf("_id" to docID2)
    val extraInfo2 = jsonObjectOf(
      "\$set" to configurationAnotherCompany
    )
    return mongoClient.findOneAndUpdate(anotherCompanyCollection, query2, extraInfo2).await()
  }

  // TimescaleDB PostgreSQL queries for items
  private fun insertItem(itemsTable: String, customId: Boolean = false) =
    if (customId) "INSERT INTO $itemsTable (id, beacon, category, service, itemid, accessControlString, brand, model, supplier, purchasedate, purchaseprice, originlocation, currentlocation, room, contact, currentowner, previousowner, ordernumber, color, serialnumber, maintenancedate, status, comments, lastmodifieddate, lastmodifiedby) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24, $25) RETURNING id"
    else "INSERT INTO $itemsTable (beacon, category, service, itemid, accessControlString, brand, model, supplier, purchasedate, purchaseprice, originlocation, currentlocation, room, contact, currentowner, previousowner, ordernumber, color, serialnumber, maintenancedate, status, comments, lastmodifieddate, lastmodifiedby) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24) RETURNING id"

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
          mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
          mqttClient.publishHandler { msg ->
            if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
              testContext.verify {
                val expected = configuration.copy().apply {
                  remove("mqttID")
                  remove("mqttUsername")
                  remove("ledStatus")
                  put("whiteList", "e051304816e5f015b5dd2438f5a8ef56d7c0") //itemBiot1, itemBiot2, itemBiot4 mac addresses without :
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
  @DisplayName("A MQTT client without authentication is refused connection")
  fun clientWithoutAuthIsRefusedConnection(vertx: Vertx, testContext: VertxTestContext) =
    runBlocking(vertx.dispatcher()) {
      val client = MqttClient.create(vertx)

      try {
        client.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
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
          username = configuration["mqttUsername"],
          password = "wrongPassword",
          willFlag = true,
          willMessage = jsonObjectOf("company" to "biot").encode()
        )
      )

      try {
        client.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        testContext.failNow("The client was able to connect with a wrong password")
      } catch (error: Throwable) {
        testContext.completeNow()
      }
    }

  @Test
  @DisplayName("A MQTT client receives updates")
  fun clientReceivesUpdate(vertx: Vertx, testContext: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
    try {
      val message = jsonObjectOf("latitude" to 42.3, "mqttID" to "mqtt")
      mqttClient.publishHandler { msg ->
        if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
          val json = msg.payload().toJsonObject()
          if (!json.containsKey("relayID")) { // only handle received message, not the one for the last configuration
            testContext.verify {
              val messageWithoutMqttID = message.copy().apply { remove("mqttID") }
              expectThat(json).isEqualTo(messageWithoutMqttID)
              testContext.completeNow()
            }
          }
        }
      }.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()

      mqttClient.subscribe(UPDATE_PARAMETERS_TOPIC, MqttQoS.AT_LEAST_ONCE.value()).await()
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
      mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
      mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
      kafkaConsumer.subscribe(INGESTION_TOPIC).await()
      val stream = kafkaConsumer.asStream().toChannel(vertx)
      for (record in stream) {
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
          stream.cancel()
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
        mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toChannel(vertx)
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
        mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toChannel(vertx)
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
        mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toChannel(vertx)
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
        mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toChannel(vertx)
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
        mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toChannel(vertx)
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
        mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toChannel(vertx)
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
        mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        mqttClient.publish(INGESTION_TOPIC, message.toBuffer(), MqttQoS.AT_LEAST_ONCE, false, false).await()
        kafkaConsumer.subscribe(INGESTION_TOPIC).await()
        val stream = kafkaConsumer.asStream().toChannel(vertx)
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
  @DisplayName("A MQTT client for another company than biot gets the right last config at subscription")
  fun clientFromAnotherCompanyGetsRightConfig(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      val client = MqttClient.create(
        vertx,
        mqttClientOptionsOf(
          clientId = configurationAnotherCompany["mqttID"],
          username = configurationAnotherCompany["mqttUsername"],
          password = mqttPassword,
          willFlag = true,
          willMessage = jsonObjectOf("company" to anotherCompanyName).encode()
        )
      )

      try {
        client.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        client.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            testContext.verify {
              val expected = configurationAnotherCompany.copy().apply {
                remove("mqttID")
                remove("mqttUsername")
                remove("ledStatus")
                put("whiteList", "122334aeb5d201a2d4fe5621") // itemAnother1 and itemAnother2 mac addresses without :
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
  @DisplayName("A MQTT client receives the config after 60 seconds if one item's beacon changed")
  fun clientSubscribesAndReceivesLastConfigAfter60SecWhenModified(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
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
                          put("whiteList", "e051304816e5f015b5dd2438f5a8ef56d7c0") //itemBiot1, itemBiot2, itemBiot4 mac addresses without :
                        }
                        expectThat(msg.payload().toJsonObject()).isEqualTo(expected)
                  }
                  msgCounter += 1
                  pgClient.preparedQuery(updateItem("items", listOf("beacon"), "biot")).execute(Tuple.tuple(listOf(itemBiot1Id, "aa:bb:cc:dd:ee:ff")))

                }
                1 -> {
                  testContext.verify {
                        val expected = configuration.copy().apply {
                          remove("mqttID")
                          remove("mqttUsername")
                          remove("ledStatus")
                          put("whiteList", "aabbccddeefff015b5dd2438f5a8ef56d7c0") //itemBiot1, itemBiot2, itemBiot4 mac addresses without :
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

      vertx.setTimer(25_000){
        testContext.completeNow()
      }
    }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  @DisplayName("A MQTT client does NOT receive the config after 70 seconds if no item's beacon changed")
  fun clientSubscribesAndDoesNotReceiveLastConfigAfter70SecWhenUnmodified(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      try {
        mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
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
                    put("whiteList", "e051304816e5f015b5dd2438f5a8ef56d7c0") //itemBiot1, itemBiot2, itemBiot4 mac addresses without :
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
      vertx.setTimer(25_000){
        testContext.completeNow()
      }
    }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  @DisplayName("A MQTT client never receives more than 1024 mac addresses in the whitelist")
  fun clientSubscribesAndNeverReceivesMoreThan1024Macs(vertx: Vertx, testContext: VertxTestContext): Unit =
    runBlocking(vertx.dispatcher()) {
      add1026Items()
      try {
        mqttClient.connect(RelaysCommunicationVerticle.MQTT_PORT, "localhost").await()
        mqttClient.publishHandler { msg ->
          if (msg.topicName() == UPDATE_PARAMETERS_TOPIC) {
            when (msgCounter) {
              0 -> {
                // First msg at subscription
                testContext.verify {
                  expectThat(msg.payload().toJsonObject().getString("whiteList").length).isLessThanOrEqualTo(1024 * 6 * 2)
                  testContext.completeNow()
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
    }


  companion object {

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

  private val macs1026 = listOf("aa:aa:bb:aa:ff:11",
    "aa:aa:aa:aa:ff:11",
    "aa:aa:aa:aa:ff:12",
    "aa:aa:aa:aa:ff:13",
    "aa:aa:aa:aa:ff:14",
    "aa:aa:aa:aa:ff:15",
    "aa:aa:aa:aa:ff:16",
    "aa:aa:aa:aa:ff:17",
    "aa:aa:aa:aa:ff:18",
    "aa:aa:aa:aa:ff:19",
    "aa:aa:aa:aa:ff:20",
    "aa:aa:aa:aa:ff:21",
    "aa:aa:aa:aa:ff:22",
    "aa:aa:aa:aa:ff:23",
    "aa:aa:aa:aa:ff:24",
    "aa:aa:aa:aa:ff:25",
    "aa:aa:aa:aa:ff:26",
    "aa:aa:aa:aa:ff:27",
    "aa:aa:aa:aa:ff:28",
    "aa:aa:aa:aa:ff:29",
    "aa:aa:aa:aa:ff:30",
    "aa:aa:aa:aa:ff:31",
    "aa:aa:aa:aa:ff:32",
    "aa:aa:aa:aa:ff:33",
    "aa:aa:aa:aa:ff:34",
    "aa:aa:aa:aa:ff:35",
    "aa:aa:aa:aa:ff:36",
    "aa:aa:aa:aa:ff:37",
    "aa:aa:aa:aa:ff:38",
    "aa:aa:aa:aa:ff:39",
    "aa:aa:aa:aa:ff:40",
    "aa:aa:aa:aa:ff:41",
    "aa:aa:aa:aa:ff:42",
    "aa:aa:aa:aa:ff:43",
    "aa:aa:aa:aa:ff:44",
    "aa:aa:aa:aa:ff:45",
    "aa:aa:aa:aa:ff:46",
    "aa:aa:aa:aa:ff:47",
    "aa:aa:aa:aa:ff:48",
    "aa:aa:aa:aa:ff:49",
    "aa:aa:aa:aa:ff:50",
    "aa:aa:aa:aa:ff:51",
    "aa:aa:aa:aa:ff:52",
    "aa:aa:aa:aa:ff:53",
    "aa:aa:aa:aa:ff:54",
    "aa:aa:aa:aa:ff:55",
    "aa:aa:aa:aa:ff:56",
    "aa:aa:aa:aa:ff:57",
    "aa:aa:aa:aa:ff:58",
    "aa:aa:aa:aa:ff:59",
    "aa:aa:aa:aa:ff:60",
    "aa:aa:aa:aa:ff:61",
    "aa:aa:aa:aa:ff:62",
    "aa:aa:aa:aa:ff:63",
    "aa:aa:aa:aa:ff:64",
    "aa:aa:aa:aa:ff:65",
    "aa:aa:aa:aa:ff:66",
    "aa:aa:aa:aa:ff:67",
    "aa:aa:aa:aa:ff:68",
    "aa:aa:aa:aa:ff:69",
    "aa:aa:aa:aa:ff:70",
    "aa:aa:aa:aa:ff:71",
    "aa:aa:aa:aa:ff:72",
    "aa:aa:aa:aa:ff:73",
    "aa:aa:aa:aa:ff:74",
    "aa:aa:aa:aa:ff:75",
    "aa:aa:aa:aa:ff:76",
    "aa:aa:aa:aa:ff:77",
    "aa:aa:aa:aa:ff:78",
    "aa:aa:aa:aa:ff:79",
    "aa:aa:aa:aa:ff:80",
    "aa:aa:aa:aa:ff:81",
    "aa:aa:aa:aa:ff:82",
    "aa:aa:aa:aa:ff:83",
    "aa:aa:aa:aa:ff:84",
    "aa:aa:aa:aa:ff:85",
    "aa:aa:aa:aa:ff:86",
    "aa:aa:aa:aa:ff:87",
    "aa:aa:aa:aa:ff:88",
    "aa:aa:aa:aa:ff:89",
    "aa:aa:aa:aa:ff:90",
    "aa:aa:aa:aa:ff:91",
    "aa:aa:aa:aa:ff:92",
    "aa:aa:aa:aa:ff:93",
    "aa:aa:aa:aa:ff:94",
    "aa:aa:aa:aa:ff:95",
    "aa:aa:aa:aa:ff:96",
    "aa:aa:aa:aa:ff:97",
    "aa:aa:aa:aa:ff:98",
    "aa:aa:aa:aa:ff:99",
    "aa:aa:aa:aa:ff:aa",
    "11:aa:aa:aa:ff:11",
    "11:aa:aa:aa:ff:12",
    "11:aa:aa:aa:ff:13",
    "11:aa:aa:aa:ff:14",
    "11:aa:aa:aa:ff:15",
    "11:aa:aa:aa:ff:16",
    "11:aa:aa:aa:ff:17",
    "11:aa:aa:aa:ff:18",
    "11:aa:aa:aa:ff:19",
    "11:aa:aa:aa:ff:20",
    "11:aa:aa:aa:ff:21",
    "11:aa:aa:aa:ff:22",
    "11:aa:aa:aa:ff:23",
    "11:aa:aa:aa:ff:24",
    "11:aa:aa:aa:ff:25",
    "11:aa:aa:aa:ff:26",
    "11:aa:aa:aa:ff:27",
    "11:aa:aa:aa:ff:28",
    "11:aa:aa:aa:ff:29",
    "11:aa:aa:aa:ff:30",
    "11:aa:aa:aa:ff:31",
    "11:aa:aa:aa:ff:32",
    "11:aa:aa:aa:ff:33",
    "11:aa:aa:aa:ff:34",
    "11:aa:aa:aa:ff:35",
    "11:aa:aa:aa:ff:36",
    "11:aa:aa:aa:ff:37",
    "11:aa:aa:aa:ff:38",
    "11:aa:aa:aa:ff:39",
    "11:aa:aa:aa:ff:40",
    "11:aa:aa:aa:ff:41",
    "11:aa:aa:aa:ff:42",
    "11:aa:aa:aa:ff:43",
    "11:aa:aa:aa:ff:44",
    "11:aa:aa:aa:ff:45",
    "11:aa:aa:aa:ff:46",
    "11:aa:aa:aa:ff:47",
    "11:aa:aa:aa:ff:48",
    "11:aa:aa:aa:ff:49",
    "11:aa:aa:aa:ff:50",
    "11:aa:aa:aa:ff:51",
    "11:aa:aa:aa:ff:52",
    "11:aa:aa:aa:ff:53",
    "11:aa:aa:aa:ff:54",
    "11:aa:aa:aa:ff:55",
    "11:aa:aa:aa:ff:56",
    "11:aa:aa:aa:ff:57",
    "11:aa:aa:aa:ff:58",
    "11:aa:aa:aa:ff:59",
    "11:aa:aa:aa:ff:60",
    "11:aa:aa:aa:ff:61",
    "11:aa:aa:aa:ff:62",
    "11:aa:aa:aa:ff:63",
    "11:aa:aa:aa:ff:64",
    "11:aa:aa:aa:ff:65",
    "11:aa:aa:aa:ff:66",
    "11:aa:aa:aa:ff:67",
    "11:aa:aa:aa:ff:68",
    "11:aa:aa:aa:ff:69",
    "11:aa:aa:aa:ff:70",
    "11:aa:aa:aa:ff:71",
    "11:aa:aa:aa:ff:72",
    "11:aa:aa:aa:ff:73",
    "11:aa:aa:aa:ff:74",
    "11:aa:aa:aa:ff:75",
    "11:aa:aa:aa:ff:76",
    "11:aa:aa:aa:ff:77",
    "11:aa:aa:aa:ff:78",
    "11:aa:aa:aa:ff:79",
    "11:aa:aa:aa:ff:80",
    "11:aa:aa:aa:ff:81",
    "11:aa:aa:aa:ff:82",
    "11:aa:aa:aa:ff:83",
    "11:aa:aa:aa:ff:84",
    "11:aa:aa:aa:ff:85",
    "11:aa:aa:aa:ff:86",
    "11:aa:aa:aa:ff:87",
    "11:aa:aa:aa:ff:88",
    "11:aa:aa:aa:ff:89",
    "11:aa:aa:aa:ff:90",
    "11:aa:aa:aa:ff:91",
    "11:aa:aa:aa:ff:92",
    "11:aa:aa:aa:ff:93",
    "11:aa:aa:aa:ff:94",
    "11:aa:aa:aa:ff:95",
    "11:aa:aa:aa:ff:96",
    "11:aa:aa:aa:ff:97",
    "11:aa:aa:aa:ff:98",
    "11:aa:aa:aa:ff:99",
    "12:aa:aa:aa:ff:11",
    "12:aa:aa:aa:ff:12",
    "12:aa:aa:aa:ff:13",
    "12:aa:aa:aa:ff:14",
    "12:aa:aa:aa:ff:15",
    "12:aa:aa:aa:ff:16",
    "12:aa:aa:aa:ff:17",
    "12:aa:aa:aa:ff:18",
    "12:aa:aa:aa:ff:19",
    "12:aa:aa:aa:ff:20",
    "12:aa:aa:aa:ff:21",
    "12:aa:aa:aa:ff:22",
    "12:aa:aa:aa:ff:23",
    "12:aa:aa:aa:ff:24",
    "12:aa:aa:aa:ff:25",
    "12:aa:aa:aa:ff:26",
    "12:aa:aa:aa:ff:27",
    "12:aa:aa:aa:ff:28",
    "12:aa:aa:aa:ff:29",
    "12:aa:aa:aa:ff:30",
    "12:aa:aa:aa:ff:31",
    "12:aa:aa:aa:ff:32",
    "12:aa:aa:aa:ff:33",
    "12:aa:aa:aa:ff:34",
    "12:aa:aa:aa:ff:35",
    "12:aa:aa:aa:ff:36",
    "12:aa:aa:aa:ff:37",
    "12:aa:aa:aa:ff:38",
    "12:aa:aa:aa:ff:39",
    "12:aa:aa:aa:ff:40",
    "12:aa:aa:aa:ff:41",
    "12:aa:aa:aa:ff:42",
    "12:aa:aa:aa:ff:43",
    "12:aa:aa:aa:ff:44",
    "12:aa:aa:aa:ff:45",
    "12:aa:aa:aa:ff:46",
    "12:aa:aa:aa:ff:47",
    "12:aa:aa:aa:ff:48",
    "12:aa:aa:aa:ff:49",
    "12:aa:aa:aa:ff:50",
    "12:aa:aa:aa:ff:51",
    "12:aa:aa:aa:ff:52",
    "12:aa:aa:aa:ff:53",
    "12:aa:aa:aa:ff:54",
    "12:aa:aa:aa:ff:55",
    "12:aa:aa:aa:ff:56",
    "12:aa:aa:aa:ff:57",
    "12:aa:aa:aa:ff:58",
    "12:aa:aa:aa:ff:59",
    "12:aa:aa:aa:ff:60",
    "12:aa:aa:aa:ff:61",
    "12:aa:aa:aa:ff:62",
    "12:aa:aa:aa:ff:63",
    "12:aa:aa:aa:ff:64",
    "12:aa:aa:aa:ff:65",
    "12:aa:aa:aa:ff:66",
    "12:aa:aa:aa:ff:67",
    "12:aa:aa:aa:ff:68",
    "12:aa:aa:aa:ff:69",
    "12:aa:aa:aa:ff:70",
    "12:aa:aa:aa:ff:71",
    "12:aa:aa:aa:ff:72",
    "12:aa:aa:aa:ff:73",
    "12:aa:aa:aa:ff:74",
    "12:aa:aa:aa:ff:75",
    "12:aa:aa:aa:ff:76",
    "12:aa:aa:aa:ff:77",
    "12:aa:aa:aa:ff:78",
    "12:aa:aa:aa:ff:79",
    "12:aa:aa:aa:ff:80",
    "12:aa:aa:aa:ff:81",
    "12:aa:aa:aa:ff:82",
    "12:aa:aa:aa:ff:83",
    "12:aa:aa:aa:ff:84",
    "12:aa:aa:aa:ff:85",
    "12:aa:aa:aa:ff:86",
    "12:aa:aa:aa:ff:87",
    "12:aa:aa:aa:ff:88",
    "12:aa:aa:aa:ff:89",
    "12:aa:aa:aa:ff:90",
    "12:aa:aa:aa:ff:91",
    "12:aa:aa:aa:ff:92",
    "12:aa:aa:aa:ff:93",
    "12:aa:aa:aa:ff:94",
    "12:aa:aa:aa:ff:95",
    "12:aa:aa:aa:ff:96",
    "12:aa:aa:aa:ff:97",
    "12:aa:aa:aa:ff:98",
    "12:aa:aa:aa:ff:99",
    "13:aa:aa:aa:ff:11",
    "13:aa:aa:aa:ff:12",
    "13:aa:aa:aa:ff:13",
    "13:aa:aa:aa:ff:14",
    "13:aa:aa:aa:ff:15",
    "13:aa:aa:aa:ff:16",
    "13:aa:aa:aa:ff:17",
    "13:aa:aa:aa:ff:18",
    "13:aa:aa:aa:ff:19",
    "13:aa:aa:aa:ff:20",
    "13:aa:aa:aa:ff:21",
    "13:aa:aa:aa:ff:22",
    "13:aa:aa:aa:ff:23",
    "13:aa:aa:aa:ff:24",
    "13:aa:aa:aa:ff:25",
    "13:aa:aa:aa:ff:26",
    "13:aa:aa:aa:ff:27",
    "13:aa:aa:aa:ff:28",
    "13:aa:aa:aa:ff:29",
    "13:aa:aa:aa:ff:30",
    "13:aa:aa:aa:ff:31",
    "13:aa:aa:aa:ff:32",
    "13:aa:aa:aa:ff:33",
    "13:aa:aa:aa:ff:34",
    "13:aa:aa:aa:ff:35",
    "13:aa:aa:aa:ff:36",
    "13:aa:aa:aa:ff:37",
    "13:aa:aa:aa:ff:38",
    "13:aa:aa:aa:ff:39",
    "13:aa:aa:aa:ff:40",
    "13:aa:aa:aa:ff:41",
    "13:aa:aa:aa:ff:42",
    "13:aa:aa:aa:ff:43",
    "13:aa:aa:aa:ff:44",
    "13:aa:aa:aa:ff:45",
    "13:aa:aa:aa:ff:46",
    "13:aa:aa:aa:ff:47",
    "13:aa:aa:aa:ff:48",
    "13:aa:aa:aa:ff:49",
    "13:aa:aa:aa:ff:50",
    "13:aa:aa:aa:ff:51",
    "13:aa:aa:aa:ff:52",
    "13:aa:aa:aa:ff:53",
    "13:aa:aa:aa:ff:54",
    "13:aa:aa:aa:ff:55",
    "13:aa:aa:aa:ff:56",
    "13:aa:aa:aa:ff:57",
    "13:aa:aa:aa:ff:58",
    "13:aa:aa:aa:ff:59",
    "13:aa:aa:aa:ff:60",
    "13:aa:aa:aa:ff:61",
    "13:aa:aa:aa:ff:62",
    "13:aa:aa:aa:ff:63",
    "13:aa:aa:aa:ff:64",
    "13:aa:aa:aa:ff:65",
    "13:aa:aa:aa:ff:66",
    "13:aa:aa:aa:ff:67",
    "13:aa:aa:aa:ff:68",
    "13:aa:aa:aa:ff:69",
    "13:aa:aa:aa:ff:70",
    "13:aa:aa:aa:ff:71",
    "13:aa:aa:aa:ff:72",
    "13:aa:aa:aa:ff:73",
    "13:aa:aa:aa:ff:74",
    "13:aa:aa:aa:ff:75",
    "13:aa:aa:aa:ff:76",
    "13:aa:aa:aa:ff:77",
    "13:aa:aa:aa:ff:78",
    "13:aa:aa:aa:ff:79",
    "13:aa:aa:aa:ff:80",
    "13:aa:aa:aa:ff:81",
    "13:aa:aa:aa:ff:82",
    "13:aa:aa:aa:ff:83",
    "13:aa:aa:aa:ff:84",
    "13:aa:aa:aa:ff:85",
    "13:aa:aa:aa:ff:86",
    "13:aa:aa:aa:ff:87",
    "13:aa:aa:aa:ff:88",
    "13:aa:aa:aa:ff:89",
    "13:aa:aa:aa:ff:90",
    "13:aa:aa:aa:ff:91",
    "13:aa:aa:aa:ff:92",
    "13:aa:aa:aa:ff:93",
    "13:aa:aa:aa:ff:94",
    "13:aa:aa:aa:ff:95",
    "13:aa:aa:aa:ff:96",
    "13:aa:aa:aa:ff:97",
    "13:aa:aa:aa:ff:98",
    "13:aa:aa:aa:ff:99",
    "14:aa:aa:aa:ff:11",
    "14:aa:aa:aa:ff:12",
    "14:aa:aa:aa:ff:13",
    "14:aa:aa:aa:ff:14",
    "14:aa:aa:aa:ff:15",
    "14:aa:aa:aa:ff:16",
    "14:aa:aa:aa:ff:17",
    "14:aa:aa:aa:ff:18",
    "14:aa:aa:aa:ff:19",
    "14:aa:aa:aa:ff:20",
    "14:aa:aa:aa:ff:21",
    "14:aa:aa:aa:ff:22",
    "14:aa:aa:aa:ff:23",
    "14:aa:aa:aa:ff:24",
    "14:aa:aa:aa:ff:25",
    "14:aa:aa:aa:ff:26",
    "14:aa:aa:aa:ff:27",
    "14:aa:aa:aa:ff:28",
    "14:aa:aa:aa:ff:29",
    "14:aa:aa:aa:ff:30",
    "14:aa:aa:aa:ff:31",
    "14:aa:aa:aa:ff:32",
    "14:aa:aa:aa:ff:33",
    "14:aa:aa:aa:ff:34",
    "14:aa:aa:aa:ff:35",
    "14:aa:aa:aa:ff:36",
    "14:aa:aa:aa:ff:37",
    "14:aa:aa:aa:ff:38",
    "14:aa:aa:aa:ff:39",
    "14:aa:aa:aa:ff:40",
    "14:aa:aa:aa:ff:41",
    "14:aa:aa:aa:ff:42",
    "14:aa:aa:aa:ff:43",
    "14:aa:aa:aa:ff:44",
    "14:aa:aa:aa:ff:45",
    "14:aa:aa:aa:ff:46",
    "14:aa:aa:aa:ff:47",
    "14:aa:aa:aa:ff:48",
    "14:aa:aa:aa:ff:49",
    "14:aa:aa:aa:ff:50",
    "14:aa:aa:aa:ff:51",
    "14:aa:aa:aa:ff:52",
    "14:aa:aa:aa:ff:53",
    "14:aa:aa:aa:ff:54",
    "14:aa:aa:aa:ff:55",
    "14:aa:aa:aa:ff:56",
    "14:aa:aa:aa:ff:57",
    "14:aa:aa:aa:ff:58",
    "14:aa:aa:aa:ff:59",
    "14:aa:aa:aa:ff:60",
    "14:aa:aa:aa:ff:61",
    "14:aa:aa:aa:ff:62",
    "14:aa:aa:aa:ff:63",
    "14:aa:aa:aa:ff:64",
    "14:aa:aa:aa:ff:65",
    "14:aa:aa:aa:ff:66",
    "14:aa:aa:aa:ff:67",
    "14:aa:aa:aa:ff:68",
    "14:aa:aa:aa:ff:69",
    "14:aa:aa:aa:ff:70",
    "14:aa:aa:aa:ff:71",
    "14:aa:aa:aa:ff:72",
    "14:aa:aa:aa:ff:73",
    "14:aa:aa:aa:ff:74",
    "14:aa:aa:aa:ff:75",
    "14:aa:aa:aa:ff:76",
    "14:aa:aa:aa:ff:77",
    "14:aa:aa:aa:ff:78",
    "14:aa:aa:aa:ff:79",
    "14:aa:aa:aa:ff:80",
    "14:aa:aa:aa:ff:81",
    "14:aa:aa:aa:ff:82",
    "14:aa:aa:aa:ff:83",
    "14:aa:aa:aa:ff:84",
    "14:aa:aa:aa:ff:85",
    "14:aa:aa:aa:ff:86",
    "14:aa:aa:aa:ff:87",
    "14:aa:aa:aa:ff:88",
    "14:aa:aa:aa:ff:89",
    "14:aa:aa:aa:ff:90",
    "14:aa:aa:aa:ff:91",
    "14:aa:aa:aa:ff:92",
    "14:aa:aa:aa:ff:93",
    "14:aa:aa:aa:ff:94",
    "14:aa:aa:aa:ff:95",
    "14:aa:aa:aa:ff:96",
    "14:aa:aa:aa:ff:97",
    "14:aa:aa:aa:ff:98",
    "14:aa:aa:aa:ff:99",
    "15:aa:aa:aa:ff:11",
    "15:aa:aa:aa:ff:12",
    "15:aa:aa:aa:ff:13",
    "15:aa:aa:aa:ff:14",
    "15:aa:aa:aa:ff:15",
    "15:aa:aa:aa:ff:16",
    "15:aa:aa:aa:ff:17",
    "15:aa:aa:aa:ff:18",
    "15:aa:aa:aa:ff:19",
    "15:aa:aa:aa:ff:20",
    "15:aa:aa:aa:ff:21",
    "15:aa:aa:aa:ff:22",
    "15:aa:aa:aa:ff:23",
    "15:aa:aa:aa:ff:24",
    "15:aa:aa:aa:ff:25",
    "15:aa:aa:aa:ff:26",
    "15:aa:aa:aa:ff:27",
    "15:aa:aa:aa:ff:28",
    "15:aa:aa:aa:ff:29",
    "15:aa:aa:aa:ff:30",
    "15:aa:aa:aa:ff:31",
    "15:aa:aa:aa:ff:32",
    "15:aa:aa:aa:ff:33",
    "15:aa:aa:aa:ff:34",
    "15:aa:aa:aa:ff:35",
    "15:aa:aa:aa:ff:36",
    "15:aa:aa:aa:ff:37",
    "15:aa:aa:aa:ff:38",
    "15:aa:aa:aa:ff:39",
    "15:aa:aa:aa:ff:40",
    "15:aa:aa:aa:ff:41",
    "15:aa:aa:aa:ff:42",
    "15:aa:aa:aa:ff:43",
    "15:aa:aa:aa:ff:44",
    "15:aa:aa:aa:ff:45",
    "15:aa:aa:aa:ff:46",
    "15:aa:aa:aa:ff:47",
    "15:aa:aa:aa:ff:48",
    "15:aa:aa:aa:ff:49",
    "15:aa:aa:aa:ff:50",
    "15:aa:aa:aa:ff:51",
    "15:aa:aa:aa:ff:52",
    "15:aa:aa:aa:ff:53",
    "15:aa:aa:aa:ff:54",
    "15:aa:aa:aa:ff:55",
    "15:aa:aa:aa:ff:56",
    "15:aa:aa:aa:ff:57",
    "15:aa:aa:aa:ff:58",
    "15:aa:aa:aa:ff:59",
    "15:aa:aa:aa:ff:60",
    "15:aa:aa:aa:ff:61",
    "15:aa:aa:aa:ff:62",
    "15:aa:aa:aa:ff:63",
    "15:aa:aa:aa:ff:64",
    "15:aa:aa:aa:ff:65",
    "15:aa:aa:aa:ff:66",
    "15:aa:aa:aa:ff:67",
    "15:aa:aa:aa:ff:68",
    "15:aa:aa:aa:ff:69",
    "15:aa:aa:aa:ff:70",
    "15:aa:aa:aa:ff:71",
    "15:aa:aa:aa:ff:72",
    "15:aa:aa:aa:ff:73",
    "15:aa:aa:aa:ff:74",
    "15:aa:aa:aa:ff:75",
    "15:aa:aa:aa:ff:76",
    "15:aa:aa:aa:ff:77",
    "15:aa:aa:aa:ff:78",
    "15:aa:aa:aa:ff:79",
    "15:aa:aa:aa:ff:80",
    "15:aa:aa:aa:ff:81",
    "15:aa:aa:aa:ff:82",
    "15:aa:aa:aa:ff:83",
    "15:aa:aa:aa:ff:84",
    "15:aa:aa:aa:ff:85",
    "15:aa:aa:aa:ff:86",
    "15:aa:aa:aa:ff:87",
    "15:aa:aa:aa:ff:88",
    "15:aa:aa:aa:ff:89",
    "15:aa:aa:aa:ff:90",
    "15:aa:aa:aa:ff:91",
    "15:aa:aa:aa:ff:92",
    "15:aa:aa:aa:ff:93",
    "15:aa:aa:aa:ff:94",
    "15:aa:aa:aa:ff:95",
    "15:aa:aa:aa:ff:96",
    "15:aa:aa:aa:ff:97",
    "15:aa:aa:aa:ff:98",
    "15:aa:aa:aa:ff:99",
    "17:aa:aa:aa:ff:11",
    "17:aa:aa:aa:ff:12",
    "17:aa:aa:aa:ff:13",
    "17:aa:aa:aa:ff:14",
    "17:aa:aa:aa:ff:15",
    "17:aa:aa:aa:ff:16",
    "17:aa:aa:aa:ff:17",
    "17:aa:aa:aa:ff:18",
    "17:aa:aa:aa:ff:19",
    "17:aa:aa:aa:ff:20",
    "17:aa:aa:aa:ff:21",
    "17:aa:aa:aa:ff:22",
    "17:aa:aa:aa:ff:23",
    "17:aa:aa:aa:ff:24",
    "17:aa:aa:aa:ff:25",
    "17:aa:aa:aa:ff:26",
    "17:aa:aa:aa:ff:27",
    "17:aa:aa:aa:ff:28",
    "17:aa:aa:aa:ff:29",
    "17:aa:aa:aa:ff:30",
    "17:aa:aa:aa:ff:31",
    "17:aa:aa:aa:ff:32",
    "17:aa:aa:aa:ff:33",
    "17:aa:aa:aa:ff:34",
    "17:aa:aa:aa:ff:35",
    "17:aa:aa:aa:ff:36",
    "17:aa:aa:aa:ff:37",
    "17:aa:aa:aa:ff:38",
    "17:aa:aa:aa:ff:39",
    "17:aa:aa:aa:ff:40",
    "17:aa:aa:aa:ff:41",
    "17:aa:aa:aa:ff:42",
    "17:aa:aa:aa:ff:43",
    "17:aa:aa:aa:ff:44",
    "17:aa:aa:aa:ff:45",
    "17:aa:aa:aa:ff:46",
    "17:aa:aa:aa:ff:47",
    "17:aa:aa:aa:ff:48",
    "17:aa:aa:aa:ff:49",
    "17:aa:aa:aa:ff:50",
    "17:aa:aa:aa:ff:51",
    "17:aa:aa:aa:ff:52",
    "17:aa:aa:aa:ff:53",
    "17:aa:aa:aa:ff:54",
    "17:aa:aa:aa:ff:55",
    "17:aa:aa:aa:ff:56",
    "17:aa:aa:aa:ff:57",
    "17:aa:aa:aa:ff:58",
    "17:aa:aa:aa:ff:59",
    "17:aa:aa:aa:ff:60",
    "17:aa:aa:aa:ff:61",
    "17:aa:aa:aa:ff:62",
    "17:aa:aa:aa:ff:63",
    "17:aa:aa:aa:ff:64",
    "17:aa:aa:aa:ff:65",
    "17:aa:aa:aa:ff:66",
    "17:aa:aa:aa:ff:67",
    "17:aa:aa:aa:ff:68",
    "17:aa:aa:aa:ff:69",
    "17:aa:aa:aa:ff:70",
    "17:aa:aa:aa:ff:71",
    "17:aa:aa:aa:ff:72",
    "17:aa:aa:aa:ff:73",
    "17:aa:aa:aa:ff:74",
    "17:aa:aa:aa:ff:75",
    "17:aa:aa:aa:ff:76",
    "17:aa:aa:aa:ff:77",
    "17:aa:aa:aa:ff:78",
    "17:aa:aa:aa:ff:79",
    "17:aa:aa:aa:ff:80",
    "17:aa:aa:aa:ff:81",
    "17:aa:aa:aa:ff:82",
    "17:aa:aa:aa:ff:83",
    "17:aa:aa:aa:ff:84",
    "17:aa:aa:aa:ff:85",
    "17:aa:aa:aa:ff:86",
    "17:aa:aa:aa:ff:87",
    "17:aa:aa:aa:ff:88",
    "17:aa:aa:aa:ff:89",
    "17:aa:aa:aa:ff:90",
    "17:aa:aa:aa:ff:91",
    "17:aa:aa:aa:ff:92",
    "17:aa:aa:aa:ff:93",
    "17:aa:aa:aa:ff:94",
    "17:aa:aa:aa:ff:95",
    "17:aa:aa:aa:ff:96",
    "17:aa:aa:aa:ff:97",
    "17:aa:aa:aa:ff:98",
    "17:aa:aa:aa:ff:99",
    "18:aa:aa:aa:ff:11",
    "18:aa:aa:aa:ff:12",
    "18:aa:aa:aa:ff:13",
    "18:aa:aa:aa:ff:14",
    "18:aa:aa:aa:ff:15",
    "18:aa:aa:aa:ff:16",
    "18:aa:aa:aa:ff:17",
    "18:aa:aa:aa:ff:18",
    "18:aa:aa:aa:ff:19",
    "18:aa:aa:aa:ff:20",
    "18:aa:aa:aa:ff:21",
    "18:aa:aa:aa:ff:22",
    "18:aa:aa:aa:ff:23",
    "18:aa:aa:aa:ff:24",
    "18:aa:aa:aa:ff:25",
    "18:aa:aa:aa:ff:26",
    "18:aa:aa:aa:ff:27",
    "18:aa:aa:aa:ff:28",
    "18:aa:aa:aa:ff:29",
    "18:aa:aa:aa:ff:30",
    "18:aa:aa:aa:ff:31",
    "18:aa:aa:aa:ff:32",
    "18:aa:aa:aa:ff:33",
    "18:aa:aa:aa:ff:34",
    "18:aa:aa:aa:ff:35",
    "18:aa:aa:aa:ff:36",
    "18:aa:aa:aa:ff:37",
    "18:aa:aa:aa:ff:38",
    "18:aa:aa:aa:ff:39",
    "18:aa:aa:aa:ff:40",
    "18:aa:aa:aa:ff:41",
    "18:aa:aa:aa:ff:42",
    "18:aa:aa:aa:ff:43",
    "18:aa:aa:aa:ff:44",
    "18:aa:aa:aa:ff:45",
    "18:aa:aa:aa:ff:46",
    "18:aa:aa:aa:ff:47",
    "18:aa:aa:aa:ff:48",
    "18:aa:aa:aa:ff:49",
    "18:aa:aa:aa:ff:50",
    "18:aa:aa:aa:ff:51",
    "18:aa:aa:aa:ff:52",
    "18:aa:aa:aa:ff:53",
    "18:aa:aa:aa:ff:54",
    "18:aa:aa:aa:ff:55",
    "18:aa:aa:aa:ff:56",
    "18:aa:aa:aa:ff:57",
    "18:aa:aa:aa:ff:58",
    "18:aa:aa:aa:ff:59",
    "18:aa:aa:aa:ff:60",
    "18:aa:aa:aa:ff:61",
    "18:aa:aa:aa:ff:62",
    "18:aa:aa:aa:ff:63",
    "18:aa:aa:aa:ff:64",
    "18:aa:aa:aa:ff:65",
    "18:aa:aa:aa:ff:66",
    "18:aa:aa:aa:ff:67",
    "18:aa:aa:aa:ff:68",
    "18:aa:aa:aa:ff:69",
    "18:aa:aa:aa:ff:70",
    "18:aa:aa:aa:ff:71",
    "18:aa:aa:aa:ff:72",
    "18:aa:aa:aa:ff:73",
    "18:aa:aa:aa:ff:74",
    "18:aa:aa:aa:ff:75",
    "18:aa:aa:aa:ff:76",
    "18:aa:aa:aa:ff:77",
    "18:aa:aa:aa:ff:78",
    "18:aa:aa:aa:ff:79",
    "18:aa:aa:aa:ff:80",
    "18:aa:aa:aa:ff:81",
    "18:aa:aa:aa:ff:82",
    "18:aa:aa:aa:ff:83",
    "18:aa:aa:aa:ff:84",
    "18:aa:aa:aa:ff:85",
    "18:aa:aa:aa:ff:86",
    "18:aa:aa:aa:ff:87",
    "18:aa:aa:aa:ff:88",
    "18:aa:aa:aa:ff:89",
    "18:aa:aa:aa:ff:90",
    "18:aa:aa:aa:ff:91",
    "18:aa:aa:aa:ff:92",
    "18:aa:aa:aa:ff:93",
    "18:aa:aa:aa:ff:94",
    "18:aa:aa:aa:ff:95",
    "18:aa:aa:aa:ff:96",
    "18:aa:aa:aa:ff:97",
    "18:aa:aa:aa:ff:98",
    "18:aa:aa:aa:ff:99",
    "19:aa:aa:aa:ff:11",
    "19:aa:aa:aa:ff:12",
    "19:aa:aa:aa:ff:13",
    "19:aa:aa:aa:ff:14",
    "19:aa:aa:aa:ff:15",
    "19:aa:aa:aa:ff:16",
    "19:aa:aa:aa:ff:17",
    "19:aa:aa:aa:ff:18",
    "19:aa:aa:aa:ff:19",
    "19:aa:aa:aa:ff:20",
    "19:aa:aa:aa:ff:21",
    "19:aa:aa:aa:ff:22",
    "19:aa:aa:aa:ff:23",
    "19:aa:aa:aa:ff:24",
    "19:aa:aa:aa:ff:25",
    "19:aa:aa:aa:ff:26",
    "19:aa:aa:aa:ff:27",
    "19:aa:aa:aa:ff:28",
    "19:aa:aa:aa:ff:29",
    "19:aa:aa:aa:ff:30",
    "19:aa:aa:aa:ff:31",
    "19:aa:aa:aa:ff:32",
    "19:aa:aa:aa:ff:33",
    "19:aa:aa:aa:ff:34",
    "19:aa:aa:aa:ff:35",
    "19:aa:aa:aa:ff:36",
    "19:aa:aa:aa:ff:37",
    "19:aa:aa:aa:ff:38",
    "19:aa:aa:aa:ff:39",
    "19:aa:aa:aa:ff:40",
    "19:aa:aa:aa:ff:41",
    "19:aa:aa:aa:ff:42",
    "19:aa:aa:aa:ff:43",
    "19:aa:aa:aa:ff:44",
    "19:aa:aa:aa:ff:45",
    "19:aa:aa:aa:ff:46",
    "19:aa:aa:aa:ff:47",
    "19:aa:aa:aa:ff:48",
    "19:aa:aa:aa:ff:49",
    "19:aa:aa:aa:ff:50",
    "19:aa:aa:aa:ff:51",
    "19:aa:aa:aa:ff:52",
    "19:aa:aa:aa:ff:53",
    "19:aa:aa:aa:ff:54",
    "19:aa:aa:aa:ff:55",
    "19:aa:aa:aa:ff:56",
    "19:aa:aa:aa:ff:57",
    "19:aa:aa:aa:ff:58",
    "19:aa:aa:aa:ff:59",
    "19:aa:aa:aa:ff:60",
    "19:aa:aa:aa:ff:61",
    "19:aa:aa:aa:ff:62",
    "19:aa:aa:aa:ff:63",
    "19:aa:aa:aa:ff:64",
    "19:aa:aa:aa:ff:65",
    "19:aa:aa:aa:ff:66",
    "19:aa:aa:aa:ff:67",
    "19:aa:aa:aa:ff:68",
    "19:aa:aa:aa:ff:69",
    "19:aa:aa:aa:ff:70",
    "19:aa:aa:aa:ff:71",
    "19:aa:aa:aa:ff:72",
    "19:aa:aa:aa:ff:73",
    "19:aa:aa:aa:ff:74",
    "19:aa:aa:aa:ff:75",
    "19:aa:aa:aa:ff:76",
    "19:aa:aa:aa:ff:77",
    "19:aa:aa:aa:ff:78",
    "19:aa:aa:aa:ff:79",
    "19:aa:aa:aa:ff:80",
    "19:aa:aa:aa:ff:81",
    "19:aa:aa:aa:ff:82",
    "19:aa:aa:aa:ff:83",
    "19:aa:aa:aa:ff:84",
    "19:aa:aa:aa:ff:85",
    "19:aa:aa:aa:ff:86",
    "19:aa:aa:aa:ff:87",
    "19:aa:aa:aa:ff:88",
    "19:aa:aa:aa:ff:89",
    "19:aa:aa:aa:ff:90",
    "19:aa:aa:aa:ff:91",
    "19:aa:aa:aa:ff:92",
    "19:aa:aa:aa:ff:93",
    "19:aa:aa:aa:ff:94",
    "19:aa:aa:aa:ff:95",
    "19:aa:aa:aa:ff:96",
    "19:aa:aa:aa:ff:97",
    "19:aa:aa:aa:ff:98",
    "19:aa:aa:aa:ff:99",
    "20:aa:aa:aa:ff:11",
    "20:aa:aa:aa:ff:12",
    "20:aa:aa:aa:ff:13",
    "20:aa:aa:aa:ff:14",
    "20:aa:aa:aa:ff:15",
    "20:aa:aa:aa:ff:16",
    "20:aa:aa:aa:ff:17",
    "20:aa:aa:aa:ff:18",
    "20:aa:aa:aa:ff:19",
    "20:aa:aa:aa:ff:20",
    "20:aa:aa:aa:ff:21",
    "20:aa:aa:aa:ff:22",
    "20:aa:aa:aa:ff:23",
    "20:aa:aa:aa:ff:24",
    "20:aa:aa:aa:ff:25",
    "20:aa:aa:aa:ff:26",
    "20:aa:aa:aa:ff:27",
    "20:aa:aa:aa:ff:28",
    "20:aa:aa:aa:ff:29",
    "20:aa:aa:aa:ff:30",
    "20:aa:aa:aa:ff:31",
    "20:aa:aa:aa:ff:32",
    "20:aa:aa:aa:ff:33",
    "20:aa:aa:aa:ff:34",
    "20:aa:aa:aa:ff:35",
    "20:aa:aa:aa:ff:36",
    "20:aa:aa:aa:ff:37",
    "20:aa:aa:aa:ff:38",
    "20:aa:aa:aa:ff:39",
    "20:aa:aa:aa:ff:40",
    "20:aa:aa:aa:ff:41",
    "20:aa:aa:aa:ff:42",
    "20:aa:aa:aa:ff:43",
    "20:aa:aa:aa:ff:44",
    "20:aa:aa:aa:ff:45",
    "20:aa:aa:aa:ff:46",
    "20:aa:aa:aa:ff:47",
    "20:aa:aa:aa:ff:48",
    "20:aa:aa:aa:ff:49",
    "20:aa:aa:aa:ff:50",
    "20:aa:aa:aa:ff:51",
    "20:aa:aa:aa:ff:52",
    "20:aa:aa:aa:ff:53",
    "20:aa:aa:aa:ff:54",
    "20:aa:aa:aa:ff:55",
    "20:aa:aa:aa:ff:56",
    "20:aa:aa:aa:ff:57",
    "20:aa:aa:aa:ff:58",
    "20:aa:aa:aa:ff:59",
    "20:aa:aa:aa:ff:60",
    "20:aa:aa:aa:ff:61",
    "20:aa:aa:aa:ff:62",
    "20:aa:aa:aa:ff:63",
    "20:aa:aa:aa:ff:64",
    "20:aa:aa:aa:ff:65",
    "20:aa:aa:aa:ff:66",
    "20:aa:aa:aa:ff:67",
    "20:aa:aa:aa:ff:68",
    "20:aa:aa:aa:ff:69",
    "20:aa:aa:aa:ff:70",
    "20:aa:aa:aa:ff:71",
    "20:aa:aa:aa:ff:72",
    "20:aa:aa:aa:ff:73",
    "20:aa:aa:aa:ff:74",
    "20:aa:aa:aa:ff:75",
    "20:aa:aa:aa:ff:76",
    "20:aa:aa:aa:ff:77",
    "20:aa:aa:aa:ff:78",
    "20:aa:aa:aa:ff:79",
    "20:aa:aa:aa:ff:80",
    "20:aa:aa:aa:ff:81",
    "20:aa:aa:aa:ff:82",
    "20:aa:aa:aa:ff:83",
    "20:aa:aa:aa:ff:84",
    "20:aa:aa:aa:ff:85",
    "20:aa:aa:aa:ff:86",
    "20:aa:aa:aa:ff:87",
    "20:aa:aa:aa:ff:88",
    "20:aa:aa:aa:ff:89",
    "20:aa:aa:aa:ff:90",
    "20:aa:aa:aa:ff:91",
    "20:aa:aa:aa:ff:92",
    "20:aa:aa:aa:ff:93",
    "20:aa:aa:aa:ff:94",
    "20:aa:aa:aa:ff:95",
    "20:aa:aa:aa:ff:96",
    "20:aa:aa:aa:ff:97",
    "20:aa:aa:aa:ff:98",
    "20:aa:aa:aa:ff:99",
    "21:aa:aa:aa:ff:11",
    "21:aa:aa:aa:ff:12",
    "21:aa:aa:aa:ff:13",
    "21:aa:aa:aa:ff:14",
    "21:aa:aa:aa:ff:15",
    "21:aa:aa:aa:ff:16",
    "21:aa:aa:aa:ff:17",
    "21:aa:aa:aa:ff:18",
    "21:aa:aa:aa:ff:19",
    "21:aa:aa:aa:ff:20",
    "21:aa:aa:aa:ff:21",
    "21:aa:aa:aa:ff:22",
    "21:aa:aa:aa:ff:23",
    "21:aa:aa:aa:ff:24",
    "21:aa:aa:aa:ff:25",
    "21:aa:aa:aa:ff:26",
    "21:aa:aa:aa:ff:27",
    "21:aa:aa:aa:ff:28",
    "21:aa:aa:aa:ff:29",
    "21:aa:aa:aa:ff:30",
    "21:aa:aa:aa:ff:31",
    "21:aa:aa:aa:ff:32",
    "21:aa:aa:aa:ff:33",
    "21:aa:aa:aa:ff:34",
    "21:aa:aa:aa:ff:35",
    "21:aa:aa:aa:ff:36",
    "21:aa:aa:aa:ff:37",
    "21:aa:aa:aa:ff:38",
    "21:aa:aa:aa:ff:39",
    "21:aa:aa:aa:ff:40",
    "21:aa:aa:aa:ff:41",
    "21:aa:aa:aa:ff:42",
    "21:aa:aa:aa:ff:43",
    "21:aa:aa:aa:ff:44",
    "21:aa:aa:aa:ff:45",
    "21:aa:aa:aa:ff:46",
    "21:aa:aa:aa:ff:47",
    "21:aa:aa:aa:ff:48",
    "21:aa:aa:aa:ff:49",
    "21:aa:aa:aa:ff:50",
    "21:aa:aa:aa:ff:51",
    "21:aa:aa:aa:ff:52",
    "21:aa:aa:aa:ff:53",
    "21:aa:aa:aa:ff:54",
    "21:aa:aa:aa:ff:55",
    "21:aa:aa:aa:ff:56",
    "21:aa:aa:aa:ff:57",
    "21:aa:aa:aa:ff:58",
    "21:aa:aa:aa:ff:59",
    "21:aa:aa:aa:ff:60",
    "21:aa:aa:aa:ff:61",
    "21:aa:aa:aa:ff:62",
    "21:aa:aa:aa:ff:63",
    "21:aa:aa:aa:ff:64",
    "21:aa:aa:aa:ff:65",
    "21:aa:aa:aa:ff:66",
    "21:aa:aa:aa:ff:67",
    "21:aa:aa:aa:ff:68",
    "21:aa:aa:aa:ff:69",
    "21:aa:aa:aa:ff:70",
    "21:aa:aa:aa:ff:71",
    "21:aa:aa:aa:ff:72",
    "21:aa:aa:aa:ff:73",
    "21:aa:aa:aa:ff:74",
    "21:aa:aa:aa:ff:75",
    "21:aa:aa:aa:ff:76",
    "21:aa:aa:aa:ff:77",
    "21:aa:aa:aa:ff:78",
    "21:aa:aa:aa:ff:79",
    "21:aa:aa:aa:ff:80",
    "21:aa:aa:aa:ff:81",
    "21:aa:aa:aa:ff:82",
    "21:aa:aa:aa:ff:83",
    "21:aa:aa:aa:ff:84",
    "21:aa:aa:aa:ff:85",
    "21:aa:aa:aa:ff:86",
    "21:aa:aa:aa:ff:87",
    "21:aa:aa:aa:ff:88",
    "21:aa:aa:aa:ff:89",
    "21:aa:aa:aa:ff:90",
    "21:aa:aa:aa:ff:91",
    "21:aa:aa:aa:ff:92",
    "21:aa:aa:aa:ff:93",
    "21:aa:aa:aa:ff:94",
    "21:aa:aa:aa:ff:95",
    "21:aa:aa:aa:ff:96",
    "21:aa:aa:aa:ff:97",
    "21:aa:aa:aa:ff:98",
    "21:aa:aa:aa:ff:99",
    "22:aa:aa:aa:ff:11",
    "22:aa:aa:aa:ff:12",
    "22:aa:aa:aa:ff:13",
    "22:aa:aa:aa:ff:14",
    "22:aa:aa:aa:ff:15",
    "22:aa:aa:aa:ff:16",
    "22:aa:aa:aa:ff:17",
    "22:aa:aa:aa:ff:18",
    "22:aa:aa:aa:ff:19",
    "22:aa:aa:aa:ff:20",
    "22:aa:aa:aa:ff:21",
    "22:aa:aa:aa:ff:22",
    "22:aa:aa:aa:ff:23",
    "22:aa:aa:aa:ff:24",
    "22:aa:aa:aa:ff:25",
    "22:aa:aa:aa:ff:26",
    "22:aa:aa:aa:ff:27",
    "22:aa:aa:aa:ff:28",
    "22:aa:aa:aa:ff:29",
    "22:aa:aa:aa:ff:30",
    "22:aa:aa:aa:ff:31",
    "22:aa:aa:aa:ff:32",
    "22:aa:aa:aa:ff:33",
    "22:aa:aa:aa:ff:34",
    "22:aa:aa:aa:ff:35",
    "22:aa:aa:aa:ff:36",
    "22:aa:aa:aa:ff:37",
    "22:aa:aa:aa:ff:38",
    "22:aa:aa:aa:ff:39",
    "22:aa:aa:aa:ff:40",
    "22:aa:aa:aa:ff:41",
    "22:aa:aa:aa:ff:42",
    "22:aa:aa:aa:ff:43",
    "22:aa:aa:aa:ff:44",
    "22:aa:aa:aa:ff:45",
    "22:aa:aa:aa:ff:46",
    "22:aa:aa:aa:ff:47",
    "22:aa:aa:aa:ff:48",
    "22:aa:aa:aa:ff:49",
    "22:aa:aa:aa:ff:50",
    "22:aa:aa:aa:ff:51",
    "22:aa:aa:aa:ff:52",
    "22:aa:aa:aa:ff:53",
    "22:aa:aa:aa:ff:54",
    "22:aa:aa:aa:ff:55")
}
