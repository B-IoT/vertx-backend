db.createCollection("relays");

db.relays.createIndex({relayID: 1}, {unique: true});