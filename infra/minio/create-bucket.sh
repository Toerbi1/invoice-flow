#!/bin/sh
set -eu

echo ">> configuring mc alias for ${MINIO_ENDPOINT}"
mc alias set local "${MINIO_ENDPOINT}" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}"

echo ">> waiting for MinIO to be ready"
mc ready local

echo ">> ensuring bucket '${MINIO_BUCKET}'"
mc mb --ignore-existing "local/${MINIO_BUCKET}"

echo ">> buckets:"
mc ls local
