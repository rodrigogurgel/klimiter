# KLimiter — Troubleshooting high-volume load testing locally with gRPC

← [Troubleshooting](../TROUBLESHOOTING.md)

## Goal

This document describes how to diagnose and prepare a local environment for **high-volume** load testing of KLimiter using **k6 + gRPC + Nginx + Docker**.

A target of **4,000 requests per second** is used as a practical example, but the guidance applies to any scenario where the local environment starts showing saturation, connection errors, throughput drops, or instability under load.

The observed error was:

```text
CANCELED: context canceled while waiting for connections to become ready
```

This error typically means the gRPC client tried to execute a call but the connection was not yet ready or could not become ready within the expected time. In a local environment this usually happens when the test starts aggressively, when Nginx or the application cannot accept enough connections, or when Docker, WSL, Linux/macOS, or the test machine itself is constrained by CPU, memory, file descriptors, or sockets.

## Scope

Use this document when the goal is to test **local capacity under high load**, for example:

- validating KLimiter behaviour with thousands of requests per second;
- investigating `dropped_iterations` in k6;
- investigating gRPC errors such as `CANCELED`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`, or `RESOURCE_EXHAUSTED`;
- testing multiple application instances behind Nginx;
- discovering local Docker, WSL, Nginx, JVM, Redis, or k6 bottlenecks.

Do not use this document as a default daily-development configuration. The suggested adjustments increase CPU, memory, and socket consumption and should be applied temporarily.

## Files considered

The `nginx.conf` version considered in this document already contains important adjustments for high local load:

```nginx
worker_processes auto;
worker_rlimit_nofile 200000;

events {
    worker_connections 65535;
    multi_accept on;
}

http {
    access_log off;
    keepalive_timeout 75s;
    keepalive_requests 100000;

    upstream klimiter_grpc {
        least_conn;

        server app-1:9090 max_fails=3 fail_timeout=10s;
        server app-2:9090 max_fails=3 fail_timeout=10s;
        server app-3:9090 max_fails=3 fail_timeout=10s;

        keepalive 512;
    }

    server {
        listen 9090 http2;

        grpc_connect_timeout 10s;
        grpc_send_timeout 30s;
        grpc_read_timeout 30s;
        grpc_socket_keepalive on;

        location / {
            grpc_pass grpc://klimiter_grpc;
        }
    }
}
```

The `docker-compose.yaml` considered already has `ulimits.nofile` on the `nginx` service, but keeps conservative values for the application containers — reduced Java heap and memory limits — suitable for moderate local testing:

```yaml
x-app-common-environment: &app-common-environment
  JAVA_TOOL_OPTIONS: >-
    -Xms128m
    -Xmx384m
    -Dspring.jmx.enabled=false
    -Dfile.encoding=UTF-8

x-app-base: &app-base
  mem_limit: 512m
```

These values may be sufficient for development and moderate testing, but can be too low when the goal is to sustain thousands of requests per second locally.

---

## 1. When to apply this troubleshooting

### 1.1. Do not change the environment if

Do not change Nginx, Docker Desktop, WSL, Linux/macOS, or compose if:

- the test runs at low or medium load without errors;
- there are no `dropped_iterations` in k6;
- there is no significant volume of `CANCELED`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`, or `RESOURCE_EXHAUSTED`;
- `docker stats` does not show saturated CPU or memory;
- the goal is to validate a contract, payload, or rate-limit rule — not maximum local capacity.

In that case, adjust the k6 scenario first and run with progressive load.

### 1.2. Adjust the k6 script first when

Adjust the k6 script before touching the environment when:

- the test starts directly at high volume;
- errors appear in the first few seconds;
- there are `CANCELED` errors with a message similar to `context canceled while waiting for connections to become ready`;
- the test opens many gRPC connections at the same time;
- `dropped_iterations` appears but container CPU/memory are not clearly saturated.

Recommended configuration for local environments:

```js
export const options = {
  scenarios: {
    rampup_test: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 500,
      maxVUs: 2000,
      gracefulStop: '30s',
      stages: [
        { target: 500, duration: '20s' },
        { target: 1000, duration: '20s' },
        { target: 2000, duration: '20s' },
        { target: 3000, duration: '20s' },
        { target: 4000, duration: '1m' },
      ],
    },
  },
};
```

