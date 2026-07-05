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

## O que ajustar antes de subir

- **`deployment.yaml`** — `image:` (aponte para o seu registry) e `KLIMITER_REDIS_POOL_SIZE`
  (dimensione conforme `ActiveProcessorCount`/carga). O `JAVA_TOOL_OPTIONS` assume ~2 cores dedicados.
- **`configmap-policies.yaml`** — os caps do `policies.yaml` (formato em [`docs/POLITICAS.md`](../docs/POLITICAS.md)).
- **`hpa-pdb.yaml`** — `minReplicas`/`maxReplicas` conforme sua carga.
- **Agendamento** — estes manifestos são genéricos (sem `nodeSelector`/`tolerations`). Para isolar o
  limiter num pool dedicado, adicione `nodeSelector` + `tolerations` de volta ao `deployment.yaml`.
- **Fargate** — sem co-locar Redis; veja as especificidades em [`docs/SATURACAO.md §8.3`](../docs/SATURACAO.md).
