# Redis Cluster connection from local application

This document explains how to troubleshoot connection issues when a local application tries to connect to a Redis Cluster running inside Docker Compose.

## Problem

When running the application locally and Redis Cluster inside Docker, the application may fail to connect to the cluster even when the Redis ports are exposed correctly.

Example application error:

```text
Unable to connect to [redis-cluster-3/<unresolved>:7003]: Failed to resolve 'redis-cluster-3'
Unable to connect to [redis-cluster-2/<unresolved>:7002]: Failed to resolve 'redis-cluster-2'
Unable to connect to [redis-cluster-1/<unresolved>:7001]: Failed to resolve 'redis-cluster-1'
```

This usually happens when the application uses a Redis Cluster client, such as Lettuce, and the Redis Cluster nodes announce hostnames that are only resolvable inside Docker.

## Why `localhost:7001` is not enough

For a standalone Redis instance, connecting to `localhost:6379` is usually enough.

For Redis Cluster, it is different.

A cluster-aware client works roughly like this:

```text
1. The client connects to one startup node, for example localhost:7001.
2. The client asks Redis for the cluster topology.
3. Redis returns the real cluster nodes.
4. The client starts connecting directly to those nodes.
```

If the cluster topology returns:

```text
redis-cluster-1:7001
redis-cluster-2:7002
redis-cluster-3:7003
```

then the machine running the application must be able to resolve those hostnames.

So even if the application starts with:

```text
redis://localhost:7001
```

the client may later try to connect to:

```text
redis://redis-cluster-2:7002
```

If `redis-cluster-2` cannot be resolved outside Docker, the connection fails.

## Expected local Redis Cluster configuration

For a Redis Cluster running in Docker Compose and accessed by a local app, each Redis node should run on a unique port and announce a hostname that works inside Docker.

Example:

```yaml
redis-cluster-1:
  image: redis:7.4-alpine
  container_name: redis-cluster-1
  command: >
    redis-server
    --port 7001
    --cluster-enabled yes
    --cluster-config-file nodes.conf
    --cluster-node-timeout 5000
    --appendonly no
    --cluster-announce-hostname redis-cluster-1
    --cluster-announce-port 7001
    --cluster-announce-bus-port 17001
  ports:
    - "7001:7001"
    - "17001:17001"

redis-cluster-2:
  image: redis:7.4-alpine
  container_name: redis-cluster-2
  command: >
    redis-server
    --port 7002
    --cluster-enabled yes
    --cluster-config-file nodes.conf
    --cluster-node-timeout 5000
    --appendonly no
    --cluster-announce-hostname redis-cluster-2
    --cluster-announce-port 7002
    --cluster-announce-bus-port 17002
  ports:
    - "7002:7002"
    - "17002:17002"

redis-cluster-3:
  image: redis:7.4-alpine
  container_name: redis-cluster-3
  command: >
    redis-server
    --port 7003
    --cluster-enabled yes
    --cluster-config-file nodes.conf
    --cluster-node-timeout 5000
    --appendonly no
    --cluster-announce-hostname redis-cluster-3
    --cluster-announce-port 7003
    --cluster-announce-bus-port 17003
  ports:
    - "7003:7003"
    - "17003:17003"
```

Do not use `--cluster-announce-ip 127.0.0.1` for a Redis Cluster formed by multiple containers.

Inside each container, `127.0.0.1` means the container itself. If every node announces `127.0.0.1`, the cluster may fail to join and get stuck with:

```text
Waiting for the cluster to join
```

or with a partial state like:

```text
cluster_state:fail
cluster_slots_assigned:5461
cluster_known_nodes:1
cluster_size:1
```

A healthy 3-node cluster should show:

```text
cluster_state:ok
cluster_slots_assigned:16384
cluster_known_nodes:3
cluster_size:3
```

## Application configuration

When the application runs locally and Redis Cluster runs inside Docker, use the same hostnames announced by the cluster:

```properties
KLIMITER_MODE=cluster
KLIMITER_REDIS_URIS=redis://redis-cluster-1:7001,redis://redis-cluster-2:7002,redis://redis-cluster-3:7003
```

Or in YAML:

```yaml
klimiter:
  mode: cluster
  redis:
    uris: redis://redis-cluster-1:7001,redis://redis-cluster-2:7002,redis://redis-cluster-3:7003
```

