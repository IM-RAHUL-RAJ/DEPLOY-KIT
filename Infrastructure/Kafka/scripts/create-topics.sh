#!/usr/bin/env bash
# ==============================================================================
# Creates the contracted Kafka topics (contracts/kafka-topics.md) and their
# dead-letter topics. Safe to run more than once: existing topics are left alone.
#
#   ./create-topics.sh
#
# The broker comes from KAFKA_BOOTSTRAP_SERVERS: the environment if it is set
# (as Docker Compose does), otherwise Application/.env, the one place it is configured.
#
# Uses kafka-topics.sh (or kafka-topics) from PATH when installed, otherwise runs
# it inside the broker container named by KAFKA_CONTAINER.
# ==============================================================================

set -euo pipefail

ENV_FILE="$(cd "$(dirname "$0")/../../.." && pwd)/.env"
if [[ -z "${KAFKA_BOOTSTRAP_SERVERS:-}" && -f "${ENV_FILE}" ]]; then
    KAFKA_BOOTSTRAP_SERVERS="$(grep -E '^KAFKA_BOOTSTRAP_SERVERS=' "${ENV_FILE}" | tail -1 | cut -d= -f2- | tr -d '"')"
fi
BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVERS:?set KAFKA_BOOTSTRAP_SERVERS in Application/.env or the environment}"
CONTAINER_NAME="${KAFKA_CONTAINER:-trading-kafka}"
REPLICATION_FACTOR="${KAFKA_REPLICATION_FACTOR:-1}"

kafka_topics() {
    if command -v kafka-topics.sh &> /dev/null; then
        kafka-topics.sh "$@"
    elif command -v kafka-topics &> /dev/null; then
        kafka-topics "$@"
    else
        docker exec "${CONTAINER_NAME}" /opt/kafka/bin/kafka-topics.sh "$@"
    fi
}

create_topic() {
    local topic=$1 partitions=$2 retention_ms=$3
    echo "Creating '${topic}' (partitions=${partitions}, retention.ms=${retention_ms})"
    kafka_topics --bootstrap-server "${BOOTSTRAP_SERVER}" --create --if-not-exists \
        --topic "${topic}" \
        --partitions "${partitions}" \
        --replication-factor "${REPLICATION_FACTOR}" \
        --config retention.ms="${retention_ms}" \
        --config cleanup.policy=delete
}

echo "Kafka bootstrap: ${BOOTSTRAP_SERVER}"

# Keyed by accountId: per-account ordering, three executors in one group.
create_topic "orders"       3 604800000     # 7 days
create_topic "trade-events" 3 2592000000    # 30 days
# Keyed by symbol: higher volume, low value per message.
create_topic "market-data"  6 86400000      # 1 day

# Dead-letter topics: <topic>.DLT, the name the executor's error handler publishes to.
# Same partition count as the source topic, because a record keeps its partition.
create_topic "orders.DLT"       3 604800000
create_topic "trade-events.DLT" 3 2592000000
create_topic "market-data.DLT"  6 86400000

echo "Topics:"
kafka_topics --bootstrap-server "${BOOTSTRAP_SERVER}" --list
