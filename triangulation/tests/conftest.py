# Copyright (c) 2021 BioT. All rights reserved.

import pytest
import rootpath
from confluent_kafka import Consumer
from testcontainers.core.waiting_utils import wait_container_is_ready
from testcontainers.compose import DockerCompose

from src.config import KAFKA_HOST, KAFKA_PORT


@pytest.fixture
def docker_compose():
    container = get_container()
    yield
    container.stop()


@wait_container_is_ready()
def get_container():
    COMPOSE_PATH = "{}/tests".format(rootpath.detect(".", pattern="tests"))
    compose = DockerCompose(COMPOSE_PATH)
    compose.start()
    consumer = Consumer(
        {
            "bootstrap.servers": f"{KAFKA_HOST}:{KAFKA_PORT}",
            "group.id": "test",
        }
    )
    if not consumer.list_topics():
        raise ValueError("Unable to connect with kafka container!")

    consumer.close()

    return compose
