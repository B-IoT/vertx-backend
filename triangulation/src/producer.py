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

    msg1 = orjson.dumps(
        {
            "relayID": "123",
            "rssi": [-40.0],
            "mac": ["mac"],
            "latitude": 42.34,
            "longitude": 2.32,
            "timestamp": "timestamp",
        }
    )
    msg2 = orjson.dumps(
        {
            "relayID": "124",
            "rssi": [-60.0],
            "mac": ["mac"],
            "latitude": 42.33,
            "longitude": 2.33,
            "timestamp": "timestamp",
        }
    )
    producer.produce("incoming.update", key="123", value=msg1, callback=acked)
    producer.produce("incoming.update", key="124", value=msg2, callback=acked)

    producer.poll(1)
