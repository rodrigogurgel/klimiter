#!/bin/sh

sleep 5

redis-cli --cluster create \
  redis-cluster-1:6379 \
  redis-cluster-2:6379 \
  redis-cluster-3:6379 \
  --cluster-yes