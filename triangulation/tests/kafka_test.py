# Copyright (c) 2021 BioT. All rights reserved.

import pytest
import socket
import orjson
from confluent_kafka import Producer

from ..src.kafka import KafkaConsumer
from ..src.main import TOPIC
from ..src.config import KAFKA_HOST, KAFKA_PORT



@pytest.mark.asyncio
@pytest.mark.usefixtures("docker_compose")
async def test_kafka_consumer_consumes_new_message_and_calls_the_callback(mocker):
    producer = Producer(
        {
            "bootstrap.servers": f"{KAFKA_HOST}:{KAFKA_PORT}",
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
    producer.produce(TOPIC, key=key, value=msg)
    producer.flush()

    kafkaConsumer = KafkaConsumer()
    on_message_stub = mocker.AsyncMock(side_effect=lambda k, m: kafkaConsumer.stop())
    await kafkaConsumer.start([TOPIC], on_message_stub)

    on_message_stub.assert_awaited_once_with(key, msg_dict)
