# Deployment (Kubernetes / EKS)

Manifestos de referência para rodar o **klimiter** num cluster Kubernetes. São um ponto de partida
genérico — ajuste imagem, recursos e políticas ao seu ambiente. As **decisões de runtime** por trás
destes valores (CPU/QoS, heap do ZGC, Fargate, escala por cores/pods) estão em
[`docs/SATURACAO.md §8`](../docs/SATURACAO.md) e [`docs/OTIMIZACAO-THROUGHPUT.md`](../docs/OTIMIZACAO-THROUGHPUT.md).

## Conteúdo (`kubernetes/eks/`)

| Arquivo | Recurso |
|---------|---------|
| `namespace.yaml` | `Namespace` klimiter (todos os demais recursos vivem nele). |
| `redis-secret.example.yaml` | Exemplo do `Secret` `klimiter-redis` (chave `uri`). **Não é aplicado direto** — veja abaixo. |
| `configmap-policies.yaml` | `ConfigMap` com o `policies.yaml` montado em `/etc/klimiter`. |
| `deployment.yaml` | `Deployment` do serviço (3 réplicas, QoS Guaranteed, probes gRPC nativas). |
| `service.yaml` | `Service` ClusterIP expondo o gRPC na porta 9090. |
| `hpa-pdb.yaml` | `HorizontalPodAutoscaler` (CPU 70%) + `PodDisruptionBudget`. |

## Pré-requisitos

- Kubernetes **≥ 1.24** (usa probe gRPC nativa, sem `grpc_health_probe`).
- Uma imagem do klimiter publicada num registry acessível pelo cluster.
- Um Redis alcançável pelos pods (ElastiCache ou Redis no cluster — de preferência **fora** do nó do limiter).

## Como aplicar

```bash
cd deployments/kubernetes/eks

# 1. Namespace primeiro
kubectl apply -f namespace.yaml

# 2. Secret do Redis — NÃO commite o real. Preencha a partir do exemplo, ou crie via CLI:
kubectl -n klimiter create secret generic klimiter-redis \
  --from-literal=uri='redis://<host>:6379'        # use rediss:// para ElastiCache com TLS

# 3. Ajuste a imagem no deployment.yaml (campo `image:`), depois aplique o resto
kubectl apply -f configmap-policies.yaml -f deployment.yaml -f service.yaml -f hpa-pdb.yaml
```

> A ordem importa: `namespace` → `secret` + `configmap` → `deployment`/`service`/`hpa-pdb`.

## Atualização de políticas (hot reload × ConfigMap)

O hot reload de políticas ([`docs/POLITICAS.md §6.1`](../docs/POLITICAS.md)) usa o `WatchService`
do JDK sobre o diretório do arquivo — e **isso não funciona com ConfigMap montada como volume**: o
kubelet propaga o update por troca atômica de symlink (`..data`), o arquivo `policies.yaml` nunca
gera evento de criação/modificação e o reload não dispara. Com estes manifestos, **mudar a
ConfigMap não aplica ao vivo**.

Aplique políticas novas com um restart controlado (rolling, sem downtime — o PDB cobre):

```bash
kubectl -n klimiter apply -f configmap-policies.yaml
kubectl -n klimiter rollout restart deployment/klimiter
```

## Segurança (premissas)

Estes manifestos assumem tráfego **interno ao cluster**: o gRPC é **plaintext e sem autenticação**,
exposto só por um `Service` ClusterIP (sem Ingress). Não exponha a porta 9090 para fora do cluster
sem colocar TLS/mTLS e autenticação na frente (ex.: service mesh — note que o `deployment.yaml`
desliga a injeção do sidecar Istio; reavalie se o mesh for a sua camada de mTLS). A conexão com o
Redis aceita TLS via `rediss://` no `Secret`.

## O que ajustar antes de subir

- **`deployment.yaml`** — `image:` (aponte para o seu registry) e `KLIMITER_REDIS_POOL_SIZE`
  (dimensione conforme `ActiveProcessorCount`/carga). O `JAVA_TOOL_OPTIONS` assume ~2 cores dedicados.
- **`configmap-policies.yaml`** — os caps do `policies.yaml` (formato em [`docs/POLITICAS.md`](../docs/POLITICAS.md)).
- **`hpa-pdb.yaml`** — `minReplicas`/`maxReplicas` conforme sua carga.
- **Agendamento** — estes manifestos são genéricos (sem `nodeSelector`/`tolerations`). Para isolar o
  limiter num pool dedicado, adicione `nodeSelector` + `tolerations` de volta ao `deployment.yaml`.
- **Fargate** — sem co-locar Redis; veja as especificidades em [`docs/SATURACAO.md §8.3`](../docs/SATURACAO.md).
