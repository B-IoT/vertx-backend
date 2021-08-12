/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

package ch.biot.backend.relayscommunication

// TimescaleDB PostgreSQL queries for items

fun getItemsMacs(itemsTable: String, accessControlString: String) =
  "SELECT I.beacon FROM $itemsTable I  WHERE (I.accessControlString LIKE '$accessControlString:%' OR I.accessControlString LIKE '$accessControlString')"
