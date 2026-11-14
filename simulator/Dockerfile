FROM python:3.12-slim-bookworm
WORKDIR /source
COPY simulator/requirements.txt simulator/requirements.txt
RUN pip install --no-cache-dir -r simulator/requirements.txt
COPY simulator/ simulator/
COPY reference/v1.43/개발자료/09_구현설계/contracts/ reference/v1.43/개발자료/09_구현설계/contracts/
ENTRYPOINT ["python", "-u", "simulator/device.py"]
