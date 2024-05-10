# Copyright (c) 2021 BioT. All rights reserved.

# set base image (host OS)
FROM python:3.13.0b1-slim

# set the working directory in the container
WORKDIR /triangulation

# copy the dependencies file to the working directory
COPY requirements.txt requirements.txt

# upgrade packages
RUN apt-get update
RUN apt-get upgrade -y
RUN apt-get -y install gcc

# install dependencies
RUN pip install -U pip
RUN pip install -r requirements.txt

# copy the content of the local directory to the working directory
COPY . .

# command to run on container start
CMD [ "python", "-m", "src.main" ]