# Copyright (c) 2021 BioT. All rights reserved.

from .config import logger, TIMESCALE_HOST, TIMESCALE_PORT

import asyncpg
from collections import defaultdict
from typing import DefaultDict, Tuple, List


import math
import numpy as np
from pykalman import KalmanFilter # https://pykalman.github.io/
import pickle


class _CoordinatesHistory:
    """
    Coordinates history used for weighted moving average.
    """

    MAX_HISTORY_SIZE = 1

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
        return {
            i: self.compute_weights(i) for i in range(1, self.MAX_HISTORY_SIZE + 1)
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

        self.filter_size_raw = 30
        self.filter_size_dist = 30

        self.max_history = max(self.filter_size_dist, self.filter_size_raw)        

        self.var_coeff_raw = 12
        self.var_coeff_dist = 10
        
        self.temp_raw = np.zeros([self.nb_beacons, self.nb_relays])
        self.temp_raw[:] = np.nan
        
        self.matrix_raw = np.zeros([self.nb_beacons, self.nb_relays, self.max_history])
        self.matrix_raw[:] = np.nan
        
        self.initial_value_guess = np.empty([self.nb_beacons, self.nb_relays])
        self.initial_value_guess[:] = 3
        
        self.relay_mapping = {}
        self.beacon_mapping = {}
        self.inv_beacon_mapping = {}

        self.matrix_dist = np.empty([self.nb_beacons, self.nb_relays, self.max_history])
        self.matrix_dist[:] = np.nan
        
        # Importing the scaler model
        filename = 'src/scaler.sav'
        self.scaler = pickle.load(open(filename, 'rb'))
        
        # Importing the ML model
        filename = 'src/Model_SVC.sav'
        self.reg_kalman = pickle.load(open(filename, 'rb'))

        return self

    def get_table_name(self, company: str) -> str:
        """
        Gets the beacon_data table name for the given company.
        """
        return f"beacon_data_{company}" if company != "biot" else "beacon_data"

    async def store_beacons_data(self, company: str, data: list):
        """
        Stores the beacons' data in TimescaleDB.
        Data must be an array of tuples of the following form: ("aa:aa:aa:aa:aa:aa", 10, "available", 2.3, 3.2, 1, 25).
        """
        async with self.db_pool.acquire() as conn:
            table_name = self.get_table_name(company)
            stmt = await conn.prepare(self.INSERT_QUERY(table_name))
            await stmt.executemany(data)
            logger.info("New beacons' data inserted in DB '{}': {}", table_name, data)

    async def update_beacon_status(self, company: str, mac: str, status: str):
        """
        Updates the status of the given beacon.
        """
        async with self.db_pool.acquire() as conn:
            table_name = self.get_table_name(company)

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

    def lat_to_meters(self, lat1, lon1, lat2, lon2):
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

    def db_to_meters(self, RSSI, measure_ref, N):
        """
        Converts dB to meters.
        """
        d = 10 ** ((measure_ref - RSSI) / (10 * N))
        return d
    
    def feature_augmentation(self, X):
        return np.array([X, X**2, X**3, X**4, X**5]).transpose()
    
    def preprocessing(self, beacon_indexes, relay_index, max_history):
        
        measured_ref = -64
        tx = 6
        meters_to_db = lambda x: measured_ref - 10*tx*math.log10(x)
        
        matrix_dist_temp = self.matrix_dist[:, :, 0]
        matrix_dist_temp_old = self.matrix_dist[:, :, 0]

        self.initial_value_guess = np.array(list(map(meters_to_db, matrix_dist_temp_old.flatten()/100))).reshape(matrix_dist_temp_old.shape)

        matrix_dist_temp[:] = np.nan
        
        # Variance of the signal (per beacon/relay)
        var = np.nanvar(self.matrix_raw[:, :, 0:self.filter_size_raw], axis = 2) # Matrix 2D
        var = self.var_coeff_raw * var
        observation_covariance = var ** 2 # Matrix 2D with var^2 
        
        indexes = tuple(np.argwhere(~np.isnan(self.matrix_raw[:,:,0]))) # Indexes of beacon/relay pairs
        
        for index in indexes:
            index = tuple(index) # Converting to the right format
            
            kf = KalmanFilter(
                initial_state_mean = self.initial_value_guess[index],
                initial_state_covariance = observation_covariance[index],
                observation_covariance = observation_covariance[index]
            )
            
            temp = self.matrix_raw[index[0], index[1], 0:self.filter_size_raw] # Matrix of 1 x filter_size_raw
            temp = np.flip(temp) # Flipping to be in the right format for Kalman
            temp = temp[~np.isnan(temp)] # Removing all nan
            if temp.shape[0] > 1 and not np.isnan(self.initial_value_guess[index]): # Checking we have more than 1 value
                temp,_ = kf.smooth(temp)
            
            temp = self.feature_augmentation(temp[-1]) # Taking the latest RSSI and augmenting it
            temp = self.scaler.transform(np.array(temp).reshape(1, -1)) # Normalizing
            temp = np.concatenate(([1], temp.flatten()))


            matrix_dist_temp[index] = self.reg_kalman.predict(np.array(temp).reshape(1, -1))/100

        if len(indexes) > 0:
            # Stack matrix_dist_temp onto matrix_dist
            np.dstack((matrix_dist_temp, self.matrix_dist))
            self.matrix_dist = self.matrix_dist[:,:,0:max_history]


        indexes = tuple(np.argwhere(~np.isnan(self.matrix_dist[:,:,0]))) # Indexes of beacon/relay pairs
        initial_value_guess_dist = self.matrix_dist[:,:,0]

        # Variance of the signal (per beacon/relay)
        var_dist = np.nanvar(self.matrix_dist[:,:,0:self.filter_size_dist], axis = 2) # Matrix 2D
        var_dist = self.var_coeff_dist * var_dist
        observation_covariance_dist = var_dist ** 2 # Matrix 2D with var^2 
        
        for index in indexes:
            index = tuple(index) # Converting to the right format
            
            kf = KalmanFilter(
                initial_state_mean = initial_value_guess_dist[index],
                initial_state_covariance = observation_covariance_dist[index],
                observation_covariance = observation_covariance_dist[index]
            )
            
            temp = self.matrix_dist[index[0], index[1], 0:self.filter_size_dist] # Matrix of 1 x filter_size_dist
            temp = np.flip(temp) # Flipping to be in the right format for Kalman
            temp = temp[~np.isnan(temp)] # Removing all nan
            if temp.shape[0] > 1 and not np.isnan(initial_value_guess_dist[index]): # Checking we have more than 1 value
                temp,_ = kf.smooth(temp)
            smooth_dist = temp[-1]
            self.matrix_dist[index[0], index[1], 0] = smooth_dist
            
        return
     
    async def triangulation_engine(self, beacon_indexes, beacons, company):
        
        coordinates = []
        
        for i, beacon_index in enumerate(beacon_indexes):
            
           mac = self.inv_beacon_mapping[beacon_index]
           beacon_data = next(b for b in beacons if b["mac"] == mac)
           status = beacon_data["status"]
           
           temp = self.matrix_dist[beacon_index, :, 0]
           
           
           relay_indexes = np.argwhere(~np.isnan(temp)).flatten()           
           relay_indexes = temp[relay_indexes].argsort()        
           
           nb_relays = len(relay_indexes)
           
           logger.info(
            "Starting triangulation for beacon: {}, with: {} relays",
                mac, nb_relays
            )
           
           lat = []
           long = []
           if nb_relays > 2:               
               
               # Taking only the 5 closest relays for triangulation
               if nb_relays > 5:
                   nb_relays = 5
                   
               for relay_1 in range(nb_relays-1):
                    for relay_2 in range(relay_1+1, nb_relays):
                        
                        relay_1_index = relay_indexes[relay_1]
                        relay_2_index = relay_indexes[relay_2]
                        vect_lat = self.relay_matrix[relay_2_index, 0] - self.relay_matrix[relay_1_index, 0]
                        vect_long = self.relay_matrix[relay_2_index, 1] - self.relay_matrix[relay_1_index, 1]
                        
                        # Calculating the distance between the 2 gateways in meters
                        dist = self.lat_to_meters(
                            self.relay_matrix[relay_1_index, 0],
                            self.relay_matrix[relay_1_index, 1],
                            self.relay_matrix[relay_2_index, 0],
                            self.relay_matrix[relay_2_index, 1],
                        )
                        
                        # Applying proportionality rule from the origin on the vector to determine the position of the beacon in lat;long coord
                        # ie: x1 = x0 + (dist_beacon/dist_tot) * vector_length
                        
                        dist_1 = self.matrix_dist[beacon_index, relay_1_index, 0]
                        
                        lat.append(
                            self.relay_matrix[relay_1_index, 0] + (dist_1 / dist) * vect_lat
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
               
               if not np.isnan(weighted_latitude) and not np.isnan(weighted_longitude):
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
                    await self.update_beacon_status(company, mac, self.TO_REPAIR)

                logger.warning(
                    "Beacon '{}' detected by {} relay, skipping!", mac, nb_relays
                )
            
           else:
                logger.warning("Beacon '{}' not detected by any relay, skipping!", mac)

        # return None
        return coordinates
                
    async def triangulate(self, relay_id: str, data: dict):
        """
        Triangulates all beacons detected by the given relay, if enough information is available.
        """

        # max_history = 30 #Number of data hsitory to keep 

        # logger.info(
        #             "Matrix dist {}",
        #             self.matrix_dist
        #         )

        # Import the data
        relay_data = [
            data["latitude"],
            data["longitude"],
            data["floor"]
        ]

        relay_data = np.array(relay_data).astype(np.float)
        relay_name = data["relayID"]
        
        company = data["company"]
        beacons = data["beacons"] # Includes the data from the MQTT    
        coordinates = []   
        
        # Create the mapping of the relays [relay name, relay_int_identifier]
        if relay_id not in self.relay_mapping:
            self.relay_mapping[relay_id] = len(self.relay_mapping)
                
        relay_index = self.relay_mapping[relay_id]
        
        # Create the matrix with the relay data [latitude, longitude, floor]
        if self.relay_matrix is None:
            self.relay_matrix = np.array(relay_data).reshape(1,len(relay_data))  
            self.relay_matrix_name = np.array(relay_name).reshape(1, 1)           
        elif relay_name not in self.relay_matrix_name :
            self.relay_matrix = np.concatenate((self.relay_matrix, np.array(relay_data).reshape(1,len(relay_data))), axis=0)
            self.relay_matrix_name = np.concatenate((self.relay_matrix_name, np.array(relay_name).reshape(1, 1)), axis=0)
        elif relay_name in self.relay_matrix_name and (self.relay_matrix[relay_index][0] != relay_data[0] or 
                                                        self.relay_matrix[relay_index][1] != relay_data[1] or 
                                                        self.relay_matrix[relay_index][2] != relay_data[2]):
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
                
                #Calculate the inverse of the mapping
                for k in self.beacon_mapping.keys(): 
                    self.inv_beacon_mapping[self.beacon_mapping[k]]= k
                
        # Convert the mac list to the beacon_int_identifier from of the mapping
        beacon_indexes = list(map(self.beacon_mapping.get, macs))
        
        # We add each beacon to our temporary connectivity matrix     
        for i in range (len(beacon_indexes)):
            beacon_number_temp = beacon_indexes[i]
            
            
            if not np.isnan(self.temp_raw[beacon_number_temp, relay_index]):
                self.matrix_raw = np.dstack((self.temp_raw, self.matrix_raw))
                # Number of historic values we want to keep
                self.matrix_raw = self.matrix_raw[:,:,0:self.max_history]

                logger.info("Relay_matrix_name before preprocessing: {}", self.relay_matrix_name)
                # Starting the filtering job
                self.preprocessing(beacon_indexes, relay_index, self.max_history)
            
                coordinates = await self.triangulation_engine(beacon_indexes, beacons, company)
                self.temp_raw[:] = np.nan
                
            self.temp_raw[beacon_number_temp, relay_index] = rssis[i]
        
        
        ### Triangulation engine
        
        # Initial value guess with the nominal model at 1m 
        if coordinates:
            logger.info(
                    "Coordinates {}",
                    coordinates
                )
            await self.store_beacons_data(company, coordinates)          