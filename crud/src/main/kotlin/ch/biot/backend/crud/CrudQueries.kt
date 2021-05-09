/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud

// TimescaleDB PostgreSQL queries for items

internal fun insertItem(itemsTable: String, customId: Boolean = false) =
  if (customId) "INSERT INTO $itemsTable (id, beacon, category, service) VALUES ($1, $2, $3, $4) RETURNING id"
  else "INSERT INTO $itemsTable (beacon, category, service) VALUES ($1, $2, $3) RETURNING id"

internal fun getItems(itemsTable: String, beaconDataTable: String) =
  "SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac ORDER BY I.id, D.time DESC"

internal fun getItemsWithCategory(itemsTable: String, beaconDataTable: String) =
  "SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE I.category=$1 ORDER BY I.id, D.time DESC"

internal fun getClosestItems(itemsTable: String, beaconDataTable: String) =
  "SELECT items_computed.floor, json_agg(row_to_json(items_computed) ORDER BY ST_Distance(ST_SetSRID(ST_MakePoint($2, $1),4326),ST_SetSRID(ST_MakePoint(items_computed.longitude, items_computed.latitude),4326)) ASC) AS closest_items FROM (SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac ORDER BY I.id, D.time DESC) items_computed GROUP BY items_computed.floor"

internal fun getClosestItemsWithCategory(itemsTable: String, beaconDataTable: String) =
  "SELECT items_computed.floor, json_agg(row_to_json(items_computed) ORDER BY ST_Distance(ST_SetSRID(ST_MakePoint($3, $2),4326),ST_SetSRID(ST_MakePoint(items_computed.longitude, items_computed.latitude),4326)) ASC) AS closest_items FROM (SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE I.category=$1 ORDER BY I.id, D.time DESC) items_computed GROUP BY items_computed.floor"

internal fun getItem(itemsTable: String, beaconDataTable: String) =
  "SELECT * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE I.id=$1 ORDER BY D.time DESC LIMIT 1"

internal fun updateItem(itemsTable: String) =
  "UPDATE $itemsTable SET beacon = $1, category = $2, service = $3 WHERE id=$4"

internal fun deleteItem(itemsTable: String) = "DELETE from $itemsTable WHERE id=$1"

internal fun getCategories(itemsTable: String) = "SELECT DISTINCT I.category FROM $itemsTable I"