The local machine must be able to resolve these names.

## Resolution on Linux

Edit `/etc/hosts`:

```bash
sudo nano /etc/hosts
```

Add:

```text
127.0.0.1 redis-cluster-1
127.0.0.1 redis-cluster-2
127.0.0.1 redis-cluster-3
```

Validate hostname resolution:

```bash
getent hosts redis-cluster-1
getent hosts redis-cluster-2
getent hosts redis-cluster-3
```

Expected result:

```text
127.0.0.1 redis-cluster-1
127.0.0.1 redis-cluster-2
127.0.0.1 redis-cluster-3
```

Validate Redis access:

```bash
redis-cli -c -h redis-cluster-1 -p 7001 ping
redis-cli -c -h redis-cluster-2 -p 7002 ping
redis-cli -c -h redis-cluster-3 -p 7003 ping
```

Expected result:

```text
PONG
PONG
PONG
```

## Resolution on Windows

Edit the Windows hosts file as Administrator.

Open Notepad as Administrator and open:

```text
C:\Windows\System32\drivers\etc\hosts
```

Add:

```text
127.0.0.1 redis-cluster-1
127.0.0.1 redis-cluster-2
127.0.0.1 redis-cluster-3
```

Validate hostname resolution using PowerShell:

```powershell
Resolve-DnsName redis-cluster-1
Resolve-DnsName redis-cluster-2
Resolve-DnsName redis-cluster-3
```

Or:

```powershell
ping redis-cluster-1
ping redis-cluster-2
ping redis-cluster-3
```

The names should resolve to:

```text
127.0.0.1
```

Validate Redis access:

```powershell
redis-cli -c -h redis-cluster-1 -p 7001 ping
redis-cli -c -h redis-cluster-2 -p 7002 ping
redis-cli -c -h redis-cluster-3 -p 7003 ping
```

Expected result:

```text
PONG
PONG
PONG
```

## Resolution on WSL

If the application runs inside WSL, edit the WSL hosts file, not the Windows hosts file:

```bash
sudo nano /etc/hosts
```

Add:

```text
127.0.0.1 redis-cluster-1
127.0.0.1 redis-cluster-2
127.0.0.1 redis-cluster-3
```

Validate:

```bash
getent hosts redis-cluster-1
getent hosts redis-cluster-2
getent hosts redis-cluster-3
```

Expected result:

```text
127.0.0.1 redis-cluster-1
127.0.0.1 redis-cluster-2
127.0.0.1 redis-cluster-3
```

Validate Redis access:

```bash
redis-cli -c -h redis-cluster-1 -p 7001 ping
redis-cli -c -h redis-cluster-2 -p 7002 ping
redis-cli -c -h redis-cluster-3 -p 7003 ping
```

Expected result:

```text
PONG
PONG
PONG
```

### Important WSL note

If the application is launched from IntelliJ using a Windows JDK, then the application is not actually running inside WSL. In that case, edit the Windows hosts file instead:

```text
C:\Windows\System32\drivers\etc\hosts
```

If the application is launched from IntelliJ using a WSL JDK, edit:

```text
/etc/hosts
```

## Resolution on macOS

Edit `/etc/hosts`:

```bash
sudo nano /etc/hosts
```

Add:

```text
127.0.0.1 redis-cluster-1
127.0.0.1 redis-cluster-2
127.0.0.1 redis-cluster-3
```

Flush DNS cache:

```bash
sudo dscacheutil -flushcache
sudo killall -HUP mDNSResponder
```

Validate hostname resolution:

```bash
dscacheutil -q host -a name redis-cluster-1
dscacheutil -q host -a name redis-cluster-2
dscacheutil -q host -a name redis-cluster-3
```

Validate Redis access:

```bash
redis-cli -c -h redis-cluster-1 -p 7001 ping
redis-cli -c -h redis-cluster-2 -p 7002 ping
redis-cli -c -h redis-cluster-3 -p 7003 ping
```

Expected result:

```text
PONG
PONG
PONG
```

## Cluster initialization script

If the cluster was previously created with a wrong announce configuration, Redis may keep an invalid `nodes.conf`.

Use a defensive initialization script that resets the nodes when the cluster is not healthy:

```sh
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
```

Make sure the script uses Unix line endings:

