# Changelog

Todas as mudanças notáveis deste projeto são documentadas aqui.

O formato segue [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/)
e o versionamento segue [Semantic Versioning](https://semver.org/lang/pt-BR/).

Este arquivo é **gerado automaticamente** pelo [git-cliff](https://git-cliff.org)
a partir do histórico de commits — não edite à mão. Veja `cliff.toml` e o
[CONTRIBUTING.md](CONTRIBUTING.md#changelog).

## [0.2.0] - 2026-07-05

### Adicionado

- Eks manifests

### Corrigido

- **deployments:** Dimensiona heap da JVM pela regra >=1GB/core (SATURACAO.md §8)

### Documentação

- **deployments:** Documenta manifestos EKS e generaliza o deployment

### Manutenção

- **compose:** Alinha heap da JVM ao manifesto (Initial=Max=60)

## [0.1.2] - 2026-06-28

### Corrigido

- **compose:** Usa MANAGEMENT_OTLP_METRICS_EXPORT_STEP para export a 10s

## [0.1.1] - 2026-06-28

### Manutenção

- **make:** Torna o taskset opcional no sat-server para suportar macOS
- **compose:** Exporta métricas OTLP a cada 10s no ambiente local

## [0.1.0] - 2026-06-28

### Adicionado

- **redis:** Suporta Redis Cluster atrás da flag klimiter.redis.cluster
- **policy-config:** Lê detailed_metric do YAML e reconcilia meters no reload
- **metrics:** Contador klimiter.policy.reserve com nome por policy e reconciliação eager
- **application:** Emite a métrica por policy na reserva
- **metrics-port:** Adiciona policyReserve e syncDetailedPolicies na porta
- **policy:** Adiciona flag detailed_metric e expõe override/keys de meter na resolução
- **config:** Conecta engine, observabilidade e eviction
- **adapter:** Serviço gRPC de rate limit
- **adapter:** Métricas via Micrometer
- **adapter:** Relógio do sistema para a porta Clock
- **adapter:** Contador global no Redis via Lettuce
- **core:** Implementa avaliação de lote e reserva de capacidade
- **core:** Define portas inbound e outbound da engine
- **core:** Adiciona modelo de domínio do rate limiter
- **policy:** Add policies hot reload via WatchService
- **policy:** Add policy config format, core types and YAML loader
- **proto:** Adiciona contrato gRPC do rate limiter

### Corrigido

- **make:** Aponta o load-test para o Grafana Explore (sem dashboard provisionado)

### Performance

- **batch:** Resolve o bucket uma vez por item e faz fan-out só do que vai ao central
- **redis:** Lê o resultado do script sem List<Long> intermediária
- **redis:** Aplica o TTL só na criação da chave, dispensando o PTTL por chamada

### Refatorado

- **quality:** Elimina as 7 issues apontadas pelo Sonar
- Move test/load -> scripts/load-test e atualiza referências
- **policy:** Migra logs do adapter para SLF4J fluente
- **config:** Migra application.properties para application.yaml
- **proto:** Renomeia campo dimension para key no descritor
- **proto:** Renomeia enum Verdict para Status

### Documentação

- **contributing:** Corrige a versão do Gradle no pré-requisitos (9.5.1 -> 9.6.0)
- Sincroniza a versão do Gradle na documentação (9.5/9.5.1 -> 9.6.0)
- **readme:** Documenta os modos standalone/cluster mutuamente exclusivos
- Documenta KLIMITER_REDIS_CLUSTER e o modo cluster
- **core:** Corrige link KDoc não resolvido de Clock em LocalBudget
- Documenta detailed_metric e a métrica klimiter.policy.reserve
- Adiciona README.md (front-door do projeto)
- Sensibilidade do LOW ao RTT do Redis (Fargate) — medido via tc netem
- **saturacao:** Recomendações de deployment (K8s/Fargate) + sweep de heap
- Relatório de throughput/dimensionamento + premissas de transferência p/ Kubernetes
- Documenta saturação e atualiza observabilidade/variáveis
- Adiciona guias de arquitetura, observabilidade e variáveis de ambiente

### Testes

- **redis:** IT do modo cluster e ajuste do IT standalone à nova fábrica
- Amplia cobertura (83.4% -> 85.9%; branch 76.4% -> 80.7%)
- **load:** Scripts de carga e saturação
- **core:** Adiciona dublês de teste para as portas outbound
- **arch:** Enforce hexagonal boundaries with konsist (R7)

### Build & CI

- Empacotamento Docker e Makefile
- Adiciona Lettuce e Testcontainers para a engine
- Amplia tooling, dependências e fluxo de versões

### Manutenção

- **compose:** Profiles standalone/redis-cluster e cluster local de 3 nós
- **sonar:** Make sonar-local — semeia a senha do admin e roda a análise local
- **compose:** Pina o SonarQube em 26.6.0.123539-community
- **compose:** Adiciona profile 'sonar' para SonarQube local
- Bootstrap do projeto klimiter


