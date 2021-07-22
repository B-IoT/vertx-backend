# Copyright (c) 2021 BioT. All rights reserved.

from .config import logger, TIMESCALE_HOST, TIMESCALE_PORT

import asyncpg
from collections import defaultdict
from typing import DefaultDict, Tuple, List


import math
import numpy as np
from pykalman import KalmanFilter #https://pykalman.github.io/
import pickle


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
            # Remove oldest, to keep history's length to MAX_HISTORY_SIZE
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
        from 1 to MAX_HISTORY_SIZE.
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
        self.db_pool = await asyncpg.create_pool(
            host=TIMESCALE_HOST,
            port=TIMESCALE_PORT,
            database="biot",
            user="biot",
            password="biot",
        )
        self.coordinates_history = _CoordinatesHistory()
        
        self.nb_beacons = 10
        self.nb_relays = 10
        self.max_history = 30        
        
        self.temp_raw = np.zeros([self.nb_beacons, self.nb_relays])
        self.temp_raw[:] = np.nan
        
        self.matrix_raw = np.zeros([self.nb_beacons, self.nb_relays, self.max_history])
        self.matrix_raw = np.random.rand(self.nb_beacons, self.nb_relays, self.max_history) ## REplace 1 by np.nan
        
        self.initial_value_guess = np.empty([self.nb_beacons, self.nb_relays])
        self.initial_value_guess[:] = 3
        
        self.relay_mapping = {}
        self.beacon_mapping = {}
        self.inv_beacon_mapping = {}
        
        self.matrix_dist = np.empty([self.nb_beacons, self.nb_relays])
        self.matrix_dist[:] = 3

        return self

    def _get_table_name(self, company: str) -> str:
        """
        Gets the beacon_data table name for the given company.
        """
        return f"beacon_data_{company}" if company != "biot" else "beacon_data"

    async def _store_beacons_data(self, company: str, data: list):
        """
        Stores the beacons' data in TimescaleDB.
        Data must be an array of tuples of the following form: ("aa:aa:aa:aa:aa:aa", 10, "available", 2.3, 3.2, 1, 25).
        """
        async with self.db_pool.acquire() as conn:
            table_name = self._get_table_name(company)
            stmt = await conn.prepare(self.INSERT_QUERY(table_name))
            await stmt.executemany(data)
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

            if beacon["beaconstatus"] != status:
                new_beacon = (
                    beacon["mac"],
                    beacon["battery"],
                    status,
                    beacon["latitude"],
                    beacon["longitude"],
                    beacon["floor"],
                    beacon["temperature"],
                )

                stmt = await conn.prepare(self.INSERT_QUERY(table_name))
                await stmt.executemany([new_beacon])
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
        return np.array([np.ones(len(X)), X, X**2, X**3, X**4, X**5]).transpose()
                     
    async def triangulate(self, relay_id: str, data: dict):
        """
        Triangulates all beacons detected by the given relay, if enough information is available.
        """
        
        #Importing the scaler model
        filename = 'src/db_to_m_scaler.sav'
        scaler = pickle.load(open(filename, 'rb'))
        
        #Importing the ML model
        filename = 'src/db_to_m_Kalman+GBR.sav'
        reg_kalman = pickle.load(open(filename, 'rb'))

        measured_ref = -64
        tx = 6
        max_history = 30 #Number of data hsitory to keep 
        
        #Convert meters to db -nominal case, should be replaced by a ML approach
        meters_to_db = lambda x: measured_ref - 10*tx*math.log10(x)

        #Import the data
        relay_data = [
            data["latitude"],
            data["longitude"],
            int(data["floor"]),
        ]
        
        company = data["company"]
        beacons = data["beacons"]#includes the data from the MQTT    
        
        
        ##Create the matrix with the relay data
        #Create the mapping of the relays
        if relay_id not in self.relay_mapping:
            self.relay_mapping[relay_id] = len(self.relay_mapping)
                
        relay_index = self.relay_mapping[relay_id]
        
        if self.relay_matrix is None:
            self.relay_matrix = relay_data
                
        elif (relay_data[0] and relay_data[1]) not in self.relay_matrix:
            self.relay_matrix = np.stack((self.relay_matrix, relay_data), axis=0)
        
        
        ##Create the matrix with the beacon data 
        # Filter out empty arrays
        if not beacons:
            logger.warning("No beacon detected, skipping!")
            return
        
        # Filter out empty MAC addresses
        macs, rssis = zip(
            *[(beacon["mac"], beacon["rssi"]) for beacon in beacons if beacon["mac"]]
        )
        
        #Create the mapping of the beacons
        for mac in macs:
            if mac not in self.beacon_mapping:
                self.beacon_mapping[mac] = len(self.beacon_mapping)
                
                #Calculate the inverse of the mapping
                for k in self.beacon_mapping.keys(): 
                    self.inv_beacon_mapping[self.beacon_mapping[k]]= k
                
        #Get the indexes from of the mapping
        beacon_indexes = list(map(self.beacon_mapping.get, macs))
        
        #For each beacon we add it to our temporary connectivity matrix
        logger.info(
                    "beacon_indexes: {}",
                    beacon_indexes
                )
        
        for i in range (len(beacon_indexes)):
            beacon_number_temp = beacon_indexes[i]

            if not np.isnan(self.temp_raw[beacon_number_temp, relay_index]):  
                logger.info("caca")
                self.matrix_raw = np.dstack((self.temp_raw, self.matrix_raw))
                self.temp_raw[:] = np.nan
                
            self.temp_raw[beacon_number_temp, relay_index] = rssis[i]
        
        #number of historic values we want to keep
        self.matrix_raw = self.matrix_raw[:,:,0:max_history]
        
        #Starting the filtering job
        #initial value guess with the nominal model at 1m 
        self.initial_value_guess[beacon_indexes, relay_index] = np.array(list(map(meters_to_db, self.matrix_dist[beacon_indexes, relay_index].flatten()))).reshape(len(beacon_indexes))
        self.matrix_dist[:] = np.nan
        
        #Variance of the signal (per beacon/relay)
        var = np.nanvar(self.matrix_raw[beacon_indexes, relay_index,:])
        observation_covariance = var ** 2
        
        #Flattening the distance matrix
        matrix_dist_loc = self.matrix_dist[beacon_indexes, relay_index].flatten()
    
        
        for i in range (0,np.size(var)):
        
            
            kf = KalmanFilter(
                initial_state_mean = self.initial_value_guess.flatten()[i],
                initial_state_covariance = observation_covariance.flatten()[i],
                observation_covariance = observation_covariance.flatten()[i]
            )
            temp = self.matrix_raw[beacon_indexes, relay_index].reshape(len(beacon_indexes), max_history)
            temp = np.flip(temp[i,:])
            temp,_ = kf.smooth(temp[~np.isnan(temp)])
            temp = self._feature_augmentation(temp[-1])
            logger.info(
                    "feature augmentation: {}",
                    temp
                )
            temp = scaler.transform(np.array(temp).reshape(1, -1))
            logger.info(
                    "feature normalisation: {}",
                    temp
                )
            matrix_dist_loc[i] = reg_kalman.predict(np.array(temp).reshape(1, -1))/100
            logger.info(
                        "matrix dist loc  {}",
                        matrix_dist_loc
                        )
        self.matrix_dist[beacon_indexes, relay_index] = matrix_dist_loc.reshape(len(beacon_indexes))
        
        coordinates = []
        
        for i, beacon_index in enumerate(beacon_indexes):
         
           mac = self.inv_beacon_mapping[beacon_index]
           beacon_data = next(b for b in beacons if b["mac"] == mac)
           status = beacon_data["status"]
           
           temp = self.matrix_dist[beacon_index, :]
           logger.info(
                    "temp {}",
                    temp
                )
           
           relay_indexes = np.argwhere(~np.isnan(temp)).flatten()
           relay_indexes = temp[relay_indexes].argsort()
           
           nb_relays = len(relay_indexes)
           
           lat = []
           long = []
           if nb_relays > 2:               
               
               #Taking only the 5 closest relays for triangulation
               if nb_relays > 5:
                   nb_relays = 5
                   
               for relay_1 in range(nb_relays-1):
                    for relay_2 in range(relay_1 + 1, nb_relays):
                        
                        relay_1_index = relay_indexes[relay_1]
                        relay_2_index = relay_indexes[relay_2]
                        
                        vect_lat = self.relay_matrix[relay_2_index, 0] - self.relay_matrix[relay_1_index, 0]
                        vect_long = self.relay_matrix[relay_2_index, 1] - self.relay_matrix[relay_1_index, 1]                
                        
                        #Calculating the distance between the 2 gateways in meters
                        dist = self._lat_to_meters(
                            self.relay_matrix[relay_1_index, 0],
                            self.relay_matrix[relay_1_index, 1],
                            self.relay_matrix[relay_2_index, 0],
                            self.relay_matrix[relay_2_index, 1],
                        )
                        
                        # Applying proportionality rule from the origin on the vector to determine the position of the beacon in lat;long coord
                        # ie: x1 = x0 + (dist_beacon/dist_tot) * vector_length
                        
                        dist_1 = self.matrix_dist[beacon_index, relay_1_index]
                        
                        lat.append(
                            self.relay_matrix[relay_1_index, 0] + (dist_1 / dist) * vect_lat
                        )
                        long.append(
                            self.relay_matrix[relay_1_index, 1]
                            + (dist_1 / dist) * vect_long
                        )
                        
               floor = np.mean(self.relay_matrix[relay_indexes[0:3], 2])
               
               if (floor - np.floor(floor)) - 0.5 <= 1e-5:
                    # Case where we're in the middle, eg: floor = 1.5
                    # Taking the floor of the closest relay
                    floor = self.relay_matrix[relay_indexes[0], 2]
               else:
                    # Otherwise taking the mean floor + rounding it
                    floor = np.around(np.mean(self.relay_matrix[relay_indexes[0:3], 2]))

               floor = np.mean(self.relay_matrix[relay_indexes[0:3], 2])              
             
               #SMA            
               self.coordinates_history.update_coordinates_history(
                    mac, (np.mean(lat), np.mean(long))
               )
               
               # Use the weighted moving average for smoothing coordinates computation
               (
                    weighted_latitude,
                    weighted_longitude,
               ) = self.coordinates_history.weighted_moving_average(mac)

               new_beacon_status = (
                    self.TO_REPAIR
                    if status == self.MOVEMENT_DETECTED_AND_BUTTON_PRESSED
                    else self.AVAILABLE
               )
               
               coordinates.append(
                    (
                        mac,
                        beacon_data["battery"],
                        new_beacon_status,
                        weighted_latitude,  #     ,    np.mean(lat)   weighted_latitude
                        weighted_longitude,  #     ,np.mean(long) weighted_longitude
                        floor,
                        beacon_data["temperature"],
                    )
                )
               
               status_updated_message = (
                    ""
                    if status != self.MOVEMENT_DETECTED_AND_BUTTON_PRESSED
                    else f" and status updated to '{self.TO_REPAIR}'"
               )
               logger.info("Triangulation done{}", status_updated_message)

           elif 1 <= nb_relays and nb_relays <= 2:
                if status == self.MOVEMENT_DETECTED_AND_BUTTON_PRESSED:
                    # Even if we didn't triangulate, we still need to update the status
                    await self._update_beacon_status(company, mac, self.TO_REPAIR)

                logger.warning(
                    "Beacon '{}' detected by {} relay, skipping!", mac, nb_relays
                )
            
           else:
                logger.warning("Beacon '{}' not detected by any relay, skipping!", mac)
                
           if coordinates:
                await self._store_beacons_data(company, coordinates)          