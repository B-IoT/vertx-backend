from confluent_kafka import Consumer, KafkaError, KafkaException
from decouple import config
import asyncio
import asyncpg
import sys
import gc
import orjson
import datetime
import numpy as np
import pandas as pd


MIN_COMMIT_COUNT = 5
TOPIC = "incoming.update"
KAFKA_HOST = config("KAFKA_HOST", default="localhost")
KAFKA_PORT = config("KAFKA_PORT", default=9092, cast=int)
TIMESCALE_HOST = config("TIMESCALE_HOST", default="localhost")
TIMESCALE_PORT = config("TIMESCALE_PORT", default=5432, cast=int)

connectivity_df = pd.DataFrame()
relay_df = pd.DataFrame(index=["lat", "long", "floor"])


def lat_to_meters(lat1, lon1, lat2, lon2):
    """
    Converts lat distance to meters.
    """
    R = 6378.137  # Radius of earth in KM
    prod = np.pi / 180
    dLat = (lat2 - lat1) * prod
    dLon = (lon2 - lon1) * prod
    a = (
        np.sin(dLat / 2) ** 2
        + np.cos(lat1 * prod) * np.cos(lat2 * prod) * np.sin(dLon / 2) ** 2
    )
    c = 2 * np.arctan2(np.sqrt(a), np.sqrt(1 - a))
    d = R * c
    return d * 1000  # meters


def db_to_meters(RSSI, measure_ref, N):
    """
    Converts dB to meters.
    """
    d = 10 ** ((measure_ref - RSSI) / (10 * N))
    return d


async def store_beacons_data(data):
    """
    Stores the beacons' data in TimescaleDB.
    Data must be an array of tuples of the following form: ("aa:aa:aa:aa:aa:aa", 10, "available", 2.3, 3.2, 1).
    """
    async with asyncpg.create_pool(
        host=TIMESCALE_HOST,
        port=TIMESCALE_PORT,
        database="biot",
        user="biot",
        password="biot",
    ) as pool:
        async with pool.acquire() as conn:
            stmt = await conn.prepare(
                """INSERT INTO beacon_data (time, mac, battery, status, latitude, longitude, floor) VALUES (NOW(), $1, $2, $3, $4, $5, $6);"""
            )
            await stmt.executemany(data)
            print("  New beacons' data inserted in DB")


def triangulate(relay_id, data):
    relay_df[relay_id] = [data["latitude"], data["longitude"], data["floor"]]

    beacons = data["mac"]

    if not beacons:
        print('  No beacon detected, skipping!')
        return

    df_beacons = pd.DataFrame(columns=['beacon', 'rssi']) # used to remove duplicates through averaging
    df_beacons['beacon'] = beacons
    df_beacons['rssi'] = data["rssi"]
    averaged = df_beacons.groupby('beacon').agg('mean')
    
    relay = pd.DataFrame(index=["RSSI", "tx", "ref"], columns=averaged.index)
    relay.loc["RSSI"] = averaged["rssi"]
    relay.loc["tx"] = 6
    relay.loc["ref"] = -69
    relay.loc["dist"] = db_to_meters(
        relay.loc["RSSI"], relay.loc["ref"], relay.loc["tx"]
    )

    connectivity_df[relay_id] = relay.loc["dist"]

    updated_beacon = connectivity_df[~connectivity_df.loc[:, relay_id].isnull()].index

    # Triangulation of each beacon
    coordinates = []
    for i in range(len(updated_beacon)):
        beacon = beacons[i]

        # Temporary df for the data of the beacon
        temp_df = pd.DataFrame(
            connectivity_df.loc[beacon, :][connectivity_df.loc[beacon, :] > 0]
        )
        temp_df = temp_df.reset_index().rename(
            columns={"index": "relay", beacon: "dist"}
        )
        # Only use the 5 closest relays to the beacon
        temp_df = temp_df.sort_values("dist", axis=0, ascending=True).iloc[:5, :]

        lat = []
        long = []
        nb_relays = len(temp_df)
        if nb_relays > 1:
            print(
                f"  Beacon '{beacon}' detected by {nb_relays} relays, starting triangulation..."
            )
            for relay_1 in range(nb_relays - 1):
                for relay_2 in range(relay_1 + 1, nb_relays):
                    # Making a vector between the 2 gateways
                    rel_1 = temp_df.loc[relay_1, "relay"]
                    rel_2 = temp_df.loc[relay_2, "relay"]
                    vect_lat = relay_df.loc["lat", rel_2] - relay_df.loc["lat", rel_1]
                    vect_long = (
                        relay_df.loc["long", rel_2] - relay_df.loc["long", rel_1]
                    )

                    # Calculating the distance between the 2 gateways in meter
                    dist = lat_to_meters(
                        relay_df.loc["lat", rel_1],
                        relay_df.loc["long", rel_1],
                        relay_df.loc["lat", rel_2],
                        relay_df.loc["long", rel_2],
                    )

                    # Applying proportionality rule from the origin on the vector to determine the position of the beacon in lat;long coord
                    # ie: x1 = x0 + (dist_beacon/dist_tot) * vector_length

                    dist_1 = temp_df.loc[relay_1, "dist"]
                    lat.append(relay_df.loc["lat", rel_1] + (dist_1 / dist) * vect_lat)
                    long.append(
                        relay_df.loc["long", rel_1] + (dist_1 / dist) * vect_long
                    )

            temp_relay = temp_df.loc[:, "relay"]
            floor = np.mean(relay_df.loc["floor", temp_relay])   

            if (floor - np.floor(floor)) - 0.5 <= 1e-5: 
                # Case where we're in the middle, eg: floor = 1.5
                # Taking the floor of the closest relay
                floor = relay_df.loc["floor", temp_df.loc[0, "relay"]] 
            else:
                # Otherwise taking the mean floor + rounding it
                floor = np.around(np.mean(relay_df.loc["floor", temp_relay])) 
            
            coordinates.append((beacon, 10, "available", np.mean(lat), np.mean(long), floor))

            print("  Triangulation done")
        elif len(temp_df) == 1:
            print(f"  Beacon '{beacon}' detected by only one relay, skipping!")
        else:
            print(f"  Beacon '{beacon}' not detected by any relay, skipping!")

    if coordinates:
        loop = asyncio.get_event_loop()
        loop.run_until_complete(store_beacons_data(coordinates))
    
    # Garbage collect
    del df_beacons
    del averaged
    del relay
    del updated_beacon
    del coordinates
    del temp_df
    gc.collect()


def commit_completed(err, _):
    """
    Callack on commit completed.
    """
    if err:
        print(str(err))


def consume_loop(consumer, topics):
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
                print(f"[{datetime.datetime.utcnow()}] Message received:")
                print(f"  Key = {key}")h
                print(f"  Value = {value}")

                triangulate(key, value)

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
            "auto.offset.reset": "latest",
            "allow.auto.create.topics": "true"
        }

        consumer = Consumer(conf)

        print(f"Starting Kafka consumer loop on topic '{TOPIC}'...")
        consume_loop(consumer, [TOPIC])
    except KeyboardInterrupt:
        print("\nStopped consumer!")
