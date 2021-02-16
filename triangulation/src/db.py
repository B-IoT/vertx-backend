import asyncio
import asyncpg
from decouple import config

TIMESCALE_HOST = config("TIMESCALE_HOST", default="localhost")
TIMESCALE_PORT = config("TIMESCALE_PORT", default=5432, cast=int)




async def f(data):
    """
data must be an array of tuples of the following form:
("aa:aa:aa:aa:aa:aa", 10, "available", 2.3, 3.2)
"""
    async with asyncpg.create_pool(
        host=TIMESCALE_HOST,
        port=TIMESCALE_PORT,
        ssl="require",
        database="biot",
        user="biot",
        password="biot",
    ) as pool:
        async with pool.acquire() as conn:
            stmt = await conn.prepare(
                """INSERT INTO beacon_data (time, mac, battery, status, latitude, longitude) VALUES (NOW(), $1, $2, $3, $4, $5);"""
            )
            await stmt.executemany(data)
            print("New data inserted")


loop = asyncio.get_event_loop()
loop.run_until_complete(f([("bb:aa:aa:aa:aa:aa", 10, "available", 2.3, 3.2)]))
