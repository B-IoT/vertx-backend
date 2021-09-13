import pytest
import asyncpg
import time

from ..src.triangulator import Triangulator
from ..src.config import TIMESCALE_HOST, TIMESCALE_PORT


@pytest.mark.asyncio
@pytest.mark.usefixtures("docker_compose")
async def test_triangulator_triangulates(mocker):
    db_pool = await asyncpg.create_pool(
        host=TIMESCALE_HOST,
        port=TIMESCALE_PORT,
        database="biot",
        user="biot",
        password="biot",
    )
    triangulator = await Triangulator.create()

    mac1 = "aaaa"
    mac2 = "bbbb"

    relay1 = "relay1"
    relay2 = "relay2"
    relay3 = "relay3"

    msg1 = {
        "relayID": relay1,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -69,
                "battery": 53,
                "temperature": 20,
                "status": 0,
            },
            {
                "mac": mac2,
                "rssi": -62,
                "battery": 51,
                "temperature": 21,
                "status": 0,
            },
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg2 = {
        "relayID": relay2,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -79,
                "battery": 53,
                "temperature": 21,
                "status": 0,
            },
            {
                "mac": mac2,
                "rssi": -60,
                "battery": 51,
                "temperature": 22,
                "status": 0,
            },
        ],
        "latitude": 42.42,
        "longitude": 2.22,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg3 = {
        "relayID": relay3,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -59,
                "battery": 53,
                "temperature": 23,
                "status": 0,
            },
            {
                "mac": mac2,
                "rssi": -60,
                "battery": 51,
                "temperature": 24,
                "status": 2,
            },
        ],
        "latitude": 47.49,
        "longitude": 1.31,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg4 = {
        "relayID": relay1,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -72,
                "battery": 60,
                "temperature": 25,
                "status": 0,
            },
            {
                "mac": mac2,
                "rssi": -59,
                "battery": 79,
                "temperature": 25,
                "status": 2,
            },
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }

    await triangulator.triangulate(relay1, msg1)
    await triangulator.triangulate(relay2, msg2)
    await triangulator.triangulate(relay3, msg3)
    await triangulator.triangulate(relay1, msg4)

    async with db_pool.acquire() as conn:
        fetch_stmt = await conn.prepare(
            "SELECT * from beacon_data WHERE mac = $1 ORDER BY time DESC LIMIT 1"
        )
        beacon1 = await fetch_stmt.fetchrow(mac1)
        beacon2 = await fetch_stmt.fetchrow(mac2)

        assert beacon1 is not None, "The first beacon is None"
        assert beacon2 is not None, "The second beacon is None"

        assert beacon1["floor"] == 1, "The first beacon is at the first floor"
        assert beacon2["floor"] == 1, "The second beacon is at the first floor"

        assert beacon1["latitude"] > 0, "The first beacon has a valid latitude"
        assert beacon2["latitude"] > 0, "The second beacon has a valid latitude"

        assert beacon1["longitude"] > 0, "The first beacon has a valid longitude"
        assert beacon2["longitude"] > 0, "The second beacon has a valid longitude"

        assert (
            beacon1["beaconstatus"] == "available"
        ), "The first beacon has status 'available'"
        assert (
            beacon2["beaconstatus"] == "toRepair"
        ), "The second beacon has status 'toRepair'"

        assert (
            beacon1["temperature"] == msg4["beacons"][0]["temperature"]
        ), "The first beacon has the last registered temperature"
        assert (
            beacon2["temperature"] == msg4["beacons"][1]["temperature"]
        ), "The second beacon has the last registered temperature"

        assert (
            beacon1["battery"] == msg4["beacons"][0]["battery"]
        ), "The first beacon has the last registered battery"
        assert (
            beacon2["battery"] == msg4["beacons"][1]["battery"]
        ), "The second beacon has the last registered battery"


