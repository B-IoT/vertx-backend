from confluent_kafka import Producer
import socket
import orjson


def acked(err, msg):
    if err is not None:
        print("Failed to deliver message: %s: %s" % (str(msg), str(err)))
    else:
        print("Message produced: %s" % (str(msg)))


if __name__ == "__main__":
    conf = {
        "bootstrap.servers": "localhost:9092",
        "client.id": socket.gethostname(),
    }

    producer = Producer(conf)

    msg = orjson.dumps(
        {
            "relayID": "123",
            "rssi": [-60.0],
            "mac": ["mac"],
            "latitude": 2.3,
            "longitude": 2.3,
            "timestamp": "timestamp",
        }
    )
    producer.produce("incoming.update", key="key", value=msg, callback=acked)

    producer.poll(1)
