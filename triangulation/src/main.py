# Copyright (c) 2021 BioT. All rights reserved.

from kafka import KafkaConsumer
from config import logger
from triangulation import Triangulator

import asyncio

# Constants
TOPIC = "incoming.update"


async def main():
    try:
        consumer = KafkaConsumer()
        triangulator = await Triangulator.create()

        logger.info("Starting Kafka consumer loop on topic '{}'...", TOPIC)
        await consumer.start([TOPIC], triangulator.triangulate)
    except KeyboardInterrupt:
        logger.info("Stopped consumer!")


if __name__ == "__main__":
    loop = asyncio.get_event_loop()
    loop.run_until_complete(main())
    loop.close()
