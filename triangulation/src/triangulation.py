from confluent_kafka import Consumer, KafkaError, KafkaException
from decouple import config
import sys
import orjson


MIN_COMMIT_COUNT = 5
TOPIC = "incoming.update"
KAFKA_HOST = config("KAFKA_HOST", default="localhost")
KAFKA_PORT = config("KAFKA_PORT", default=9092, cast=int)


def store_beacons_data(data):
    # TODO with asyncpg
    pass


def commit_completed(err, _):
    if err:
        print(str(err))


def consume_loop(consumer, topics):
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
                print("Message received:")
                print(f"  Key = {key}")
                print(f"  Value = {value}")

                # TODO here triangulate

                msg_count += 1
                if msg_count % MIN_COMMIT_COUNT == 0:
                    consumer.commit()
    finally:
        # Close down consumer to commit final offsets.
        consumer.close()


if __name__ == "__main__":
    try:
        conf = {
            "bootstrap.servers": f"{KAFKA_HOST}:{KAFKA_PORT}",
            "group.id": "triangulation-client",
            "on_commit": commit_completed,
            "auto.offset.reset": "earliest",
        }

        consumer = Consumer(conf)

        print(f"Starting consume loop on topic '{TOPIC}'")
        consume_loop(consumer, [TOPIC])
    except KeyboardInterrupt:
        print("\nStopped consumer")
