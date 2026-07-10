"""Small local test client for the ZeroMQ extraction worker."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import zmq


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python send_test_job.py <pdf-path>")
        raise SystemExit(1)

    pdf_path = Path(sys.argv[1]).resolve()

    context = zmq.Context.instance()

    push_socket = context.socket(zmq.PUSH)
    push_socket.connect("tcp://localhost:5557")

    pull_socket = context.socket(zmq.PULL)
    pull_socket.connect("tcp://localhost:5558")

    job = {
        "invoiceId": "local-test-1",
        "pdfPath": str(pdf_path),
    }

    push_socket.send_string(json.dumps(job, ensure_ascii=False))
    result = pull_socket.recv_string()

    print(json.dumps(json.loads(result), indent=2, ensure_ascii=False))

    push_socket.close()
    pull_socket.close()


if __name__ == "__main__":
    main()
