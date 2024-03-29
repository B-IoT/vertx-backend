/*
 * Copyright (c) 2021 BioT. All rights reserved.
 */

db.createUser({
    user: "biot",
    pwd: "biot",
    roles: [
        {role: "dbAdmin", db: "clients"},
        {role: "readWrite", db: "clients"}
    ]
});

db.createCollection("relays");
db.relays.createIndex({relayID: 1}, {unique: true});
db.relays.createIndex({mqttID: 1}, {unique: true});
db.relays.createIndex({mqttUsername: 1}, {unique: true});

db.createCollection("idsRelays");
db.idsRelays.createIndex({id: 1}, {unique: true});
db.idsRelays.insert({id: 1})

db.createCollection("users");
db.users.createIndex({userID: 1}, {unique: true});
db.users.createIndex({username: 1}, {unique: true});