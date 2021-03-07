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
    beacon VARCHAR(17) NOT NULL UNIQUE,
    category VARCHAR(100) NOT NULL,
    service VARCHAR(100)
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

ALTER TABLE beacon_data SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'mac'
);

SELECT add_compression_policy('beacon_data', INTERVAL '7 days');