Once the ramp-up stabilises, use `constant-arrival-rate` only if the goal is to validate sustained throughput at a fixed rate.

### 1.3. Validate Nginx when

Validate or change `nginx.conf` when:

- traffic goes through Nginx, not directly to the application;
- errors such as `UNAVAILABLE`, `CANCELED`, `upstream timed out`, `connect() failed`, `connection reset by peer`, or `no live upstreams` appear in the logs;
- there are multiple application instances behind the load balancer;
- the load requires keepalive between Nginx and upstreams.

The version considered in this document is already a good starting point for high local volume, because it includes:

- `worker_processes auto`;
- `worker_rlimit_nofile 200000`;
- `worker_connections 65535`;
- `multi_accept on`;
- `access_log off`;
- `least_conn` on the upstream;
- `keepalive 512` for Nginx → application connections;
- explicit gRPC timeouts;
- `grpc_socket_keepalive on`.

If the repository file already matches the version above, the next adjustment is likely in the compose file, in Docker/WSL/Linux/macOS resources, in the JVM, in Redis, or in the k6 test design.

### 1.4. Adjust Docker Compose when

Adjust compose when:

- `docker stats` shows the application or Nginx at very high CPU;
- the application is close to its memory limit;
- Java has a low heap for the test;
- Nginx has `ulimits` but the applications do not;
- the scenario uses multiple instances, e.g. `app-1`, `app-2`, `app-3`, behind Nginx.

In the considered compose, Nginx already has `ulimits.nofile`, but the applications use `mem_limit: 512m` and `-Xmx384m`. For high local volume that may be insufficient.

### 1.5. Adjust the host when

Adjust Docker Desktop, WSL, Linux, or macOS when:

- containers appear healthy but the local environment saturates;
- `docker stats` shows high CPU across multiple containers;
- Docker Desktop is limited to few CPUs or little memory;
- WSL has little available memory;
- there are many sockets in `TIME-WAIT`;
- file descriptor limits are suspected;
- k6, Nginx, and the applications are competing for the same resources on the local machine.

---

## 2. Recommended strategy: temporary override

Avoid editing versioned files directly for high-volume tests. Prefer creating a temporary file:

```text
docker-compose.loadtest.override.yaml
```

Example:

```yaml
services:
  nginx:
    ulimits:
      nofile:
        soft: 200000
        hard: 200000

  app-1:
    mem_limit: 1536m
    environment:
      JAVA_TOOL_OPTIONS: >-
        -Xms512m
        -Xmx1024m
        -Dspring.jmx.enabled=false
        -Dfile.encoding=UTF-8
    ulimits:
      nofile:
        soft: 200000
        hard: 200000

  app-2:
    mem_limit: 1536m
    environment:
      JAVA_TOOL_OPTIONS: >-
        -Xms512m
        -Xmx1024m
        -Dspring.jmx.enabled=false
        -Dfile.encoding=UTF-8
    ulimits:
      nofile:
        soft: 200000
        hard: 200000

  app-3:
    mem_limit: 1536m
    environment:
      JAVA_TOOL_OPTIONS: >-
        -Xms512m
        -Xmx1024m
        -Dspring.jmx.enabled=false
        -Dfile.encoding=UTF-8
    ulimits:
      nofile:
        soft: 200000
        hard: 200000
```

Start with the override:

```bash
docker compose \
  -f docker-compose.yaml \
  -f docker-compose.loadtest.override.yaml \
  --profile app_cluster \
  up -d --build
```

Validate the final rendered configuration:

```bash
docker compose \
  -f docker-compose.yaml \
  -f docker-compose.loadtest.override.yaml \
  --profile app_cluster \
  config
```

Benefits:

- does not change the default project configuration;
- makes it easy to repeat the test;
- avoids accidentally committing local benchmark values;
- makes it simple to return to the normal environment.

---

## 3. Configuration by local environment

### 3.1. Windows with Docker Desktop + WSL 2

#### When to adjust

Adjust when:

