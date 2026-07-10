FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN addgroup --system appgroup \
    && adduser --system --ingroup appgroup appuser \
    && mkdir -p /tmp/invoiceflow \
    && chown -R appuser:appgroup /tmp/invoiceflow

COPY requirements.txt .

RUN python -m pip install --upgrade pip \
    && pip install -r requirements.txt

COPY extraction.py .
COPY worker.py .

ENV WORKER_PULL_ADDRESS=tcp://*:5557
ENV WORKER_PUSH_ADDRESS=tcp://*:5558

EXPOSE 5557
EXPOSE 5558

USER appuser

CMD ["python", "worker.py"]
