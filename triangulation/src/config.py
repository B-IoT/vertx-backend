# Copyright (c) 2021 BioT. All rights reserved.

from decouple import config
from loguru import logger
import sys

logger.configure(
    handlers=[
        dict(
            sink=sys.stderr,
            format="<green>[{time}]</> <level>{message}</level>",
            backtrace=True,
            diagnose=True,
        )
    ]
)

KAFKA_HOST: str = config("KAFKA_HOST", default="localhost")
KAFKA_PORT: int = config("KAFKA_PORT", default=9092, cast=int)
TIMESCALE_HOST: str = config("TIMESCALE_HOST", default="localhost")
TIMESCALE_PORT: int = config("TIMESCALE_PORT", default=5432, cast=int)