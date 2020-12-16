# vertx-backend

[![CircleCI](https://circleci.com/gh/B-IoT/vertx-backend.svg?style=svg&circle-token=fd177fb3dd3ca232d70bb91cb6a133154a9ad57f)](https://app.circleci.com/pipelines/github/B-IoT)

## Development and testing

The project is multi-module.

To run a command for a specific module, use `./gradlew <module_name>:<command_name>` (ex: `./gradlew crud:run`).

To launch all tests, use `./gradlew test`.

To build all JARs, use `./gradlew build` (it will also run the tests).

To generate aggregated JaCoCo test coverage reports, first run all tests, then use `./gradlew jacocoFullReport`.

## Architecture

![Architecture](documentation/architecture.png 'Backend architecture')

The image has been generated with [Graphviz](http://dreampuf.github.io/GraphvizOnline).

## Postman

To join the team use this [link](https://app.getpostman.com/join-team?invite_code=e075e3eaae3d0cbca574457fee024a1c).

## Swagger

The organization page is [here](https://app.swaggerhub.com/organizations/b-iot).
