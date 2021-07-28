/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud.queries

// TimescaleDB PostgreSQL queries for items

fun insertItem(itemsTable: String, customId: Boolean = false) =
  if (customId) "INSERT INTO $itemsTable (id, beacon, category, service, itemid, accessControlString, brand, model, supplier, purchasedate, purchaseprice, originlocation, currentlocation, room, contact, currentowner, previousowner, ordernumber, color, serialnumber, maintenancedate, status, comments, lastmodifieddate, lastmodifiedby) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24, $25) RETURNING id"
  else "INSERT INTO $itemsTable (beacon, category, service, itemid, accessControlString, brand, model, supplier, purchasedate, purchaseprice, originlocation, currentlocation, room, contact, currentowner, previousowner, ordernumber, color, serialnumber, maintenancedate, status, comments, lastmodifieddate, lastmodifiedby) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24) RETURNING id"

fun getItems(itemsTable: String, beaconDataTable: String) =
  "SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac ORDER BY I.id, D.time DESC"

fun getItemsWithAC(itemsTable: String, beaconDataTable: String, accessControlString: String) =
  "SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE (I.accessControlString LIKE '$accessControlString:%' OR I.accessControlString LIKE '$accessControlString') ORDER BY I.id, D.time DESC"

fun getItemsWithCategory(itemsTable: String, beaconDataTable: String) =
  "SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE I.category=$1 ORDER BY I.id, D.time DESC"

fun getItemsWithCategoryWithAC(itemsTable: String, beaconDataTable: String, accessControlString: String) =
  "SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE I.category=$1 AND (I.accessControlString LIKE '$accessControlString:%' OR I.accessControlString LIKE '$accessControlString') ORDER BY I.id, D.time DESC"

fun getClosestItems(itemsTable: String, beaconDataTable: String) =
  "SELECT items_computed.floor, json_agg(row_to_json(items_computed) ORDER BY ST_Distance(ST_SetSRID(ST_MakePoint($2, $1),4326),ST_SetSRID(ST_MakePoint(items_computed.longitude, items_computed.latitude),4326)) ASC) AS closest_items FROM (SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac ORDER BY I.id, D.time DESC) items_computed GROUP BY items_computed.floor"

fun getClosestItemsWithAC(itemsTable: String, beaconDataTable: String, accessControlString: String) =
  "SELECT items_computed.floor, json_agg(row_to_json(items_computed) ORDER BY ST_Distance(ST_SetSRID(ST_MakePoint($2, $1),4326),ST_SetSRID(ST_MakePoint(items_computed.longitude, items_computed.latitude),4326)) ASC) AS closest_items FROM (SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE (I.accessControlString LIKE '$accessControlString:%' OR I.accessControlString LIKE '$accessControlString') ORDER BY I.id, D.time DESC) items_computed GROUP BY items_computed.floor "


fun getClosestItemsWithCategory(itemsTable: String, beaconDataTable: String) =
  "SELECT items_computed.floor, json_agg(row_to_json(items_computed) ORDER BY ST_Distance(ST_SetSRID(ST_MakePoint($3, $2),4326),ST_SetSRID(ST_MakePoint(items_computed.longitude, items_computed.latitude),4326)) ASC) AS closest_items FROM (SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE I.category=$1 ORDER BY I.id, D.time DESC) items_computed GROUP BY items_computed.floor"

fun getClosestItemsWithCategoryWithAC(itemsTable: String, beaconDataTable: String, accessControlString: String) =
  "SELECT items_computed.floor, json_agg(row_to_json(items_computed) ORDER BY ST_Distance(ST_SetSRID(ST_MakePoint($3, $2),4326),ST_SetSRID(ST_MakePoint(items_computed.longitude, items_computed.latitude),4326)) ASC) AS closest_items FROM (SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE I.category=$1 AND (I.accessControlString LIKE '$accessControlString:%' OR I.accessControlString LIKE '$accessControlString') ORDER BY I.id, D.time DESC) items_computed GROUP BY items_computed.floor"

fun getItem(itemsTable: String, beaconDataTable: String) =
  "SELECT * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE I.id=$1 ORDER BY D.time DESC LIMIT 1"

fun getItemWithAC(itemsTable: String, beaconDataTable: String, accessControlString: String) =
  "SELECT * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE I.id=$1 AND (I.accessControlString LIKE '$accessControlString:%' OR I.accessControlString LIKE '$accessControlString') ORDER BY D.time DESC LIMIT 1"

fun updateItem(itemsTable: String, updatedColumns: List<String>): String {
  val columnsWithValues = updatedColumns.mapIndexed { index, colName -> "$colName = \$${index + 2}" }.joinToString()
  return "UPDATE $itemsTable SET $columnsWithValues WHERE id = $1"
}

fun deleteItem(itemsTable: String) = "DELETE FROM $itemsTable WHERE id=$1"
fun deleteItemWithAC(itemsTable: String, accessControlString: String) = "DELETE FROM $itemsTable WHERE id=$1 AND (accessControlString LIKE '$accessControlString:%' OR accessControlString LIKE '$accessControlString')"

fun getCategories(itemsTable: String) = "SELECT DISTINCT I.category FROM $itemsTable I"
