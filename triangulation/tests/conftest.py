# Copyright (c) 2021 BioT. All rights reserved.

import pytest
import rootpath
from confluent_kafka.admin import AdminClient, NewTopic
from kafka import KafkaConsumer
from kafka.errors import KafkaError
from testcontainers.core.waiting_utils import wait_container_is_ready
from testcontainers.compose import DockerCompose

from triangulation.src.main import TOPIC

BOOTSTRAP_SERVERS = "localhost:9092"


@pytest.fixture
def kafka_container_using_docker_compose():
    container = get_container()
    yield
    container.stop()


@wait_container_is_ready()
def get_container():
    COMPOSE_PATH = "{}/tests".format(rootpath.detect(".", pattern="tests"))
    compose = DockerCompose(COMPOSE_PATH)
    print('HERE0')
    compose.start()
    bootstrap_server = "localhost:9092"
    consumer = KafkaConsumer(group_id="test", bootstrap_servers=[bootstrap_server])
    if not consumer.topics():
        raise KafkaError("Unable to connect with kafka container!")

    print('HERE1')

    kafka_admin = AdminClient({"bootstrap.servers": BOOTSTRAP_SERVERS})
    fs = kafka_admin.create_topics(
        [NewTopic(TOPIC, num_partitions=1, replication_factor=1)]
    )

    print('HERE2')

    for topic, f in fs.items():
        try:
            f.result()  # The result itself is None
        except Exception as e:
            print("Failed to create topic {}: {}".format(topic, e))

    return compose
