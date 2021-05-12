-- Copyright (c) 2021 BioT. All rights reserved.

CREATE USER biot WITH PASSWORD 'biot';
CREATE DATABASE biot OWNER biot;
ALTER USER biot WITH SUPERUSER;

\c biot biot;

CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS postgis;

-- Below commands should be used to create new tables for a new client, adding the company name to the tables' names
-- Do not forget to connect to the biot database first, with \c biot biot

CREATE TABLE IF NOT EXISTS items
(
    id SERIAL PRIMARY KEY,
    beacon VARCHAR(17) UNIQUE,
    category VARCHAR(100),
    service VARCHAR(100),
    itemID VARCHAR(50),
    brand VARCHAR(100),
    model VARCHAR(100),
    supplier VARCHAR(100),
    purchaseDate DATE,
    purchasePrice DECIMAL(15, 6),
    originLocation VARCHAR(100),
    currentLocation VARCHAR(100),
    room VARCHAR(100),
    contact VARCHAR(100),
    owner VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS beacon_data
(
    time TIMESTAMPTZ NOT NULL,
    mac VARCHAR(17) NOT NULL,
    battery INTEGER,
    status VARCHAR(50),
    latitude DECIMAL(9, 6),
    longitude DECIMAL(9, 6),
    floor INTEGER
);

SELECT create_hypertable('beacon_data', 'time');

CREATE INDEX ON items(beacon);

CREATE INDEX ON beacon_data(mac, time DESC);

-- Use the following commands to enable compression
-- ALTER TABLE beacon_data SET (
--     timescaledb.compress,
--     timescaledb.compress_segmentby = 'mac'
-- );
--
-- SELECT add_compression_policy('beacon_data', INTERVAL '7 days');