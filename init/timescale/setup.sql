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
    longitude DECIMAL(9, 6),
    FOREIGN KEY (mac) REFERENCES items(beacon)
);

SELECT create_hypertable('beacon_data', 'time');