- Docker Desktop uses the WSL 2 backend;
- the local test requires Nginx, Redis, multiple apps, and k6;
- `free -h` inside WSL shows little available memory;
- `nproc` shows fewer CPUs than expected;
- Docker/WSL becomes saturated during the test.

#### Create or edit `.wslconfig`

In PowerShell:

```powershell
notepad $env:USERPROFILE\.wslconfig
```

Suggestion for a machine with 16 GB of RAM:

```ini
[wsl2]
memory=8GB
processors=6
swap=4GB
localhostForwarding=true
```

Suggestion for a machine with 32 GB of RAM or more:

```ini
[wsl2]
memory=16GB
processors=8
swap=8GB
localhostForwarding=true
```

Do not use all the machine's RAM. Leave memory for Windows, the IDE, the browser, Docker Desktop, and other processes.

#### Restart WSL and Docker Desktop

After saving:

```powershell
wsl --shutdown
```

Close and reopen Docker Desktop.

Validate inside WSL:

```bash
nproc
free -h
```

#### Adjust file descriptors in the WSL session

In the terminal where the test will run:

```bash
ulimit -n
ulimit -n 200000
```

This adjustment applies to the current session only. For local testing, prefer keeping it temporary.

#### Validate during the test

```bash
docker stats
ss -s
ss -tan state established | wc -l
ss -tan state time-wait | wc -l
```

### 3.2. Windows without WSL 2 / Hyper-V backend

If Docker Desktop is using Hyper-V, adjust in:

```text
Docker Desktop → Settings → Resources → Advanced
```

Initial suggestion:

```text
CPUs: 6 to 8
Memory: 8 GB to 16 GB
Swap: 4 GB to 8 GB
```

Then apply and restart Docker Desktop if prompted.

### 3.3. Linux with native Docker Engine

On native Linux there is normally no Docker Desktop VM limiting CPU/memory. The focus is on:

- container limits;
- file descriptors;
- kernel network parameters;
- actual machine CPU/memory.

Validate resources:

```bash
nproc
free -h
ulimit -n
docker stats
```

Temporarily adjust file descriptors:

```bash
ulimit -n 200000
```

Temporarily adjust network parameters:

```bash
sudo sysctl -w net.core.somaxconn=65535
sudo sysctl -w net.ipv4.ip_local_port_range="1024 65535"
sudo sysctl -w net.ipv4.tcp_tw_reuse=1
```

Use the compose override to increase `mem_limit`, JVM heap, and application `ulimits`.

### 3.4. macOS with Docker Desktop

On macOS, Docker Desktop runs Linux containers inside a VM. Adjust in:

```text
Docker Desktop → Settings → Resources → Advanced
```

Initial suggestion:

```text
CPUs: 6 to 8
Memory: 8 GB to 16 GB
Swap: 4 GB to 8 GB
```

Then apply and restart Docker Desktop if necessary.

Also use the compose override to increase application resources, because increasing the Docker Desktop VM resources does not remove limits defined in `docker-compose.yaml`.

---

## 4. Validation before the test

### 4.1. Start the environment with override

```bash
docker compose \
  -f docker-compose.yaml \
  -f docker-compose.loadtest.override.yaml \
  --profile app_cluster \
  up -d --build
```

### 4.2. Check containers

```bash
docker compose --profile app_cluster ps
```

### 4.3. Validate Nginx

```bash
docker exec klimiter-nginx nginx -t
```

If the container name is different, use:

```bash
docker compose ps
```

And replace `klimiter-nginx` with the correct name.

### 4.4. Check logs

```bash
docker compose logs -f nginx app-1 app-2 app-3
```

### 4.5. Run a progressive test

Before a fixed high-volume test, run with a ramp-up:

```text
500 → 1000 → 2000 → 3000 → 4000 req/s
```

The goal is to identify at which point the following appear:

- `dropped_iterations`;
- `CANCELED`;
- `UNAVAILABLE`;
- `DEADLINE_EXCEEDED`;
- saturated CPU/memory;
- Nginx errors;
- application exceptions;
- Redis timeouts.

---

## 5. How to interpret symptoms