@pytest.mark.asyncio
@pytest.mark.usefixtures("docker_compose")
async def test_triangulator_battery_sentinel_value(mocker):
    db_pool = await asyncpg.create_pool(
        host=TIMESCALE_HOST,
        port=TIMESCALE_PORT,
        database="biot",
        user="biot",
        password="biot",
    )
    triangulator = await Triangulator.create()

    mac1 = "aaaa"

    relay1 = "relay1"
    relay2 = "relay2"
    relay3 = "relay3"

    msg1 = {
        "relayID": relay1,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -69,
                "battery": 53,
                "temperature": 20,
                "status": 0,
            }
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg2 = {
        "relayID": relay2,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -79,
                "battery": 54,
                "temperature": 21,
                "status": 0,
            }
        ],
        "latitude": 42.42,
        "longitude": 2.22,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg3 = {
        "relayID": relay3,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -59,
                "battery": 55,
                "temperature": 23,
                "status": 0,
            }
        ],
        "latitude": 47.49,
        "longitude": 1.31,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg4 = {
        "relayID": relay1,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -69,
                "battery": 52,
                "temperature": 20,
                "status": 0,
            }
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg5 = {
        "relayID": relay1,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -69,
                "battery": -1,
                "temperature": 25,
                "status": 0,
            }
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }

    await triangulator.triangulate(relay1, msg1)
    await triangulator.triangulate(relay2, msg2)
    await triangulator.triangulate(relay3, msg3)
    await triangulator.triangulate(relay1, msg4)

    await triangulator.triangulate(relay2, msg2)
    await triangulator.triangulate(relay3, msg3)
    await triangulator.triangulate(relay1, msg5)

    async with db_pool.acquire() as conn:
        fetch_stmt = await conn.prepare(
            "SELECT * from beacon_data WHERE mac = $1 ORDER BY time DESC LIMIT 1"
        )
        beacon = await fetch_stmt.fetchrow(mac1)

        assert beacon is not None, "The beacon is None"

        assert beacon["floor"] == 1, "The first beacon is at the first floor"

        assert beacon["latitude"] > 0, "The first beacon has a valid latitude"

        assert beacon["longitude"] > 0, "The first beacon has a valid longitude"

        assert (
            beacon["beaconstatus"] == "available"
        ), "The first beacon has status 'available'"

        assert (
            beacon["temperature"] == msg5["beacons"][0]["temperature"]
        ), "The first beacon has the last registered temperature"

        assert (
            beacon["battery"] == msg4["beacons"][0]["battery"]
        ), "The first beacon has the last registered battery (sentinel value not considered)"


@pytest.mark.asyncio
@pytest.mark.usefixtures("docker_compose")
async def test_triangulator_temperature_sentinel_value(mocker):
    db_pool = await asyncpg.create_pool(
        host=TIMESCALE_HOST,
        port=TIMESCALE_PORT,
        database="biot",
        user="biot",
        password="biot",
    )
    triangulator = await Triangulator.create()

    mac1 = "aaaa"

    relay1 = "relay1"
    relay2 = "relay2"
    relay3 = "relay3"

    msg1 = {
        "relayID": relay1,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -69,
                "battery": 53,
                "temperature": 20,
                "status": 0,
            }
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg2 = {
        "relayID": relay2,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -79,
                "battery": 54,
                "temperature": 21,
                "status": 0,
            }
        ],
        "latitude": 42.42,
        "longitude": 2.22,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg3 = {
        "relayID": relay3,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -59,
                "battery": 55,
                "temperature": 23,
                "status": 0,
            }
        ],
        "latitude": 47.49,
        "longitude": 1.31,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg4 = {
        "relayID": relay1,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -69,
                "battery": 23,
                "temperature": 30,
                "status": 0,
            }
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg5 = {
        "relayID": relay1,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -69,
                "battery": 89,
                "temperature": -256,
                "status": 0,
            }
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }

    await triangulator.triangulate(relay1, msg1)
    await triangulator.triangulate(relay2, msg2)
    await triangulator.triangulate(relay3, msg3)
    await triangulator.triangulate(relay1, msg4)

    await triangulator.triangulate(relay2, msg2)
    await triangulator.triangulate(relay3, msg3)
    await triangulator.triangulate(relay1, msg5)

    async with db_pool.acquire() as conn:
        fetch_stmt = await conn.prepare(
            "SELECT * from beacon_data WHERE mac = $1 ORDER BY time DESC LIMIT 1"
        )
        beacon = await fetch_stmt.fetchrow(mac1)

        assert beacon is not None, "The beacon is None"

        assert beacon["floor"] == 1, "The first beacon is at the first floor"

        assert beacon["latitude"] > 0, "The first beacon has a valid latitude"

        assert beacon["longitude"] > 0, "The first beacon has a valid longitude"

        assert (
            beacon["beaconstatus"] == "available"
        ), "The first beacon has status 'available'"

        assert (
            beacon["temperature"] == msg4["beacons"][0]["temperature"]
        ), "The first beacon has the last registered temperature (sentinel value not considered)"

        assert (
            beacon["battery"] == msg5["beacons"][0]["battery"]
        ), "The first beacon has the last registered battery"

