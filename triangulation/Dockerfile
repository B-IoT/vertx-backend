# Copyright (c) 2021 BioT. All rights reserved.
# set base image (host OS)
FROM python:3.9-slim

# set the working directory in the container
WORKDIR /code

# copy the dependencies file to the working directory
COPY requirements.txt .

# upgrade packages
RUN apt-get update
RUN apt-get upgrade -y

# install dependencies
RUN pip install -U pip
RUN pip install -r requirements.txt

# copy the content of the local directory to the working directory
COPY src/ .

# command to run on container start
CMD [ "python", "./main.py" ]