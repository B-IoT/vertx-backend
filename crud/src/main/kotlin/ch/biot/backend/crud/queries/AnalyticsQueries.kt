/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.crud.queries

// TimescaleDB PostgreSQL queries for analytics

fun getStatus(itemsTable: String, beaconDataTable: String) =
  "SELECT items_computed.service, items_computed.beaconstatus AS status, COUNT(items_computed.beaconstatus) FROM (SELECT DISTINCT ON (I.id) * FROM $itemsTable I LEFT JOIN $beaconDataTable D ON I.beacon = D.mac ORDER BY I.id, D.time DESC) items_computed GROUP BY items_computed.service, items_computed.beaconstatus;"