### 5.1. `CANCELED: context canceled while waiting for connections to become ready`

Likely causes:

- many gRPC connections starting at the same time;
- Nginx or the application taking too long to accept connections;
- Docker/WSL saturated;
- the test starts directly at high volume without a warm-up.

Actions:

1. use `ramping-arrival-rate`;
2. increase the connection timeout in k6;
3. validate `docker stats`;
4. add `ulimits` to the applications;
5. increase Docker/WSL resources if saturation is observed;
6. validate Nginx and application logs.

### 5.2. `UNAVAILABLE`

Likely causes:

- application unavailable;
- failing upstream;
- connection refused;
- Nginx cannot reach the application;
- application restarting or saturated.

Actions:

```bash
docker compose logs -f nginx app-1 app-2 app-3
```

### 5.3. `DEADLINE_EXCEEDED`

Likely causes:

- service taking too long to respond;
- Redis saturated;
- high CPU;
- JVM garbage collection;
- internal queuing.

Actions:

```bash
docker stats
```

Also check application logs and Redis metrics.

### 5.4. `RESOURCE_EXHAUSTED`

Likely causes:

- server-side resource limit reached;
- too many connections;
- internal queues exhausted;
- file descriptor limits;
- restriction in the proxy or gRPC runtime.

Actions:

- validate `ulimits`;
- validate `worker_connections` in Nginx;
- validate CPU/memory;
- reduce ramp-up aggressiveness;
- increase the number of application instances if appropriate for the test.

### 5.5. `dropped_iterations > 0`

Likely causes:

- k6 could not generate the requested rate;
- insufficient VUs;
- test machine saturated;
- latency increased and the executor could not maintain the arrival rate.

Actions:

- increase `preAllocatedVUs` and `maxVUs`;
- reduce the rate and scale up gradually;
- run k6 outside the saturated environment if necessary;
- check host machine CPU.

---

## 6. How to revert to the original state

### 6.1. If a temporary override was used

Stop the environment:

```bash
docker compose \
  -f docker-compose.yaml \
  -f docker-compose.loadtest.override.yaml \
  --profile app_cluster \
  down
```

Start again without the override:

```bash
docker compose --profile app_cluster up -d --build
```

Optionally remove the temporary file:

```bash
rm docker-compose.loadtest.override.yaml
```

### 6.2. If versioned files were edited directly

Check the changes:

```bash
git diff -- docker-compose.yaml docker/nginx/nginx.conf
```

Revert to the original Git state:

```bash
git restore docker-compose.yaml docker/nginx/nginx.conf
```

If the compose file has a different name, adjust the command:

```bash
git restore compose.yaml docker-compose.yml docker-compose.yaml docker/nginx/nginx.conf
```

### 6.3. If a manual backup was made

Before editing:

```bash
cp docker-compose.yaml docker-compose.yaml.bak
cp docker/nginx/nginx.conf docker/nginx/nginx.conf.bak
```

To restore:

```bash
mv docker-compose.yaml.bak docker-compose.yaml
mv docker/nginx/nginx.conf.bak docker/nginx/nginx.conf
```

### 6.4. If WSL was modified

Restore the original file:

```powershell
copy $env:USERPROFILE\.wslconfig.bak $env:USERPROFILE\.wslconfig
wsl --shutdown
```

To completely remove the custom configuration:

```powershell
remove-item $env:USERPROFILE\.wslconfig
wsl --shutdown
```

Then reopen Docker Desktop.

### 6.5. If `sysctl` was changed on Linux

If the adjustment was made with `sudo sysctl -w`, it is temporary and normally reverts to the default after a reboot.

To check current values:

```bash
sysctl net.core.somaxconn
sysctl net.ipv4.ip_local_port_range
sysctl net.ipv4.tcp_tw_reuse
```

If you wrote to `/etc/sysctl.conf` or `/etc/sysctl.d/*.conf`, remove the added lines and apply:

```bash
sudo sysctl --system
```

### 6.6. If Docker Desktop was changed on Windows/macOS

Revert manually in:

```text
Docker Desktop → Settings → Resources → Advanced
```

