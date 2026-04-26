# KLimiter load-tests

Executa teste de carga gRPC com k6 e gera relatórios HTML.

A configuração principal fica no próprio arquivo k6:

```text
k6/rate-limit.js
```

A request fica em arquivo separado:

```text
requests/rate-limit-request.json
```

## Executar

```bash
python3 runner.py run
```

## Usar outro script k6

```bash
python3 runner.py run --script k6/outro-teste.js
```

## Regerar índice HTML

```bash
python3 runner.py rebuild-index
```

## Estrutura sugerida no repositório

```text
klimiter/
├── klimiter-core/
├── klimiter-redis/
├── klimiter-service/
└── load-tests/
    ├── runner.py
    ├── k6/
    │   └── rate-limit.js
    ├── requests/
    │   └── rate-limit-request.json
    └── klimiter_load/
```

O relatório HTML mantém alternância entre tema claro e escuro usando `localStorage`.
