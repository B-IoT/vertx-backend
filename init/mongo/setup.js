db.createCollection("relays");

db.relays.createIndex({relayID: 1}, {unique: true});
db.relays.createIndex({mqttID: 1}, {unique: true});
db.relays.createIndex({mqttUsername: 1}, {unique: true});