```bash
sed -i 's/\r$//' docker/redis-cluster/cluster-init.sh
```

## Recreate the cluster from scratch

When changing Redis Cluster ports, announce hostnames, or the init script, always recreate the environment:

```bash
docker compose down -v --remove-orphans
docker compose up -d
```

Check the cluster init logs:

```bash
docker compose logs -f redis-cluster-init
```

A successful cluster creation should include:

```text
[OK] All nodes agree about slots configuration.
>>> Check for open slots...
>>> Check slots coverage...
[OK] All 16384 slots covered.
```

## Validate cluster health

Run:

```bash
redis-cli -c -h redis-cluster-1 -p 7001 cluster info
```

Expected result:

```text
cluster_state:ok
cluster_slots_assigned:16384
cluster_slots_ok:16384
cluster_known_nodes:3
cluster_size:3
```

Check the cluster nodes:

```bash
redis-cli -c -h redis-cluster-1 -p 7001 cluster nodes
```

Expected topology:

```text
redis-cluster-1:7001@17001 master
redis-cluster-2:7002@17002 master
redis-cluster-3:7003@17003 master
```

The slots should be distributed across the three nodes:

```text
0-5460
5461-10922
10923-16383
```

## Common mistakes

### Using `localhost` as the application URI

This may work for the first connection, but the client will later follow Redis Cluster topology redirects.

Avoid:

```properties
KLIMITER_REDIS_URIS=redis://localhost:7001,redis://localhost:7002,redis://localhost:7003
```

Prefer:

```properties
KLIMITER_REDIS_URIS=redis://redis-cluster-1:7001,redis://redis-cluster-2:7002,redis://redis-cluster-3:7003
```

And add the hostnames to the local hosts file.

### Using `--cluster-announce-ip 127.0.0.1`

Avoid this for a Redis Cluster formed by multiple Docker containers.

Each container would announce itself as localhost, preventing other nodes from joining correctly.

Prefer:

```text
--cluster-announce-hostname redis-cluster-1
--cluster-announce-hostname redis-cluster-2
--cluster-announce-hostname redis-cluster-3
```

### Checking the wrong port in the init script

If the Redis node runs on port `7001`, do not check port `6379`.

Wrong:

```sh
redis-cli -h redis-cluster-1 -p 6379 cluster info
```

Correct:

```sh
redis-cli -h redis-cluster-1 -p 7001 cluster info
```

### Keeping stale cluster state

Redis Cluster stores state in `nodes.conf`.

If the cluster was created with a wrong configuration, recreate the environment:

```bash
docker compose down -v --remove-orphans
docker compose up -d
```

For local development, prefer:

```text
--appendonly no
```

to reduce persistence-related surprises during repeated cluster recreation.

## Quick checklist

Use this checklist when the local app cannot connect to Redis Cluster:

1. Check that all Redis containers are healthy:

```bash
docker compose ps
```

2. Check that the cluster is healthy:

```bash
redis-cli -c -h redis-cluster-1 -p 7001 cluster info
```

3. Check that all slots are assigned:

```bash
redis-cli -c -h redis-cluster-1 -p 7001 cluster nodes
```

4. Check local hostname resolution:

```bash
getent hosts redis-cluster-1
getent hosts redis-cluster-2
getent hosts redis-cluster-3
```

5. Check direct access to each node:

```bash
redis-cli -c -h redis-cluster-1 -p 7001 ping
redis-cli -c -h redis-cluster-2 -p 7002 ping
redis-cli -c -h redis-cluster-3 -p 7003 ping
```

6. Check that the application uses the same names announced by the cluster:

```properties
KLIMITER_REDIS_URIS=redis://redis-cluster-1:7001,redis://redis-cluster-2:7002,redis://redis-cluster-3:7003
```

## Summary

For a local application connecting to a Redis Cluster running in Docker Compose:

- The Redis nodes should announce Docker-resolvable names, such as `redis-cluster-1`, `redis-cluster-2`, and `redis-cluster-3`.
- The local machine must also resolve those names to `127.0.0.1`.
- `localhost:7001` is not enough because Redis Cluster clients follow the topology returned by the cluster.
- If the cluster gets stuck with `cluster_known_nodes:1` or only `5461` slots assigned, reset the nodes and recreate the cluster.
