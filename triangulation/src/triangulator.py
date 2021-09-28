# Copyright (c) 2021 BioT. All rights reserved.

from .config import logger, TIMESCALE_HOST, TIMESCALE_PORT

import asyncpg
from asyncio import gather
from collections import defaultdict
from typing import DefaultDict, Tuple, List


import numpy as np
from scipy.spatial.distance import cdist, euclidean
from pykalman import KalmanFilter  # https://pykalman.github.io/
from google.cloud import storage
import joblib


class _BeaconData:
    def __init__(
        self,
        mac,
        latitude,
        longitude,
        floor,
        beacon_status=None,
        temperature=-256,
        battery=-1,
    ):
        self.mac = mac
        self.beacon_status = beacon_status
        self.latitude = latitude
        self.longitude = longitude
        self.floor = floor
        self.temperature = temperature
        self.battery = battery

    def __str__(self):
        return f"BeaconData(mac={self.mac}, beacon_status={self.beacon_status}, latitude={self.latitude}, longitude={self.longitude}, floor={self.floor}, temperature={self.temperature}, battery={self.battery})"

    def __repr__(self):
        return f"BeaconData(mac={self.mac}, beacon_status={self.beacon_status}, latitude={self.latitude}, longitude={self.longitude}, floor={self.floor}, temperature={self.temperature}, battery={self.battery})"


