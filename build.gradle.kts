import org.gradle.api.tasks.testing.logging.TestExceptionFormat.*

/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

plugins {
    kotlin("jvm") version "1.5.10"
    id("com.github.johnrengelman.shadow") version "6.1.0" apply false
    id("com.google.cloud.tools.jib") version "3.1.4" apply false
    id("com.github.ben-manes.versions") version "0.39.0"
    jacoco
}

repositories {
    mavenCentral()
    jcenter()
}

jacoco {
    toolVersion = "0.8.7"
}

allprojects {
    extra["vertxVersion"] = if (project.hasProperty("vertxVersion")) project.property("vertxVersion") else "4.1.2"
    extra["junitJupiterVersion"] = "5.8.0"
    extra["logbackClassicVersion"] = "1.2.6"
    extra["kotlinLoggingVersion"] = "2.0.11"
    extra["testContainersVersion"] = "1.16.0"
    extra["restAssuredVersion"] = "4.4.0"
    extra["striktVersion"] = "0.31.0"
    extra["hazelcastVersion"] = "2.2.3"
    extra["micrometerPrometheusVersion"] = "1.8.3"
    extra["arrowVersion"] = "0.13.2"
    extra["mockkVersion"] = "1.12.0"
}

subprojects {
    repositories {
        mavenCentral()
        jcenter()
    }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "application")
    apply(plugin = "com.github.johnrengelman.shadow")
    apply(plugin = "com.google.cloud.tools.jib")
    apply(plugin = "jacoco")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.jacocoTestReport {
        reports {
            xml.isEnabled = true
            csv.isEnabled = false
            html.isEnabled = true
            html.destination = file("$buildDir/reports/coverage")
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "11"
        }
    }

    group = "ch.biot.backend"
}

tasks.register<JacocoReport>("jacocoRootReport") {
    subprojects {
        this@subprojects.plugins.withType<JacocoPlugin>().configureEach {
            this@subprojects.tasks.matching {
                it.extensions.findByType<JacocoTaskExtension>() != null
            }
                .configureEach {
                    sourceSets(this@subprojects.the<SourceSetContainer>().named("main").get())
                    executionData(this)
                }
        }
    }

    reports {
        xml.isEnabled = true
        csv.isEnabled = false
        html.isEnabled = true
        html.destination = file("$buildDir/reports/coverage")
    }

    dependsOn(tasks.test)
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport) // report is always generated after tests run
    testLogging {
        exceptionFormat = FULL
    }
}
tasks.jacocoTestReport {
    dependsOn(tasks.test) // tests are required to run before generating the report
}

tasks.named("dependencyUpdates", com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask::class.java)
    .configure {
        fun isNonStable(version: String): Boolean {
            val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
            val regex = "^[0-9,.v-]+(-r)?$".toRegex()
            val isStable = stableKeyword || regex.matches(version)
            return isStable.not()
        }

        gradleReleaseChannel = "current"

        revision = "release"

        resolutionStrategy {
            componentSelection {
                all {
                    if (isNonStable(candidate.version) && !isNonStable(currentVersion)) {
                        reject("Release candidate")
                    }
                }
            }
        }
    }

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
    gradleVersion = "6.7.1"
}
