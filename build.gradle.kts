/*
 * Copyright (c) 2020 BIoT. All rights reserved.
 */

plugins {
    kotlin("jvm") version "1.3.72"
    id("com.github.johnrengelman.shadow") version "5.2.0" apply false
}

allprojects {
    extra["vertxVersion"] = if (project.hasProperty("vertxVersion")) project.property("vertxVersion") else "4.0.0"
    extra["junitJupiterVersion"] = "5.6.0"
    extra["logbackClassicVersion"] = "1.2.3"
    extra["testContainersVersion"] = "1.15.0"
    extra["restAssuredVersion"] = "4.3.2"
    extra["striktVersion"] = "0.28.0"
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