class _CoordinatesHistory:
    """
    Coordinates history used for weighted moving average.
    """

    MAX_HISTORY_SIZE = 10

    def __init__(self):
        self.history_per_beacon: DefaultDict[
            str, List[Tuple[float, float]]
        ] = defaultdict(list)
        self.weights = self.build_weights_dict()

    def update_coordinates_history(
        self, beacon: str, new_coordinates: Tuple[float, float]
    ):
        """
        Updates the coordinates history of the given beacon with the given new coordinates.
        """
        history = self.history_per_beacon[beacon]
        size = len(history)
        if size == self.MAX_HISTORY_SIZE:
            # Remove oldest, to keep history's length to MAX_HISTORY_SIZE
            history.pop()

        # Insert the element to the head of the list
        history.insert(0, new_coordinates)

    def compute_weights(self, length: int):
        """
        Computes the weights to be used for the moving average.
        """
        denom = (length * (length + 1)) / 2
        return np.array([n / denom for n in range(length, 0, -1)])

    def build_weights_dict(self):
        """
        Builds the dictionary of the weights, with keys (corresponding to the length of the coordinates history)
        from 1 to MAX_HISTORY_SIZE.
        """
        return {i: self.compute_weights(i) for i in range(1, self.MAX_HISTORY_SIZE + 1)}

    def geometric_median(self, beacon: str, eps=1e-5):
        """
        Computes the geometric median of the saved coordinates of the given beacon
        """
        coordinates_history = self.history_per_beacon[beacon]
        X = np.array(coordinates_history)
        y = np.mean(X, 0)

        while True:
            D = cdist(X, [y])
            nonzeros = (D != 0)[:, 0]

            Dinv = 1 / D[nonzeros]
            Dinvs = np.sum(Dinv)
            W = Dinv / Dinvs
            T = np.sum(W * X[nonzeros], 0)

            num_zeros = len(X) - np.sum(nonzeros)
            if num_zeros == 0:
                y1 = T
            elif num_zeros == len(X):
                return y
            else:
                R = (T - y) * Dinvs
                r = np.linalg.norm(R)
                rinv = 0 if r == 0 else num_zeros / r
                y1 = max(0, 1 - rinv) * T + min(1, rinv) * y

            if euclidean(y, y1) < eps:
                return y1

            y = y1

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
    AVAILABLE = "available"

    INSERT_QUERY = staticmethod(
        lambda table: f"""INSERT INTO {table} (time, mac, battery, beaconstatus, latitude, longitude, floor, temperature) VALUES (NOW(), $1, $2, $3, $4, $5, $6, $7);"""
    )
    FETCH_BEACON_QUERY = staticmethod(
        lambda table: f"""SELECT * from {table} WHERE mac = $1 ORDER BY time DESC LIMIT 1;"""
    )

    @classmethod
    async def create(cls):
        # We need to use this because it is impossible to call await inside __init__()
        self = Triangulator()
        # Data structures
        self.relay_matrix = None
        self.relay_matrix_name = None
        self.db_pool = await asyncpg.create_pool(
            host=TIMESCALE_HOST,
            port=TIMESCALE_PORT,
            database="biot",
            user="biot",
            password="biot",
        )
        self.coordinates_history = _CoordinatesHistory()

        self.nb_beacons = 25
        self.nb_relays = 25

        self.filter_size_preproc = 60  # 30#60
        self.filter_size_postproc = 30  # 15#30

        self.max_history = max(self.filter_size_postproc, self.filter_size_preproc)

        self.var_coeff_preproc = 15
        self.var_coeff_postproc = 10

        self.temp_raw = np.zeros([self.nb_beacons, self.nb_relays])
        self.temp_raw[:] = np.nan

        self.matrix_raw = np.zeros([self.nb_beacons, self.nb_relays, self.max_history])
        self.matrix_raw[:] = np.nan

        self.matrix_rssi_kalman = np.zeros(
            [self.nb_beacons, self.nb_relays, self.max_history]
        )
        self.matrix_rssi_kalman[:] = np.nan

        self.initial_value_guess = np.empty([self.nb_beacons, self.nb_relays])
        self.initial_value_guess[:] = 3

        self.relay_mapping = {}
        self.beacon_mapping = {}
        self.inv_beacon_mapping = {}

        self.matrix_dist = np.empty([self.nb_beacons, self.nb_relays, self.max_history])
        self.matrix_dist[:] = np.nan

        # The last beacon data is used when sentinel values are received for some fields
        self.last_beacon_data_per_beacon = {}

        # self.kalman = KalmanFilter(0.008, 1)

        # Importing the scaler model
        with open("src/scaler.sav", "rb") as scaler_file:
            self.scaler = joblib.load(scaler_file)

        # Importing the ML model
        with open("src/gbr_compressed.gz", "rb") as reg_kalman_file:
            self.reg_kalman = joblib.load(reg_kalman_file)

        # client = storage.Client(project="dark-mark-304414")
        # bucket = client.get_bucket("biot-models")

        # scaler_blob = storage.Blob("scaler.sav", bucket)
        # rfr_blob = storage.Blob("rfr_compressed.gz", bucket)
        # # Download the scaler model and ML model
        # with open("scaler.sav", "wb+") as scaler_file, open(
        #     "rfr_compressed.gz", "wb+"
        # ) as rfr_file:
        #     client.download_blob_to_file(scaler_blob, scaler_file)
        #     client.download_blob_to_file(rfr_blob, rfr_file)

        # # Load the scaler model and ML model
        # with open("scaler.sav", "rb") as scaler_file, open(
        #     "rfr_compressed.gz", "rb"
        # ) as rfr_file:
        #     self.scaler = joblib.load(scaler_file)
        #     self.reg_kalman = joblib.load(rfr_file)

        return self

    def _get_table_name(self, company: str) -> str:
        """
        Gets the beacon_data table name for the given company.
        """
        return f"beacon_data_{company}" if company != "biot" else "beacon_data"

    def _weighted_mean(self, values):
        """
        Calculates a weighted mean for the triangulation to triangulate to the closest relay

        Input: list of latitudes and longitudes by ascending order with respect to the closest relay
        Returns: unique latitude and longitude
        """
        # weight_2 = [
        #     0.7,
        #     0.3,
        # ]  # pushing towards the closest relay; values = [position of the relay, position of the beacon]
        # if len(values) == 2:  # nb relays = 2
        #     mean_weighted = np.sum([a * b for a, b in zip(weight_2, values)])

        weight_3 = [0.4, 0.4, 0.2]
        if len(values) == 3:  # nb relays = 3
            mean_weighted = np.sum([a * b for a, b in zip(weight_3, values)])

        weight_4 = [0.25, 0.25, 0.25, 0.1, 0.1, 0.05]
        if len(values) == 6:  # nb relays = 4
            mean_weighted = np.sum([a * b for a, b in zip(weight_4, values)])

        weight_5 = [0.175, 0.175, 0.175, 0.175, 0.06, 0.06, 0.06, 0.05, 0.05, 0.02]
        if len(values) == 10:  # nb relays = 5
            mean_weighted = np.sum([a * b for a, b in zip(weight_5, values)])

        return mean_weighted

    async def _insert_beacon_data(self, table_name: str, beacon_data: _BeaconData):
        """
        Inserts the beacon data in the DB.
        """
        async with self.db_pool.acquire() as connection:
            query = self.INSERT_QUERY(table_name)

            last_beacon_data = (
                self.last_beacon_data_per_beacon[beacon_data.mac]
                if beacon_data.mac in self.last_beacon_data_per_beacon
                else None
            )

            battery = None
            if beacon_data.battery != -1:
                battery = beacon_data.battery
            elif (
                last_beacon_data
                and last_beacon_data.battery is not None
                and last_beacon_data.battery != -1
            ):
                battery = last_beacon_data.battery

            beacon_status = None
            if beacon_data.beacon_status != -1:
                beacon_status = beacon_data.beacon_status
            elif (
                last_beacon_data
                and last_beacon_data.beacon_status is not None
                and last_beacon_data.beacon_status != -1
            ):
                beacon_status = last_beacon_data.beacon_status

            temperature = None
            if beacon_data.temperature != -256:
                temperature = beacon_data.temperature
            elif (
                last_beacon_data
                and last_beacon_data.temperature is not None
                and last_beacon_data.temperature != -256
            ):
                temperature = last_beacon_data.temperature

            data = [
                beacon_data.mac,
                battery,
                beacon_status,
                beacon_data.latitude,
                beacon_data.longitude,
                beacon_data.floor,
                temperature,
            ]

            self.last_beacon_data_per_beacon[beacon_data.mac] = _BeaconData(
                beacon_data.mac,
                beacon_data.latitude,
                beacon_data.longitude,
                beacon_data.floor,
                beacon_status,
                temperature,
                battery,
            )

            stmt = await connection.prepare(query)
            return await stmt.executemany([tuple(data)])

    async def _store_beacons_data(self, company: str, data: List[_BeaconData]):
        """
        Stores the beacons' data in TimescaleDB.
        """
        table_name = self._get_table_name(company)
        # Store the data in parallel
        await gather(
            *[self._insert_beacon_data(table_name, beacon_data) for beacon_data in data]
        )

        logger.info("New beacons' data inserted in DB '{}': {}", table_name, data)

    async def _update_beacon_status(self, company: str, mac: str, status: str):
        """
        Updates the status of the given beacon.
        """
        async with self.db_pool.acquire() as conn:
            table_name = self._get_table_name(company)

            fetch_stmt = await conn.prepare(self.FETCH_BEACON_QUERY(table_name))
            beacon = await fetch_stmt.fetchrow(mac)

            if not beacon:
                logger.warning(
                    "Skipping status update for beacon '{}', as it does not exist.", mac
                )
                return

            beacon_status = beacon["beaconstatus"]
            if beacon_status != status:
                beacon_data = _BeaconData(
                    beacon["mac"],
                    beacon["latitude"],
                    beacon["longitude"],
                    beacon["floor"],
                    status,
                    beacon["temperature"],
                    beacon["battery"],
                )
                await self._insert_beacon_data(table_name, beacon_data)

                self.last_beacon_data_per_beacon[mac] = beacon_data

                logger.info(
                    "Updated beacon '{}' status in DB '{}' to '{}'",
                    mac,
                    table_name,
                    status,
                )
            else:
                logger.warning(
                    "Skipping status update for beacon '{}', as it has already status '{}' in DB '{}'",
                    mac,
                    status,
                    table_name,
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

    def _feature_augmentation(self, X):
        return np.array([X, X ** 2, X ** 3, X ** 4, X ** 5]).transpose()

    def _preprocessing(self, beacon_indexes, relay_index, max_history):
        measured_ref = -65
        tx = 6

        def meters_to_db(dist):
            return measured_ref - 10 * tx * np.log10(dist)

        matrix_dist_temp = self.matrix_dist[:, :, 0]
        matrix_rssi_kalman = self.matrix_rssi_kalman[:, :, 0]

        matrix_dist_temp[:] = np.nan

        var = self.var_coeff_preproc * 83.13
        observation_covariance = np.diag([var]) ** 2

        indexes = tuple(
            np.argwhere(~np.isnan(self.matrix_raw[:, :, 0]))
        )  # Indexes of beacon/relay pairs

        for index in indexes:
            index = tuple(index)  # Converting to the right format

            kf = KalmanFilter(
                initial_state_mean=matrix_rssi_kalman[index],
                initial_state_covariance=observation_covariance,
                observation_covariance=observation_covariance,
            )

            temp = self.matrix_raw[
                index[0], index[1], 0 : self.filter_size_preproc
            ]  # Matrix of 1 x filter_size_preproc

            temp = np.flip(temp)  # Flipping to be in the right format for Kalman
            temp = temp[~np.isnan(temp)]  # Removing all nan
            if temp.shape[0] > 1 and not np.isnan(
                matrix_rssi_kalman[index]
            ):  # Checking we have more than 1 value
                temp, _ = kf.smooth(temp)

            matrix_rssi_kalman[index] = temp[-1]
            self.matrix_rssi_kalman = np.dstack(
                (matrix_rssi_kalman, self.matrix_rssi_kalman)
            )
            self.matrix_rssi_kalman = self.matrix_rssi_kalman[:, :, 0:max_history]

            temp = self._feature_augmentation(
                temp[-1]
            )  # Taking the latest RSSI and augmenting it
            temp = self.scaler.transform(np.array(temp).reshape(1, -1))  # Normalizing
            temp = np.concatenate(([1], temp.flatten()))

            dist_estimated = self.reg_kalman.predict(np.array(temp).reshape(1, -1))
            matrix_dist_temp[index] = (
                dist_estimated / 100 if dist_estimated <= 55 else dist_estimated / 250
            )
            logger.info("Meters after preproc and ML = {}", matrix_dist_temp[index])

        if len(indexes) > 0:
            # Stack matrix_dist_temp onto matrix_dist
            self.matrix_dist = np.dstack((matrix_dist_temp, self.matrix_dist))
            self.matrix_dist = self.matrix_dist[:, :, 0:max_history]

        indexes = tuple(
            np.argwhere(~np.isnan(self.matrix_dist[:, :, 0]))
        )  # Indexes of beacon/relay pairs
        initial_value_guess_dist = self.matrix_dist[:, :, 0]

        var_dist = self.var_coeff_postproc * 1034.92
        observation_covariance_dist = np.diag([var_dist]) ** 2

        for index in indexes:
            # Converting to the right format
            index = tuple(index)

            kf = KalmanFilter(
                initial_state_mean=initial_value_guess_dist[index],
                initial_state_covariance=observation_covariance_dist,
                observation_covariance=observation_covariance_dist,
            )

            temp = self.matrix_dist[
                index[0], index[1], 0 : self.filter_size_postproc
            ]  # Matrix of 1 x filter_size_dist
            temp = np.flip(temp)  # Flipping to be in the right format for Kalman
            temp = temp[~np.isnan(temp)]  # Removing all nan
            if temp.shape[0] > 1 and not np.isnan(
                initial_value_guess_dist[index]
            ):  # Checking we have more than 1 value
                temp, _ = kf.smooth(temp)

            smooth_dist = temp[-1]
            self.matrix_dist[index[0], index[1], 0] = smooth_dist

        return

    def _cache_battery_and_temperature(self, mac: str, battery: int, temperature: int):
        last_beacon_data = (
            self.last_beacon_data_per_beacon[mac]
            if mac in self.last_beacon_data_per_beacon
            else None
        )

        if (
            battery == -1
            and last_beacon_data
            and last_beacon_data.battery is not None
            and last_beacon_data.battery != -1
        ):
            battery = last_beacon_data.battery

        if (
            temperature == -256
            and last_beacon_data
            and last_beacon_data.temperature is not None
            and last_beacon_data.temperature != -256
        ):
            temperature = last_beacon_data.temperature

        if last_beacon_data:
            self.last_beacon_data_per_beacon[mac] = _BeaconData(
                mac,
                last_beacon_data.latitude,
                last_beacon_data.longitude,
                last_beacon_data.floor,
                last_beacon_data.beacon_status,
                temperature,
                battery,
            )
        else:
            self.last_beacon_data_per_beacon[mac] = _BeaconData(
                mac,
                None,
                None,
                None,
                None,
                temperature,
                battery,
            )

    async def _triangulation_engine(self, beacon_indexes, beacons, company):
        coordinates = []
        
        logger.info("beacon_indexes: {}", beacon_indexes)
        for beacon_index in beacon_indexes:
            mac = self.inv_beacon_mapping[beacon_index]
            beacon_data = next(b for b in beacons if b["mac"] == mac)
            status = beacon_data["status"]

            # Store the temperature and the battery, they need to be cached even if the triangulation does not start
            self._cache_battery_and_temperature(
                mac, beacon_data["battery"], beacon_data["temperature"]
            )

            temp = np.array(self.matrix_dist)[beacon_index, :, 0]
            
            logger.info("temp: {}", temp)
            logger.info("matrix_dist: {}", self.matrix_dist)
            
            relay_indexes = np.argwhere(~np.isnan(temp)).flatten()
            logger.info("relay_indexes for temp no nan: {}", relay_indexes)
            
            relay_indexes = temp[relay_indexes].argsort()
            logger.info("relay_indexes for temp sorted: {}", relay_indexes)
            
            nb_relays = len(relay_indexes)

            logger.info(
                "Starting triangulation for beacon: {}, with: {} relays", mac, nb_relays
            )

            lat = []
            long = []
            if nb_relays >= 3:
                # distances = self.matrix_dist[beacon_index, relay_indexes, 0]
                # not_nan_distances_indexes = np.argwhere(~np.isnan(distances)).flatten()
                # distances = distances[not_nan_distances_indexes]
                # relays_coords = np.array([(self.relay_matrix[index, 0], self.relay_matrix[index, 1]) for index in relay_indexes])
                # relays_coords = relays_coords[not_nan_distances_indexes]

                # Taking only the 5 closest relays for triangulation
                if nb_relays > 5:
                    nb_relays = 5

                for relay_1 in range(nb_relays - 1):
                    logger.info("relay_1_index: {}", relay_indexes[relay_1])
                    
                    logger.info("matrix_dist: {}", self.self.matrix_dist[beacon_index, :, 0])
                    
                    logger.info("matrix_dist: {}", self.self.matrix_dist[beacon_index, relay_1, 0])
                    
                    for relay_2 in range(relay_1 + 1, nb_relays):
                        relay_1_index = relay_indexes[relay_1]
                        
                        relay_2_index = relay_indexes[relay_2]
                        vect_lat = (
                            self.relay_matrix[relay_2_index, 0]
                            - self.relay_matrix[relay_1_index, 0]
                        )
                        vect_long = (
                            self.relay_matrix[relay_2_index, 1]
                            - self.relay_matrix[relay_1_index, 1]
                        )

                        # Calculating the distance between the 2 gateways in meters
                        dist = self._lat_to_meters(
                            self.relay_matrix[relay_1_index, 0],
                            self.relay_matrix[relay_1_index, 1],
                            self.relay_matrix[relay_2_index, 0],
                            self.relay_matrix[relay_2_index, 1],
                        )
                        
                        logger.info(
                            "dist: {}", dist
                        )

                        # Applying proportionality rule from the origin on the vector to determine the position of the beacon in lat;long coord
                        # ie: x1 = x0 + (dist_beacon/dist_tot) * vector_length

                        dist_1 = self.matrix_dist[beacon_index, relay_1_index, 0]
                        
                        
                        logger.info(
                            "dist_1: {}", dist_1
                        )

                        lat.append(
                            self.relay_matrix[relay_1_index, 0]
                            + (dist_1 / dist) * vect_lat
                        )
                        long.append(
                            self.relay_matrix[relay_1_index, 1]
                            + (dist_1 / dist) * vect_long
                        )

                floor = np.mean(self.relay_matrix[0:3, 2])

                if (floor - np.floor(floor)) - 0.5 <= 1e-5:
                    # Case where we're in the middle, eg: floor = 1.5
                    # Taking the floor of the closest relay
                    floor = self.relay_matrix[0, 2]
                else:
                    # Otherwise taking the mean floor + rounding it
                    floor = np.around(np.mean(self.relay_matrix[0:3, 2]))

                floor = np.mean(self.relay_matrix[0:3, 2])

                # Doing a weighted mean and then pushing towards the closest relay
                # new_lat = self._weighted_mean([self.relay_matrix[relay_indexes[0], 0] , np.mean(lat)])
                # new_long = self._weighted_mean([self.relay_matrix[relay_indexes[0], 1] , np.mean(long)])
                new_lat = self._weighted_mean(lat)
                new_long = self._weighted_mean(long)
                # new_lat = np.mean(lat)
                # new_long = np.mean(long)

                new_beacon_status = (
                    self.TO_REPAIR
                    if status == self.MOVEMENT_DETECTED_AND_BUTTON_PRESSED
                    else self.AVAILABLE
                )

                if not np.isnan(new_lat) and not np.isnan(new_long):
                    self.coordinates_history.update_coordinates_history(
                        mac, (new_lat, new_long)
                    )

                    # # Use the weighted moving average for smoothing coordinates computation
                    # (
                    #     weighted_latitude,
                    #     weighted_longitude,
                    # ) = self.coordinates_history.weighted_moving_average(mac)

                    # Use the geometric median for smoothing coordinates computation and removing outliers
                    (
                        weighted_latitude,
                        weighted_longitude,
                    ) = self.coordinates_history.geometric_median(mac)

                    data = _BeaconData(
                        mac,
                        weighted_latitude,  #     ,    np.mean(lat)   weighted_latitude
                        weighted_longitude,  #     ,np.mean(long) weighted_longitude
                        floor,
                        new_beacon_status,
                        beacon_data["temperature"],
                        beacon_data["battery"],
                    )
                    coordinates.append(data)

                status_updated_message = (
                    ""
                    if status != self.MOVEMENT_DETECTED_AND_BUTTON_PRESSED
                    else f" and status updated to '{self.TO_REPAIR}'"
                )
                logger.info("Triangulation done{}", status_updated_message)

            elif 1 <= nb_relays and nb_relays <= 2:
                if (
                    status == self.MOVEMENT_DETECTED_AND_BUTTON_PRESSED
                    or status == self.BUTTON_PRESSED
                ):
                    # Even if we didn't triangulate, we still need to update the status
                    await self.update_beacon_status(company, mac, self.TO_REPAIR)

                logger.warning(
                    "Beacon '{}' detected by {} relay, skipping!", mac, nb_relays
                )

            else:
                logger.warning("Beacon '{}' not detected by any relay, skipping!", mac)

        return coordinates

    async def triangulate(self, relay_id: str, data: dict):
        """
        Triangulates all beacons detected by the given relay, if enough information is available.
        """
        # Import the data
        relay_data = [data["latitude"], data["longitude"], data["floor"]]

        relay_data = np.array(relay_data).astype(np.float64)
        relay_name = data["relayID"]

        company = data["company"]
        beacons = data["beacons"]  # Includes the data from the MQTT
        coordinates = []

        # Create the mapping of the relays [relay name, relay_int_identifier]
        if relay_id not in self.relay_mapping:
            self.relay_mapping[relay_id] = len(self.relay_mapping)

        relay_index = self.relay_mapping[relay_id]

        # Create the matrix with the relay data [latitude, longitude, floor]
        if self.relay_matrix is None:
            self.relay_matrix = np.array(relay_data).reshape(1, len(relay_data))
            self.relay_matrix_name = np.array(relay_name).reshape(1, 1)
        elif relay_name not in self.relay_matrix_name:
            self.relay_matrix = np.concatenate(
                (self.relay_matrix, np.array(relay_data).reshape(1, len(relay_data))),
                axis=0,
            )
            self.relay_matrix_name = np.concatenate(
                (self.relay_matrix_name, np.array(relay_name).reshape(1, 1)), axis=0
            )
        elif relay_name in self.relay_matrix_name and (
            self.relay_matrix[relay_index][0] != relay_data[0]
            or self.relay_matrix[relay_index][1] != relay_data[1]
            or self.relay_matrix[relay_index][2] != relay_data[2]
        ):
            # It means that the latitude/longitude/floor changed for this relay --> update
            self.relay_matrix[relay_index] = relay_data

        # Exit if the relay did not detect any beacon
        if not beacons:
            logger.warning("No beacon detected, skipping!")
            return

        # Filter out empty MAC addresses
        macs, rssis = zip(
            *[(beacon["mac"], beacon["rssi"]) for beacon in beacons if beacon["mac"]]
        )

        # Create the mapping of the beacons [beacon mac, beacon_int_identifier]
        for mac in macs:
            if mac not in self.beacon_mapping:
                self.beacon_mapping[mac] = len(self.beacon_mapping)

                # Calculate the inverse of the mapping
                for k in self.beacon_mapping.keys():
                    self.inv_beacon_mapping[self.beacon_mapping[k]] = k

        # Convert the mac list to the beacon_int_identifier from of the mapping
        beacon_indexes = list(map(self.beacon_mapping.get, macs))

        # We add each beacon to our temporary connectivity matrix
        for i in range(len(beacon_indexes)):
            beacon_number_temp = beacon_indexes[i]

            if not np.isnan(self.temp_raw[beacon_number_temp, relay_index]):
                self.matrix_raw = np.dstack((self.temp_raw, self.matrix_raw))
                # Number of historic values we want to keep
                self.matrix_raw = self.matrix_raw[:, :, 0 : self.max_history]

                # Starting the filtering job
                self._preprocessing(beacon_indexes, relay_index, self.max_history)

                # logger.info("Relay matrix name {}", self.relay_matrix_name)
                # logger.info("Matrix dist {}", self.matrix_dist[:, :, 0])

                coordinates = await self._triangulation_engine(
                    beacon_indexes, beacons, company
                )
                self.temp_raw[:] = np.nan

            self.temp_raw[beacon_number_temp, relay_index] = rssis[
                i
            ]  # self._improve_rssi(rssis[i])

        if coordinates:
            await self._store_beacons_data(company, coordinates)

    def _improve_rssi(self, rssi):
        if rssi <= 65:
            return rssi
        elif 65 < rssi and rssi <= 70:
            return rssi - 6
        elif 70 < rssi and rssi <= 75:
            return rssi - 15
        elif 75 < rssi and rssi <= 85:
            return rssi - 15
        else:
            return rssi - 15
