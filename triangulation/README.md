# triangulation

This service encapsulates the location engine used for triangulation.

To run the tests, first execute in your terminal:

```shell
pip install -r requirements_dev.txt
```

Then, modify in `tests/docker-compose.yml` the path to the `init` folder in the `vertx-backend` project so that it is correct. The path should not be absolute, but instead should start from `~/`. You should keep `init/timescale:/docker-entrypoint-initdb.d/` and just modify the path above that.

Finally, make sure that Docker is running, then run:

```shell
pytest
```

The minimum Python version required is 3.8.
