from __future__ import annotations

import json
import os
import tempfile
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import zmq
from confluent_kafka import Consumer, Producer
from minio import Minio
from pymongo import MongoClient


DEFAULT_SHARED_TMP_DIR = "/tmp/invoiceflow"

SERVICE_NAME = "extraction-gateway"

DEFAULT_KAFKA_BOOTSTRAP = "localhost:29092"
DEFAULT_INPUT_TOPIC = "invoice.received"
DEFAULT_OUTPUT_TOPIC = "invoice.extracted"
DEFAULT_GROUP_ID = "extraction-gateway"

DEFAULT_MINIO_ENDPOINT = "localhost:9000"
DEFAULT_MINIO_ACCESS_KEY = "invoiceflow"
DEFAULT_MINIO_SECRET_KEY = "invoiceflow_dev_pw"
DEFAULT_MINIO_BUCKET = "invoices"
DEFAULT_MINIO_SECURE = "false"

DEFAULT_MONGO_URI = "mongodb://invoiceflow:invoiceflow_dev_pw@localhost:27017/invoiceflow?authSource=admin"
DEFAULT_MONGO_DATABASE = "invoiceflow"
DEFAULT_MONGO_COLLECTION = "extractions"

DEFAULT_WORKER_PUSH_ADDRESS = "tcp://localhost:5557"
DEFAULT_WORKER_PULL_ADDRESS = "tcp://localhost:5558"


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def env_bool(name: str, default: str = "false") -> bool:
    return os.getenv(name, default).lower() in {"1", "true", "yes", "y"}


def create_consumer() -> Consumer:
    return Consumer(
        {
            "bootstrap.servers": os.getenv("KAFKA_BOOTSTRAP", DEFAULT_KAFKA_BOOTSTRAP),
            "group.id": os.getenv("KAFKA_GROUP_ID", DEFAULT_GROUP_ID),
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        }
    )


def create_producer() -> Producer:
    return Producer(
        {
            "bootstrap.servers": os.getenv("KAFKA_BOOTSTRAP", DEFAULT_KAFKA_BOOTSTRAP),
        }
    )


def create_minio_client() -> Minio:
    return Minio(
        endpoint=os.getenv("MINIO_ENDPOINT", DEFAULT_MINIO_ENDPOINT),
        access_key=os.getenv("MINIO_ROOT_USER", DEFAULT_MINIO_ACCESS_KEY),
        secret_key=os.getenv("MINIO_ROOT_PASSWORD", DEFAULT_MINIO_SECRET_KEY),
        secure=env_bool("MINIO_SECURE", DEFAULT_MINIO_SECURE),
    )


def create_mongo_collection():
    client = MongoClient(os.getenv("MONGO_URI", DEFAULT_MONGO_URI))
    database = client[os.getenv("MONGO_DATABASE", DEFAULT_MONGO_DATABASE)]
    return database[os.getenv("MONGO_COLLECTION", DEFAULT_MONGO_COLLECTION)]


def create_zmq_sockets():
    context = zmq.Context.instance()

    push_socket = context.socket(zmq.PUSH)
    push_socket.connect(os.getenv("WORKER_PUSH_ADDRESS", DEFAULT_WORKER_PUSH_ADDRESS))

    pull_socket = context.socket(zmq.PULL)
    pull_socket.setsockopt(zmq.RCVTIMEO, 30000)
    pull_socket.connect(os.getenv("WORKER_PULL_ADDRESS", DEFAULT_WORKER_PULL_ADDRESS))

    return push_socket, pull_socket


def parse_kafka_value(raw_value: bytes) -> dict[str, Any]:
    return json.loads(raw_value.decode("utf-8"))


def get_payload(envelope: dict[str, Any]) -> dict[str, Any]:
    payload = envelope.get("payload")

    if not isinstance(payload, dict):
        raise ValueError("Kafka envelope is missing object field: payload")

    return payload


def download_pdf_from_minio(
    minio_client: Minio,
    document_key: str,
    target_dir: Path,
) -> Path:
    bucket = os.getenv("MINIO_BUCKET", DEFAULT_MINIO_BUCKET)
    target_path = target_dir / Path(document_key).name

    response = None

    try:
        response = minio_client.get_object(bucket, document_key)
        target_path.write_bytes(response.read())
        return target_path
    finally:
        if response is not None:
            response.close()
            response.release_conn()


def send_job_to_worker(
    push_socket,
    pull_socket,
    invoice_id: str,
    pdf_path: Path,
) -> dict[str, Any]:
    job = {
        "invoiceId": invoice_id,
        "pdfPath": str(pdf_path),
    }

    push_socket.send_string(json.dumps(job, ensure_ascii=False))

    raw_result = pull_socket.recv_string()
    return json.loads(raw_result)