Reduce CPU, memory, and swap to the previous values and apply/restart Docker Desktop if prompted.

---

## 7. High-volume local testing checklist

Before the test:

- [ ] Use a k6 scenario with ramp-up.
- [ ] Avoid `console.error` per request.
- [ ] Record errors by metric/tag and generate a separate report.
- [ ] Validate `nginx -t`.
- [ ] Start multiple apps behind Nginx when the test is about load balancing or capacity.
- [ ] Use a temporary override to increase heap/memory for the applications.
- [ ] Apply `ulimits.nofile` to Nginx and the applications if needed.
- [ ] Increase Docker Desktop/WSL/Linux/macOS CPU and memory only if `docker stats` or the system indicates saturation.
- [ ] Monitor `docker stats`.
- [ ] Check logs for `nginx`, `app-1`, `app-2`, `app-3`.

During the test:

- [ ] Watch `dropped_iterations`.
- [ ] Watch `grpc_req_duration`.
- [ ] Watch error metrics by reason, such as `grpc_error_reason`.
- [ ] Watch container CPU and memory.
- [ ] Watch established connections and `TIME-WAIT`.

After the test:

- [ ] Generate an error report.
- [ ] Tear down the test environment.
- [ ] Remove the override or restore original files.
- [ ] Revert `.wslconfig`, Docker Desktop Resources, or `sysctl` if they were changed.

---

## 8. Useful commands

### Docker

```bash
docker stats
```

```bash
docker compose --profile app_cluster ps
```

```bash
docker compose logs -f nginx app-1 app-2 app-3
```

```bash
docker exec klimiter-nginx nginx -t
```

### Linux/WSL

```bash
nproc
free -h
ulimit -n
ss -s
ss -tan state established | wc -l
ss -tan state time-wait | wc -l
```

### Git

```bash
git diff
```

```bash
git restore docker-compose.yaml docker/nginx/nginx.conf
```

---

## 9. Recommendation for this project

For this project, the safest order is:

1. keep the current `nginx.conf`, because it is already prepared for gRPC with high local volume;
2. switch the test to ramp-up before attempting sustained high-rate load;
3. create `docker-compose.loadtest.override.yaml` to increase heap/memory and `ulimits` for the applications during the test;
4. increase Docker Desktop/WSL/Linux/macOS resources only if `docker stats` or the system indicates saturation;
5. at the end, remove the override and return to the original values.

Avoid turning local benchmark configurations into permanent project defaults. Values such as `-Xmx1024m`, `mem_limit: 1536m`, `nofile: 200000`, and WSL with 16 GB of RAM are useful for high-volume testing, but may not represent the ideal configuration for daily development or production.

---

## 10. Where to place this document

Since `docs/TROUBLESHOOTING.md` already exists, prefer one of the options below.

### Recommended option

Create a specific document:

```text
docs/troubleshooting/HIGH_VOLUME_LOAD_TESTING.md
```

And add a link in `docs/TROUBLESHOOTING.md`:

```md
## Load testing and high volume

- [High local volume with gRPC, Nginx, Docker, and WSL](./troubleshooting/HIGH_VOLUME_LOAD_TESTING.md)
```

This option keeps the main troubleshooting guide concise and avoids turning the general file into a very long document.

### Alternative option

Create directly:

```text
docs/HIGH_VOLUME_LOAD_TESTING.md
```

And reference it in `docs/TROUBLESHOOTING.md`.

This option works well if the `docs/` folder does not yet have subdirectories.

### Avoid

Avoid placing all this content directly in:

```text
docs/TROUBLESHOOTING.md
```

The content is specific, lengthy, and operational. Ideally `TROUBLESHOOTING.md` should work as an index or quick guide pointing to specific documents.

---

## 11. References

- Docker Desktop — WSL 2 backend: https://docs.docker.com/desktop/features/wsl/
- Docker Desktop — Settings / Resources: https://docs.docker.com/desktop/settings-and-maintenance/settings/
- Docker Engine — Resource constraints: https://docs.docker.com/engine/containers/resource_constraints/
- Microsoft — Advanced settings configuration in WSL: https://learn.microsoft.com/windows/wsl/wsl-config
