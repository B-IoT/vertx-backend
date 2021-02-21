# set base image (host OS)
FROM python:3.9-slim

# set the working directory in the container
WORKDIR /code

# copy the dependencies file to the working directory
COPY requirements.txt .

# install dependencies
RUN pip install -r requirements.txt

# copy the content of the local directory to the working directory
COPY src/triangulation.py .

# command to run on container start
CMD [ "python", "./triangulation.py" ]