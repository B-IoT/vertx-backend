# Copyright (c) 2021 BioT. All rights reserved.

from .config import logger, KAFKA_HOST, KAFKA_PORT, DEV

from confluent_kafka import Consumer, KafkaError, KafkaException
from typing import List, Callable
import orjson
import sys


class KafkaConsumer:
    """
    A Kafka consumer.
    """

    MIN_COMMIT_COUNT = 5

    def __init__(self):
        conf = {
            "bootstrap.servers": f"{KAFKA_HOST}:{KAFKA_PORT}",
            "group.id": "triangulation-client",
            "on_commit": self._commit_completed,
            "auto.offset.reset": "earliest" if DEV else "latest",
            "allow.auto.create.topics": "true",
        }
        self.consumer = Consumer(conf)
        self.should_consume = True

    def _commit_completed(self, err, _):
        """
        Callack on commit completed.
        """
        if err:
            logger.error(str(err))

    async def _consume_loop(
        self,
        consumer: Consumer,
        topics: List[str],
        on_message: Callable[[str, dict], None],
    ):
        """
        Starts consuming messages from the given topics using the given consumer.
        """
        try:
            consumer.subscribe(topics)

            msg_count = 0
            while self.should_consume:
                msg = consumer.poll(timeout=1.0)
                if msg is None:
                    continue

                if msg.error():
                    if msg.error().code() == KafkaError._PARTITION_EOF:
                        # End of partition event
                        sys.stderr.write(
                            "%% %s [%d] reached end at offset %d\n"
                            % (msg.topic(), msg.partition(), msg.offset())
                        )
                    elif msg.error():
                        raise KafkaException(msg.error())
                else:
                    # Valid message received
                    key: str = msg.key().decode()
                    value: dict = orjson.loads(msg.value())
                    logger.info("Message received:")
                    logger.info("Key = {}", key)
                    logger.info("Value = {}", value)

                    await on_message(key, value)

                    msg_count += 1
                    if msg_count % self.MIN_COMMIT_COUNT == 0:
                        consumer.commit()
        finally:
            # Close down consumer to commit final offsets.
            consumer.close()

    def stop(self):
        """
        Stops the consumer.
        """
        self.should_consume = False

    async def start(self, topics: List[str], on_message: Callable[[str, dict], None]):
        """
        Starts consuming the given topics, calling the given callback upon message reception.
        """
        await self._consume_loop(self.consumer, topics, on_message)
