# Local integration fixture only; never a deployment image.
FROM python:3.12-slim-bookworm
WORKDIR /app
COPY infra/external-storage/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt 'moto[server]==5.2.5'
COPY infra/backup/ledger.py /app/backup/ledger.py
COPY infra/external-storage/ /app/external-storage/
COPY tools/production/fixtures/external_gateway.py /app/external_gateway.py
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["python", "/app/external_gateway.py"]
