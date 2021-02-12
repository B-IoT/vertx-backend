# vertx-backend

[![CircleCI](https://circleci.com/gh/B-IoT/vertx-backend.svg?style=svg&circle-token=fd177fb3dd3ca232d70bb91cb6a133154a9ad57f)](https://app.circleci.com/pipelines/github/B-IoT) [![codecov](https://codecov.io/gh/B-IoT/vertx-backend/branch/main/graph/badge.svg?token=WU4T7FRLTN)](https://codecov.io/gh/B-IoT/vertx-backend)

## Development and testing

The project is multi-module.

To run a command for a specific module, use `./gradlew <module_name>:<command_name>` (ex: `./gradlew crud:run`).

To launch all tests, use `./gradlew test`.

To build all JARs, use `./gradlew build` (it will also run the tests).

To generate aggregated JaCoCo test coverage reports, first run all tests, then use `./gradlew jacocoRootReport`.

If you have a local Kubernetes cluster (with MongoDB, TimescaleDB and Kafka running), you can use `skaffold dev` to locally deploy and run the backend in watch mode.

## Deployment

To build, push images to [Google Cloud Registry](https://console.cloud.google.com/gcr/images/dark-mark-304414?project=dark-mark-304414&authuser=1) and deploy to [Google Kubernetes Engine](https://console.cloud.google.com/kubernetes/list?authuser=1&project=dark-mark-304414), use `skaffold run -d eu.gcr.io/dark-mark-304414`.

## Architecture

![Architecture](documentation/architecture.png 'Backend architecture')

The image has been generated with [Graphviz](http://dreampuf.github.io/GraphvizOnline).

## Postman

To join the team use this [link](https://app.getpostman.com/join-team?invite_code=e075e3eaae3d0cbca574457fee024a1c).

## Swagger

The organization page is [here](https://app.swaggerhub.com/organizations/b-iot).
