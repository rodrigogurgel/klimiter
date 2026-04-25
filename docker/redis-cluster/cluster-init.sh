#!/bin/sh
set -eu

echo "Waiting Redis cluster nodes..."

for node in \
  "redis-cluster-1 7001" \
  "redis-cluster-2 7002" \
  "redis-cluster-3 7003"
do
  host="$(echo "$node" | awk '{print $1}')"
  port="$(echo "$node" | awk '{print $2}')"

  until redis-cli -h "$host" -p "$port" ping >/dev/null 2>&1; do
    echo "Waiting for $host:$port..."
    sleep 1
  done
done

echo "Checking Redis cluster status..."

CLUSTER_STATE="$(redis-cli -h redis-cluster-1 -p 7001 cluster info 2>/dev/null | grep cluster_state || true)"

if echo "$CLUSTER_STATE" | grep -q "cluster_state:ok"; then
  echo "Redis cluster already created. Skipping cluster init."
  exit 0
fi

echo "Redis cluster is not healthy or not created. Resetting nodes..."

for node in \
  "redis-cluster-1 7001" \
  "redis-cluster-2 7002" \
  "redis-cluster-3 7003"
do
  host="$(echo "$node" | awk '{print $1}')"
  port="$(echo "$node" | awk '{print $2}')"

  echo "Resetting $host:$port..."

  redis-cli -h "$host" -p "$port" FLUSHALL || true
  redis-cli -h "$host" -p "$port" CLUSTER RESET HARD || true
done

echo "Creating Redis Cluster..."

redis-cli --cluster create \
  redis-cluster-1:7001 \
  redis-cluster-2:7002 \
  redis-cluster-3:7003 \
  --cluster-replicas 0 \
  --cluster-yes

echo "Redis cluster created successfully."

redis-cli -h redis-cluster-1 -p 7001 cluster info
redis-cli -h redis-cluster-1 -p 7001 cluster nodes