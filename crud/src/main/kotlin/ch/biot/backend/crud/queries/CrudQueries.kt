/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud.queries

// TimescaleDB PostgreSQL queries for items

fun insertItem(itemsTable: String, customId: Boolean = false) =
  if (customId) "INSERT INTO $itemsTable (id, beacon, categoryid, service, itemid, accessControlString, brand, model, supplier, purchasedate, purchaseprice, originlocation, currentlocation, room, contact, currentowner, previousowner, ordernumber, color, serialnumber, maintenancedate, status, comments, lastmodifieddate, lastmodifiedby) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24, $25) RETURNING id"
  else "INSERT INTO $itemsTable (beacon, categoryid, service, itemid, accessControlString, brand, model, supplier, purchasedate, purchaseprice, originlocation, currentlocation, room, contact, currentowner, previousowner, ordernumber, color, serialnumber, maintenancedate, status, comments, lastmodifieddate, lastmodifiedby) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24) RETURNING id"

fun getItems(itemsTable: String, beaconDataTable: String, accessControlString: String) =
  "SELECT I.*, C.name AS category FROM (SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE (I.accessControlString LIKE '$accessControlString:%' OR I.accessControlString LIKE '$accessControlString') ORDER BY I.id, D.time DESC) I LEFT JOIN categories C ON I.categoryid = C.id"

fun getItemsWithCategory(itemsTable: String, beaconDataTable: String, accessControlString: String) =
  "SELECT * FROM (SELECT DISTINCT ON (I.id) * FROM (SELECT I.*, C.name AS category FROM $itemsTable I LEFT JOIN categories C ON I.categoryid = C.id WHERE I.categoryid = $1) I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE (I.accessControlString LIKE '$accessControlString:%' OR I.accessControlString LIKE '$accessControlString') ORDER BY I.id, D.time DESC) AS Q"

fun getClosestItems(itemsTable: String, beaconDataTable: String, accessControlString: String): String {
  val getItemsQuery = getItems(itemsTable, beaconDataTable, accessControlString)
  return "SELECT items_computed.floor, json_agg(row_to_json(items_computed) ORDER BY ST_Distance(ST_SetSRID(ST_MakePoint($2, $1),4326),ST_SetSRID(ST_MakePoint(items_computed.longitude, items_computed.latitude),4326)) ASC) AS closest_items FROM ($getItemsQuery) items_computed GROUP BY items_computed.floor"
}

fun getClosestItemsWithCategory(itemsTable: String, beaconDataTable: String, accessControlString: String) =
  "SELECT items_computed.floor, json_agg(row_to_json(items_computed) ORDER BY ST_Distance(ST_SetSRID(ST_MakePoint($3, $2),4326),ST_SetSRID(ST_MakePoint(items_computed.longitude, items_computed.latitude),4326)) ASC) AS closest_items FROM (SELECT * FROM (SELECT DISTINCT ON (I.id) * FROM (SELECT I.*, C.name AS category FROM $itemsTable I LEFT JOIN categories C ON I.categoryid = C.id WHERE I.categoryid = $1) I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE (I.accessControlString LIKE '$accessControlString:%' OR I.accessControlString LIKE '$accessControlString') ORDER BY I.id, D.time DESC) AS Q) items_computed GROUP BY items_computed.floor"

fun getItem(itemsTable: String, beaconDataTable: String, accessControlString: String) =
  "SELECT I.*, C.name AS category FROM (SELECT * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac WHERE I.id=$1 AND (I.accessControlString LIKE '$accessControlString:%' OR I.accessControlString LIKE '$accessControlString') ORDER BY D.time DESC LIMIT 1) I LEFT JOIN categories C ON I.categoryid = C.id"

fun updateItem(itemsTable: String, updatedColumns: List<String>, accessControlString: String): String {
  val columnsWithValues = updatedColumns.mapIndexed { index, colName -> "$colName = \$${index + 2}" }.joinToString()
  return "UPDATE $itemsTable SET $columnsWithValues WHERE id = $1 AND (accessControlString LIKE '$accessControlString:%' OR accessControlString LIKE '$accessControlString')"
}

fun deleteItem(itemsTable: String, accessControlString: String) =
  "DELETE FROM $itemsTable WHERE id=$1 AND (accessControlString LIKE '$accessControlString:%' OR accessControlString LIKE '$accessControlString')"

fun getCategories() =
  "SELECT CA.* FROM categories CA, company_categories CC WHERE CA.id = CC.categoryid AND CC.company = $1"

fun getCategory() =
  "SELECT * FROM categories WHERE id = $1"

fun deleteCategory() =
  "DELETE FROM categories WHERE id = $1"

fun insertCategory() =
  "INSERT INTO categories (name) VALUES ($1) RETURNING id"

fun addCategoryToCompany() =
  "INSERT INTO company_categories (categoryid, company) VALUES ($1, $2)"

fun updateCategory() =
  "UPDATE categories SET name = $1 WHERE id = $2"

fun createSnapshot(itemsTable: String) =
  "INSERT INTO ${itemsTable}_snapshots (snapshotdate, accesscontrolstring) VALUES (NOW(), $1) RETURNING id"

fun getSnapshots(itemsTable: String, accessControlString: String) =
  "SELECT * FROM ${itemsTable}_snapshots WHERE (accessControlString LIKE '$accessControlString:%' OR accessControlString LIKE '$accessControlString')"

fun getSnapshot(itemsTable: String, snapshotId: Int) = "SELECT * FROM ${itemsTable}_snapshot_$snapshotId"

fun dropSnapshotTable(itemsTable: String, snapshotId: Int) = "DROP TABLE ${itemsTable}_snapshot_$snapshotId"

fun deleteSnapshot(itemsTable: String, accessControlString: String) =
  "DELETE FROM ${itemsTable}_snapshots WHERE id=$1 AND (accessControlString LIKE '$accessControlString:%' OR accessControlString LIKE '$accessControlString')"

fun leftOuterJoinFromSnapshots(itemsTable: String, firstSnapshotId: Int, secondSnapshotId: Int) =
  "SELECT F.* FROM ${itemsTable}_snapshot_$firstSnapshotId F LEFT JOIN ${itemsTable}_snapshot_$secondSnapshotId S ON F.id = S.id WHERE S.id is NULL"

fun rightOuterJoinFromSnapshots(itemsTable: String, firstSnapshotId: Int, secondSnapshotId: Int) =
  "SELECT S.* FROM ${itemsTable}_snapshot_$firstSnapshotId F RIGHT JOIN ${itemsTable}_snapshot_$secondSnapshotId S ON F.id = S.id WHERE F.id is NULL"

fun innerJoinFromSnapshots(itemsTable: String, firstSnapshotId: Int, secondSnapshotId: Int) =
  "SELECT S.* FROM ${itemsTable}_snapshot_$firstSnapshotId F INNER JOIN ${itemsTable}_snapshot_$secondSnapshotId S ON F.id = S.id"

/**
 * Makes a copy of the table, including the category name.
 */
fun snapshotTable(itemsTable: String, snapshotId: Int) =
  "CREATE TABLE ${itemsTable}_snapshot_$snapshotId AS (SELECT I.*, C.name AS category FROM $itemsTable I LEFT JOIN categories C ON I.categoryid = C.id)"

fun searchForTable() = "SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = $1"
