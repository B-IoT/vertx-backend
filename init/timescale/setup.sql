-- Copyright (c) 2021 BioT. All rights reserved.

CREATE USER biot WITH PASSWORD 'biot';
CREATE DATABASE biot OWNER biot;
ALTER USER biot WITH SUPERUSER;

\c biot biot;

CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS postgis;

-- Below commands should be used to create new tables for a new client, adding the company name to the tables' names
-- after un underscore (ex: company is arsante, table name is items_arsante)
-- Do not forget to connect to the biot database first, with \c biot biot or through pgadmin

CREATE TABLE IF NOT EXISTS items
(
    id SERIAL PRIMARY KEY,
    beacon VARCHAR(17) UNIQUE,
    category VARCHAR(100),
    service VARCHAR(100),
    itemID VARCHAR(50),
    accessControlString VARCHAR(2048),
    brand VARCHAR(100),
    model VARCHAR(100),
    supplier VARCHAR(100),
    purchaseDate DATE,
    purchasePrice DECIMAL(15, 6),
    originLocation VARCHAR(100),
    currentLocation VARCHAR(100),
    room VARCHAR(100),
    contact VARCHAR(100),
    currentOwner VARCHAR(100),
    previousOwner VARCHAR(100),
    orderNumber VARCHAR(100),
    color VARCHAR(100),
    serialNumber VARCHAR(100),
    maintenanceDate DATE,
    status VARCHAR(100),
    comments VARCHAR(200),
    lastModifiedDate DATE,
    lastModifiedBy VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS items_snapshots
(
    id SERIAL PRIMARY KEY,
    snapshotDate DATE
);

CREATE TABLE IF NOT EXISTS beacon_data
(
    time TIMESTAMPTZ NOT NULL,
    mac VARCHAR(17) NOT NULL,
    battery INTEGER,
    beaconStatus VARCHAR(50),
    latitude DECIMAL(9, 6),
    longitude DECIMAL(9, 6),
    floor INTEGER,
    temperature DECIMAL(5,2)
);

SELECT create_hypertable('beacon_data', 'time');

CREATE INDEX ON items(beacon);

CREATE INDEX ON beacon_data(mac, time DESC);

-- Trigger function for notifying on insert
CREATE OR REPLACE FUNCTION notify_insert_trigger_func()
  RETURNS trigger AS
$$
BEGIN
PERFORM pg_notify('POST', '{"data":' || row_to_json(NEW)::text || ', "table":"' || TG_TABLE_NAME || '"}');
RETURN NEW;
END;
$$
LANGUAGE 'plpgsql';

-- Trigger function for notifying on update
CREATE OR REPLACE FUNCTION notify_update_trigger_func()
  RETURNS trigger AS
$$
BEGIN
PERFORM pg_notify('PUT', '{"data":' || row_to_json(NEW)::text || ', "table":"' || TG_TABLE_NAME || '"}');
RETURN NEW;
END;
$$
LANGUAGE 'plpgsql';

-- Trigger function for notifying on delete
CREATE OR REPLACE FUNCTION notify_delete_trigger_func()
  RETURNS trigger AS
$$
BEGIN
PERFORM pg_notify('DELETE', '{"id":' || OLD.id || ', "table":"' || TG_TABLE_NAME || '"}');
RETURN OLD;
END;
$$
LANGUAGE 'plpgsql';

-- Insert trigger
DROP TRIGGER IF EXISTS items_insert_trigger ON items;
CREATE TRIGGER items_insert_trigger
AFTER INSERT
ON items
FOR EACH ROW
EXECUTE PROCEDURE notify_insert_trigger_func();

-- Update trigger
DROP TRIGGER IF EXISTS items_update_trigger ON items;
CREATE TRIGGER items_update_trigger
AFTER UPDATE
ON items
FOR EACH ROW
EXECUTE PROCEDURE notify_update_trigger_func();

-- Delete trigger
DROP TRIGGER IF EXISTS items_delete_trigger ON items;
CREATE TRIGGER items_delete_trigger
AFTER DELETE
ON items
FOR EACH ROW
EXECUTE PROCEDURE notify_delete_trigger_func();

-- -- Use the following commands to enable compression
-- ALTER TABLE beacon_data SET (
--     timescaledb.compress,
--     timescaledb.compress_segmentby = 'mac'
-- );
-- SELECT add_compression_policy('beacon_data', INTERVAL '7 days');