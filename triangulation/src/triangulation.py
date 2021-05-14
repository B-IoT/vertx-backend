# Copyright (c) 2021 BioT. All rights reserved.

from config import logger, TIMESCALE_HOST, TIMESCALE_PORT

import asyncpg
import gc
import numpy as np
import pandas as pd
from collections import defaultdict
from typing import DefaultDict, Tuple, List


class _CoordinatesHistory:
    """
    Coordinates history used for weighted moving average.
    """

    MAX_HISTORY_SIZE = 5

    def __init__(self):
        self.history_per_beacon: DefaultDict[
            str, List[Tuple[float, float]]
        ] = defaultdict(list)
        self.weights = self._build_weights_dict()

    def update_coordinates_history(
        self, beacon: str, new_coordinates: Tuple[float, float]
    ):
        """
        Updates the coordinates history of the given beacon with the given new coordinates.
        """
        history = self.history_per_beacon[beacon]
        size = len(history)
        if size == self.MAX_HISTORY_SIZE:
            # Remove oldest, to keep history's length to 5
            history.pop()

        # Insert the element to the head of the list
        history.insert(0, new_coordinates)

    def _compute_weights(self, length: int):
        """
        Computes the weights to be used for the moving average.
        """
        denom = (length * (length + 1)) / 2
        return np.array([n / denom for n in range(length, 0, -1)])

    def _build_weights_dict(self):
        """
        Builds the dictionary of the weights, with keys (corresponding to the length of the coordinates history)
        from 1 to 5.
        """
        return {
            i: self._compute_weights(i) for i in range(1, self.MAX_HISTORY_SIZE + 1)
        }

    def weighted_moving_average(self, beacon: str) -> Tuple[float, float]:
        """
        Computes the moving average given the beacon, returning the average for both latitude and longitude.
        """
        coordinates_history = self.history_per_beacon[beacon]
        size = len(coordinates_history)
        w = self.weights[size]
        latitudes, longitudes = zip(*coordinates_history)
        return np.average(latitudes, weights=w), np.average(longitudes, weights=w)


