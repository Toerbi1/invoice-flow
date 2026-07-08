#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
PARTITIONS="${TOPIC_PARTITIONS:-3}"
REPLICATION="${TOPIC_REPLICATION:-1}"

TOPICS=(
  invoice.received
  invoice.extracted
  invoice.validated
  invoice.approved
  invoice.rejected
)

echo ">> ensuring ${#TOPICS[@]} topics on ${BOOTSTRAP} (partitions=${PARTITIONS}, rf=${REPLICATION})"
for t in "${TOPICS[@]}"; do
  kafka-topics --bootstrap-server "${BOOTSTRAP}" \
    --create --if-not-exists \
    --topic "${t}" \
    --partitions "${PARTITIONS}" \
    --replication-factor "${REPLICATION}"
  echo "   ok: ${t}"
done

echo ">> current topics:"
kafka-topics --bootstrap-server "${BOOTSTRAP}" --list
