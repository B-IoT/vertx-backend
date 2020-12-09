/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

plugins {
    kotlin("jvm") version "1.4.21"
    id("com.github.johnrengelman.shadow") version "5.2.0" apply false
    id("com.github.ben-manes.versions") version "0.36.0"
}

repositories {
    mavenCentral()
    jcenter()
}

allprojects {
    extra["vertxVersion"] = if (project.hasProperty("vertxVersion")) project.property("vertxVersion") else "4.0.0"
    extra["junitJupiterVersion"] = "5.7.0"
    extra["logbackClassicVersion"] = "1.2.3"
    extra["testContainersVersion"] = "1.15.0"
    extra["restAssuredVersion"] = "4.3.2"
    extra["striktVersion"] = "0.28.1"
    extra["rxKotlinVersion"] = "2.4.0"
}

subprojects {
    repositories {
        mavenCentral()
        jcenter()
    }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "application")
    apply(plugin = "com.github.johnrengelman.shadow")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "11"
        }
    }

    group = "ch.biot.backend"
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
