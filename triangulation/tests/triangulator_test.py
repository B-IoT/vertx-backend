import pytest
import asyncpg

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

    await triangulator.triangulate(relay1, msg1)
    await triangulator.triangulate(relay2, msg2)
    await triangulator.triangulate(relay3, msg3)
    await triangulator.triangulate(relay1, msg1)

    async with db_pool.acquire() as conn:
      fetch_stmt = await conn.prepare("SELECT * from beacon_data WHERE mac = $1 ORDER BY time DESC LIMIT 1")
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

      assert beacon1["beaconstatus"] == "available", "The first beacon has status 'available'"
      assert beacon2["beaconstatus"] == "toRepair", "The second beacon has status 'toRepair'"

      assert beacon1["temperature"] == msg3["beacons"][0]["temperature"], "The first beacon has the last registered temperature"
      assert beacon2["temperature"] == msg3["beacons"][1]["temperature"], "The second beacon has the last registered temperature"

      assert beacon1["battery"] == msg3["beacons"][0]["battery"], "The first beacon has the last registered battery"
      assert beacon2["battery"] == msg3["beacons"][1]["battery"], "The second beacon has the last registered battery"