class Triangulator:
    """
    Beacons' triangulator.
    """

    MOVEMENT_DETECTED = 0
    BUTTON_PRESSED = 1
    MOVEMENT_DETECTED_AND_BUTTON_PRESSED = 2

    TO_REPAIR = "toRepair"

    @classmethod
    async def create(cls):
        # We need to use this because it is impossible to call await inside __init__()
        self = Triangulator()
        # Data structures
        self.connectivity_df = pd.DataFrame()
        self.relay_df = pd.DataFrame(index=["lat", "long", "floor"])
        self.db_pool = await asyncpg.create_pool(
            host=TIMESCALE_HOST,
            port=TIMESCALE_PORT,
            database="biot",
            user="biot",
            password="biot",
        )
        self.coordinates_history = _CoordinatesHistory()

        return self

    async def _store_beacons_data(self, company: str, data: list):
        """
        Stores the beacons' data in TimescaleDB.
        Data must be an array of tuples of the following form: ("aa:aa:aa:aa:aa:aa", 10, "available", 2.3, 3.2, 1, 25).
        """
        async with self.db_pool.acquire() as conn:
            table_name = (
                f"beacon_data_{company}" if company != "biot" else "beacon_data"
            )
            stmt = await conn.prepare(
                f"""INSERT INTO {table_name} (time, mac, battery, status, latitude, longitude, floor, temperature) VALUES (NOW(), $1, $2, $3, $4, $5, $6, $7);"""
            )
            await stmt.executemany(data)
            logger.info("New beacons' data inserted in DB {table_name}: {}", data)

    async def _update_beacon_status(self, company: str, mac: str, status: str):
        """
        Updates the status of the given beacon.
        """
        async with self.db_pool.acquire() as conn:
            table_name = (
                f"beacon_data_{company}" if company != "biot" else "beacon_data"
            )
            stmt = await conn.prepare(
                f"""UPDATE {table_name} SET status = $1 WHERE mac = $2 AND time = (SELECT MAX(time) FROM {table_name} WHERE mac = $2);"""
            )
            await stmt.executemany([(status, mac)])
            logger.info(
                "Updated beacon '{}' status in DB {table_name} to '{}'", mac, status
            )

    def _lat_to_meters(self, lat1, lon1, lat2, lon2):
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

    def _db_to_meters(self, RSSI, measure_ref, N):
        """
        Converts dB to meters.
        """
        d = 10 ** ((measure_ref - RSSI) / (10 * N))
        return d

    async def triangulate(self, relay_id: str, data: dict):
        """
        Triangulates all beacons detected by the given relay, if enough information is available.
        """

        self.relay_df[relay_id] = [
            data["latitude"],
            data["longitude"],
            int(data["floor"]),
        ]

        company = data["company"]

        beacons = data["beacons"]

        # Filter out empty arrays
        if not beacons:
            logger.info("No beacon detected, skipping!")
            return

        # Filter out empty MAC addresses
        macs, rssis = zip(
            *[(beacon["mac"], beacon["rssi"]) for beacon in beacons if beacon["mac"]]
        )

        # Used to remove duplicates through averaging
        df_beacons = pd.DataFrame(columns=["beacon", "rssi"])
        df_beacons["beacon"] = macs
        df_beacons["rssi"] = rssis
        averaged = df_beacons.groupby("beacon").agg("mean")

        relay = pd.DataFrame(index=["RSSI", "tx", "ref"], columns=averaged.index)
        relay.loc["RSSI"] = averaged["rssi"]
        relay.loc["tx"] = 6
        relay.loc["ref"] = -69
        relay.loc["dist"] = self._db_to_meters(
            relay.loc["RSSI"], relay.loc["ref"], relay.loc["tx"]
        )

        # Update the connectivity matrix with the new distances to the relay
        distances = relay.loc["dist"].rename(relay_id)
        OLD_SUFFIX = "_old"
        self.connectivity_df = pd.merge(
            self.connectivity_df,
            distances,
            how="outer",
            left_index=True,
            right_index=True,
            suffixes=(OLD_SUFFIX, None),
        )
        # Filter out old measurements
        self.connectivity_df = self.connectivity_df[
            [col for col in self.connectivity_df.columns if OLD_SUFFIX not in col]
        ]

        updated_beacons = self.connectivity_df[
            ~self.connectivity_df.loc[:, relay_id].isnull()
        ].index

        # Triangulation of each beacon
        coordinates = []
        for i in range(len(updated_beacons)):
            mac = macs[i]
            beacon_data = next(b for b in beacons if b["mac"] == mac)

            if mac not in self.connectivity_df.index:
                continue

            if beacon_data["status"] == self.BUTTON_PRESSED:
                await self._update_beacon_status(company, mac, self.TO_REPAIR)
                continue

            # Temporary df for the data of the beacon
            temp_df = pd.DataFrame(
                self.connectivity_df.loc[mac, :][self.connectivity_df.loc[mac, :] > 0]
            )
            temp_df = temp_df.reset_index().rename(
                columns={"index": "relay", mac: "dist"}
            )
            # Only use the 5 closest relays to the beacon
            temp_df = temp_df.sort_values("dist", axis=0, ascending=True).iloc[:5, :]
            temp_df = temp_df.reset_index()

            lat = []
            long = []
            nb_relays = len(temp_df)
            if nb_relays > 1:
                logger.info(
                    "Beacon '{}' detected by {} relays, starting triangulation...",
                    mac,
                    nb_relays,
                )
                for relay_1 in range(nb_relays - 1):
                    for relay_2 in range(relay_1 + 1, nb_relays):
                        # Making a vector between the 2 gateways
                        rel_1 = temp_df.loc[relay_1, "relay"]
                        rel_2 = temp_df.loc[relay_2, "relay"]
                        vect_lat = (
                            self.relay_df.loc["lat", rel_2]
                            - self.relay_df.loc["lat", rel_1]
                        )
                        vect_long = (
                            self.relay_df.loc["long", rel_2]
                            - self.relay_df.loc["long", rel_1]
                        )

                        # Calculating the distance between the 2 gateways in meter
                        dist = self._lat_to_meters(
                            self.relay_df.loc["lat", rel_1],
                            self.relay_df.loc["long", rel_1],
                            self.relay_df.loc["lat", rel_2],
                            self.relay_df.loc["long", rel_2],
                        )

                        # Applying proportionality rule from the origin on the vector to determine the position of the beacon in lat;long coord
                        # ie: x1 = x0 + (dist_beacon/dist_tot) * vector_length

                        dist_1 = temp_df.loc[relay_1, "dist"]
                        lat.append(
                            self.relay_df.loc["lat", rel_1] + (dist_1 / dist) * vect_lat
                        )
                        long.append(
                            self.relay_df.loc["long", rel_1]
                            + (dist_1 / dist) * vect_long
                        )

                temp_relay = temp_df.loc[:, "relay"]
                floor = np.mean(self.relay_df.loc["floor", temp_relay])

                if (floor - np.floor(floor)) - 0.5 <= 1e-5:
                    # Case where we're in the middle, eg: floor = 1.5
                    # Taking the floor of the closest relay
                    floor = self.relay_df.loc["floor", temp_df.loc[0, "relay"]]
                else:
                    # Otherwise taking the mean floor + rounding it
                    floor = np.around(np.mean(self.relay_df.loc["floor", temp_relay]))

                # Add the computed coordinates to the beacon's history
                self.coordinates_history.update_coordinates_history(
                    mac, (np.mean(lat), np.mean(long))
                )

                # Use the weighted moving average for smoothing coordinates computation
                (
                    weighted_latitude,
                    weighted_longitude,
                ) = self.coordinates_history.weighted_moving_average(mac)

                coordinates.append(
                    (
                        mac,
                        beacon_data["battery"],
                        self.TO_REPAIR
                        if beacon_data["status"]
                        == self.MOVEMENT_DETECTED_AND_BUTTON_PRESSED
                        else "available",
                        weighted_latitude,
                        weighted_longitude,
                        floor,
                        beacon_data["temperature"],
                    )
                )

                del temp_df

                logger.info("Triangulation done")
            elif len(temp_df) == 1:
                logger.info("Beacon '{}' detected by only one relay, skipping!", mac)
            else:
                logger.info("Beacon '{}' not detected by any relay, skipping!", mac)

        if coordinates:
            await self._store_beacons_data(company, coordinates)

        # Garbage collect
        del df_beacons
        del averaged
        del relay
        del updated_beacons
        del coordinates
        gc.collect()
