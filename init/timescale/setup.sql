CREATE EXTENSION IF NOT EXISTS timescaledb;

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
    longitude DECIMAL(9, 6)
);

SELECT create_hypertable('beacon_data', 'time');

CREATE INDEX IF NOT EXISTS ON items(beacon);

CREATE INDEX IF NOT EXISTS ON beacon_data(mac, time DESC);