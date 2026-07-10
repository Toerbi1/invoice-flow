from __future__ import annotations

import json
import os
import traceback
from pathlib import Path
from typing import Any

import zmq

from extraction import extract_from_pdf


DEFAULT_PULL_ADDRESS = "tcp://*:5557"
DEFAULT_PUSH_ADDRESS = "tcp://*:5558"


def process_job(job: dict[str, Any]) -> dict[str, Any]:
    invoice_id = job.get("invoiceId")
    pdf_path = job.get("pdfPath")

    if not invoice_id:
        return {
            "invoiceId": None,
            "status": "failed",
            "error": "Missing required field: invoiceId",
        }

    if not pdf_path:
        return {
            "invoiceId": invoice_id,
            "status": "failed",
            "error": "Missing required field: pdfPath",
        }

    path = Path(pdf_path)

    if not path.exists():
        return {
            "invoiceId": invoice_id,
            "status": "failed",
            "error": f"PDF not found: {path}",
        }

    try:
        result = extract_from_pdf(path)

        return {
            "invoiceId": invoice_id,
            "status": "extracted",
            "extraction": result.to_payload(),
        }
    except Exception as exc:
        return {
            "invoiceId": invoice_id,
            "status": "failed",
            "error": str(exc),
            "traceback": traceback.format_exc(),
        }


def run_worker(
    pull_address: str = DEFAULT_PULL_ADDRESS,
    push_address: str = DEFAULT_PUSH_ADDRESS,
) -> None:
    context = zmq.Context.instance()

    pull_socket = context.socket(zmq.PULL)
    pull_socket.bind(pull_address)

    push_socket = context.socket(zmq.PUSH)
    push_socket.bind(push_address)

    print(f"Worker listening for jobs on {pull_address}")
    print(f"Worker publishing results on {push_address}")

    try:
        while True:
            raw_message = pull_socket.recv_string()
            print(f"received job: {raw_message}", flush=True)

            try:
                job = json.loads(raw_message)
                result = process_job(job)
            except json.JSONDecodeError as exc:
                result = {
                    "invoiceId": None,
                    "status": "failed",
                    "error": f"Invalid JSON: {exc}",
                }

            print(f"sending result: {result.get('invoiceId')} {result.get('status')}", flush=True)
            push_socket.send_string(json.dumps(result, ensure_ascii=False))
    finally:
        pull_socket.close()
        push_socket.close()


if __name__ == "__main__":
    run_worker(
        pull_address=os.getenv("WORKER_PULL_ADDRESS", DEFAULT_PULL_ADDRESS),
        push_address=os.getenv("WORKER_PUSH_ADDRESS", DEFAULT_PUSH_ADDRESS),
    )