@pytest.mark.asyncio
@pytest.mark.usefixtures("docker_compose")
async def test_triangulator_status_sentinel_value(mocker):
    db_pool = await asyncpg.create_pool(
        host=TIMESCALE_HOST,
        port=TIMESCALE_PORT,
        database="biot",
        user="biot",
        password="biot",
    )
    triangulator = await Triangulator.create()

    mac1 = "aaaa"

    relay1 = "relay1"
    relay2 = "relay2"
    relay3 = "relay3"

    msg1 = {
        "relayID": relay1,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -69,
                "battery": 53,
                "temperature": 20,
                "status": 0,
            }
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg2 = {
        "relayID": relay2,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -79,
                "battery": 54,
                "temperature": 21,
                "status": 0,
            }
        ],
        "latitude": 42.42,
        "longitude": 2.22,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg3 = {
        "relayID": relay3,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -59,
                "battery": 55,
                "temperature": 23,
                "status": 0,
            }
        ],
        "latitude": 47.49,
        "longitude": 1.31,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg4 = {
        "relayID": relay1,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -69,
                "battery": 23,
                "temperature": 30,
                "status": 0,
            }
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }
    msg5 = {
        "relayID": relay1,
        "beacons": [
            {
                "mac": mac1,
                "rssi": -69,
                "battery": 89,
                "temperature": 99,
                "status": -1,
            }
        ],
        "latitude": 42.34,
        "longitude": 2.32,
        "timestamp": "timestamp",
        "floor": 1,
        "company": "biot",
    }

    await triangulator.triangulate(relay1, msg1)
    await triangulator.triangulate(relay2, msg2)
    await triangulator.triangulate(relay3, msg3)
    await triangulator.triangulate(relay1, msg4)

    await triangulator.triangulate(relay2, msg2)
    await triangulator.triangulate(relay3, msg3)
    await triangulator.triangulate(relay1, msg5)

    async with db_pool.acquire() as conn:
        fetch_stmt = await conn.prepare(
            "SELECT * from beacon_data WHERE mac = $1 ORDER BY time DESC LIMIT 1"
        )
        beacon = await fetch_stmt.fetchrow(mac1)

        assert beacon is not None, "The beacon is None"

        assert beacon["floor"] == 1, "The first beacon is at the first floor"

        assert beacon["latitude"] > 0, "The first beacon has a valid latitude"

        assert beacon["longitude"] > 0, "The first beacon has a valid longitude"

        assert (
            beacon["beaconstatus"] == "available"
        ), "The first beacon has status 'available'  (sentinel value not considered)"

        assert (
            beacon["temperature"] == msg5["beacons"][0]["temperature"]
        ), "The first beacon has the last registered temperature"

        assert (
            beacon["battery"] == msg5["beacons"][0]["battery"]
        ), "The first beacon has the last registered battery"