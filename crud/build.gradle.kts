/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.testing.logging.TestLogEvent.*

version = "1.4.0-SNAPSHOT"

val mainVerticleName = "ch.biot.backend.crud.CRUDVerticle"
val watchForChange = "src/**/*"
val doOnChange = "./gradlew classes"
val launcherClassName = "io.vertx.core.Launcher"

application {
  mainClassName = launcherClassName
}

dependencies {
  val vertxVersion = project.extra["vertxVersion"]
  val junitJupiterVersion = project.extra["junitJupiterVersion"]
  val logbackClassicVersion = project.extra["logbackClassicVersion"]
  val kotlinLoggingVersion = project.extra["kotlinLoggingVersion"]
  val restAssuredVersion = project.extra["restAssuredVersion"]
  val striktVersion = project.extra["striktVersion"]
  val testContainersVersion = project.extra["testContainersVersion"]
  val hazelcastVersion = project.extra["hazelcastVersion"]
  val arrowVersion = project.extra["arrowVersion"]
  val mockkVersion = project.extra["mockkVersion"]

  implementation("io.vertx:vertx-pg-client:$vertxVersion")
  implementation("io.vertx:vertx-mongo-client:$vertxVersion")
  implementation("io.vertx:vertx-auth-mongo:$vertxVersion")
  implementation("io.vertx:vertx-web:$vertxVersion")
  implementation("io.vertx:vertx-web-openapi:$vertxVersion")
  implementation("io.vertx:vertx-hazelcast:$vertxVersion")
  implementation("com.hazelcast:hazelcast-kubernetes:$hazelcastVersion")
  implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
  implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
  implementation("io.arrow-kt:arrow-fx-coroutines:$arrowVersion")
  implementation("ch.qos.logback:logback-classic:$logbackClassicVersion")
  implementation("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")
  testImplementation("org.testcontainers:junit-jupiter:$testContainersVersion")
  testImplementation("io.rest-assured:kotlin-extensions:$restAssuredVersion")
  testImplementation("io.vertx:vertx-junit5:$vertxVersion")
  testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
  testImplementation("io.strikt:strikt-gradle:$striktVersion")
  testImplementation("io.mockk:mockk:$mockkVersion")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
}

tasks.withType<ShadowJar> {
  archiveClassifier.set("fat")
  manifest {
    attributes(mapOf("Main-Verticle" to mainVerticleName))
  }
  mergeServiceFiles {
    include("META-INF/services/io.vertx.core.spi.VerticleFactory")
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events = setOf(PASSED, SKIPPED, FAILED)
  }
}

tasks.withType<JavaExec> {
  args = listOf(
    "run",
    mainVerticleName,
    "--redeploy=$watchForChange",
    "--launcher-class=$launcherClassName",
    "--on-redeploy=$doOnChange"
  )
  systemProperties["vertx.logger-delegate-factory-class-name"] = "io.vertx.core.logging.SLF4JLogDelegateFactory"
}

jib {
  from {
    image = "adoptopenjdk/openjdk11:ubi-minimal-jre"
  }
  to {
    image = "vertx-backend/crud"
    tags = setOf("v1.4.0", "latest")
  }
  container {
    mainClass = mainVerticleName
    jvmFlags = listOf("-noverify", "-Djava.security.egd=file:/dev/./urandom")
    ports = listOf("8081", "5701")
    user = "nobody:nobody"
  }
}