def build_extracted_event(
    invoice_id: str,
    extraction_result: dict[str, Any],
) -> dict[str, Any]:
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": "invoice.extracted",
        "schemaVersion": 1,
        "occurredAt": utc_now_iso(),
        "correlationId": invoice_id,
        "producer": SERVICE_NAME,
        "payload": {
            "invoiceId": invoice_id,
            "status": extraction_result.get("status"),
            "extraction": extraction_result.get("extraction"),
            "error": extraction_result.get("error"),
            "extractedAt": utc_now_iso(),
        },
    }


def store_extraction(
    collection,
    original_envelope: dict[str, Any],
    extraction_result: dict[str, Any],
) -> None:
    payload = get_payload(original_envelope)
    invoice_id = payload["invoiceId"]

    document = {
        "invoiceId": invoice_id,
        "documentKey": payload.get("documentKey"),
        "originalFilename": payload.get("originalFilename"),
        "status": extraction_result.get("status"),
        "extraction": extraction_result.get("extraction"),
        "error": extraction_result.get("error"),
        "receivedEvent": original_envelope,
        "createdAt": utc_now_iso(),
    }

    collection.insert_one(document)


def publish_extracted_event(
    producer: Producer,
    invoice_id: str,
    event: dict[str, Any],
) -> None:
    topic = os.getenv("KAFKA_OUTPUT_TOPIC", DEFAULT_OUTPUT_TOPIC)

    producer.produce(
        topic=topic,
        key=invoice_id,
        value=json.dumps(event, ensure_ascii=False).encode("utf-8"),
    )
    producer.flush()


def handle_message(
    envelope: dict[str, Any],
    minio_client: Minio,
    mongo_collection,
    kafka_producer: Producer,
    worker_push_socket,
    worker_pull_socket,
) -> dict[str, Any]:
    payload = get_payload(envelope)

    invoice_id = payload["invoiceId"]
    document_key = payload["documentKey"]

    print(f"handling invoiceId={invoice_id}, documentKey={document_key}", flush=True)

    shared_tmp_dir = Path(os.getenv("SHARED_TMP_DIR", DEFAULT_SHARED_TMP_DIR))
    shared_tmp_dir.mkdir(parents=True, exist_ok=True)

    pdf_path = download_pdf_from_minio(
        minio_client=minio_client,
        document_key=document_key,
        target_dir=shared_tmp_dir,
    )

    try:
        extraction_result = send_job_to_worker(
            push_socket=worker_push_socket,
            pull_socket=worker_pull_socket,
            invoice_id=invoice_id,
            pdf_path=pdf_path,
        )
    finally:
        try:
            pdf_path.unlink(missing_ok=True)
        except OSError as exc:
            print(f"could not delete temporary PDF {pdf_path}: {exc}", flush=True)

    print(f"received worker result: {extraction_result.get('status')}", flush=True)

    print("storing extraction in MongoDB", flush=True)

    store_extraction(
        collection=mongo_collection,
        original_envelope=envelope,
        extraction_result=extraction_result,
    )

    extracted_event = build_extracted_event(
        invoice_id=invoice_id,
        extraction_result=extraction_result,
    )

    print("publishing invoice.extracted", flush=True)

    publish_extracted_event(
        producer=kafka_producer,
        invoice_id=invoice_id,
        event=extracted_event,
    )

    return extracted_event


def run_gateway() -> None:
    input_topic = os.getenv("KAFKA_INPUT_TOPIC", DEFAULT_INPUT_TOPIC)

    consumer = create_consumer()
    producer = create_producer()
    minio_client = create_minio_client()
    mongo_collection = create_mongo_collection()
    worker_push_socket, worker_pull_socket = create_zmq_sockets()

    consumer.subscribe([input_topic])

    print(f"{SERVICE_NAME} consuming from {input_topic}")

    try:
        while True:
            message = consumer.poll(1.0)

            if message is None:
                continue

            if message.error():
                print(f"Kafka consumer error: {message.error()}")
                continue

            try:
                envelope = parse_kafka_value(message.value())

                event = handle_message(
                    envelope=envelope,
                    minio_client=minio_client,
                    mongo_collection=mongo_collection,
                    kafka_producer=producer,
                    worker_push_socket=worker_push_socket,
                    worker_pull_socket=worker_pull_socket,
                )

                consumer.commit(message)
                print(
                    "processed invoice",
                    event["payload"]["invoiceId"],
                    event["payload"]["status"],
                )
            except Exception as exc:
                print(f"failed to process message: {exc}")
                time.sleep(1)
    finally:
        consumer.close()
        worker_push_socket.close()
        worker_pull_socket.close()


if __name__ == "__main__":
    run_gateway()
