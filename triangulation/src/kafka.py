from confluent_kafka import Consumer, KafkaError, KafkaException
import orjson
import sys
from config import logger, KAFKA_HOST, KAFKA_PORT


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
            "auto.offset.reset": "latest",
            "allow.auto.create.topics": "true",
        }
        self.consumer = Consumer(conf)

    def _commit_completed(self, err, _):
        """
        Callack on commit completed.
        """
        if err:
            logger.error(str(err))

    async def _consume_loop(self, consumer, topics, on_message):
        """
        Starts consuming messages from the given topics using the given consumer.
        """
        try:
            consumer.subscribe(topics)

            msg_count = 0
            while True:
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

    async def start(self, topics, on_message):
        """
        Starts consuming the given topics, calling the given callback upon message reception.
        """
        await self._consume_loop(self.consumer, topics, on_message)
