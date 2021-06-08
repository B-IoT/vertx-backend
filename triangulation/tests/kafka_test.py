# Copyright (c) 2021 BioT. All rights reserved.

import pytest
import socket
import orjson
from triangulation.src.kafka import KafkaConsumer
from triangulation.src.main import TOPIC
from confluent_kafka import Producer

BOOTSTRAP_SERVERS = "localhost:9092"


def acked(err, msg):
    if err is not None:
        print("Failed to deliver message: %s: %s" % (str(msg), str(err)))
    else:
        print("Message produced: %s" % (str(msg)))


@pytest.mark.asyncio
@pytest.mark.usefixtures("kafka_container_using_docker_compose")
async def test_kafka_consumer_is_correctly_created(mocker):
    kafkaConsumer = KafkaConsumer()
    on_message_stub = mocker.stub(name="on_message_stub")
    await kafkaConsumer.start([TOPIC], on_message_stub)

    producer = Producer(
        {
            "bootstrap.servers": BOOTSTRAP_SERVERS,
            "client.id": socket.gethostname(),
        }
    )

    key = "relay_1"
    msg_dict = {
        "relayID": key,
        "beacons": [
            {
                "mac": "aaaa",
                "rssi": -69,
                "battery": 53,
                "temperature": 25,
                "status": 0,
            },
            {
                "mac": "bbbb",
                "rssi": -62,
                "battery": 51,
                "temperature": 30,
                "status": 1,
            },
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg = orjson.dumps(msg_dict)
    producer.produce(TOPIC, key=key, value=msg, callback=acked)
    producer.poll(1)

    on_message_stub.assert_called_once_with(key, msg_dict)
