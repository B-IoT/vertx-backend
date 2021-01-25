package ch.biot.backend.crud

// TimescaleDB PostgreSQL queries for items

internal const val INSERT_ITEM = "INSERT INTO items (beacon, category, service) VALUES ($1, $2, $3) RETURNING id"

internal const val GET_ITEMS =
  "SELECT DISTINCT ON (I.id) * FROM items I LEFT JOIN beacon_data D ON I.beacon = D.mac ORDER BY I.id, D.time DESC"

internal const val GET_ITEM =
  "SELECT * FROM items I LEFT JOIN beacon_data D ON I.beacon = D.mac WHERE I.id=$1 ORDER BY D.time DESC LIMIT 1"

internal const val UPDATE_ITEM = "UPDATE items SET beacon = $1, category = $2, service = $3 WHERE id=$4"

internal const val DELETE_ITEM = "DELETE from items WHERE id=$1"
