
#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from collections import defaultdict
from dataclasses import dataclass, asdict
from datetime import datetime
from pathlib import Path
from typing import Any

APP_DIR = Path(__file__).resolve().parent
RUNS_DIR = APP_DIR / "runs"
RUNS_DIR.mkdir(parents=True, exist_ok=True)

PROTO_FILE = APP_DIR.parent / "klimiter-service/src/main/proto/klimiter.proto"


@dataclass
class RunMetadata:
    run_id: str
    created_at: str
    grpc_host: str
    grpc_key: str
    grpc_value: str
    grpc_cost: int
    scenario: str
    rate: int
    duration: str
    pre_vus: int
    max_vus: int
    total: int
    allowed_ok: int
    rate_limited: int
    other: int
    avg_total_per_second: float
    avg_allowed_per_second: float
    avg_rate_limited_per_second: float
    avg_other_per_second: float


def make_k6_script(
        grpc_host: str,
        grpc_key: str,
        grpc_value: str,
        grpc_cost: int,
        scenario: str,
        rate: int,
        duration: str,
        pre_vus: int,
        max_vus: int,
) -> str:
    if scenario == "constant":
        options_block = f"""
export const options = {{
  scenarios: {{
    hello_test: {{
      executor: 'constant-arrival-rate',
      rate: {rate},
      timeUnit: '1s',
      duration: '{duration}',
      preAllocatedVUs: {pre_vus},
      maxVUs: {max_vus},
    }},
  }},
}};
""".strip()
    elif scenario == "rampup":
        options_block = f"""
export const options = {{
  scenarios: {{
    hello_test: {{
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: {pre_vus},
      maxVUs: {max_vus},
      stages: [
        {{ target: {max(1, rate // 4)}, duration: '15s' }},
        {{ target: {max(1, rate // 2)}, duration: '15s' }},
        {{ target: {rate}, duration: '{duration}' }},
      ],
    }},
  }},
}};
""".strip()
    elif scenario == "spike":
        spike_target = max(rate * 3, rate + 1)
        options_block = f"""
export const options = {{
  scenarios: {{
    hello_test: {{
      executor: 'ramping-arrival-rate',
      startRate: {max(1, rate // 3)},
      timeUnit: '1s',
      preAllocatedVUs: {pre_vus},
      maxVUs: {max_vus},
      stages: [
        {{ target: {rate}, duration: '10s' }},
        {{ target: {spike_target}, duration: '5s' }},
        {{ target: {rate}, duration: '10s' }},
        {{ target: {max(1, rate // 3)}, duration: '5s' }},
      ],
    }},
  }},
}};
""".strip()
    elif scenario == "mixed":
        spike_target = max(rate * 3, rate + 1)
        options_block = f"""
export const options = {{
  scenarios: {{
    constant_test: {{
      executor: 'constant-arrival-rate',
      rate: {rate},
      timeUnit: '1s',
      duration: '{duration}',
      preAllocatedVUs: {pre_vus},
      maxVUs: {max_vus},
    }},
    rampup_test: {{
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: {pre_vus},
      maxVUs: {max_vus},
      stages: [
        {{ target: {max(1, rate // 4)}, duration: '15s' }},
        {{ target: {max(1, rate // 2)}, duration: '15s' }},
        {{ target: {rate}, duration: '15s' }},
      ],
      startTime: '0s',
    }},
    spike_test: {{
      executor: 'ramping-arrival-rate',
      startRate: {max(1, rate // 3)},
      timeUnit: '1s',
      preAllocatedVUs: {pre_vus},
      maxVUs: {max_vus},
      stages: [
        {{ target: {rate}, duration: '10s' }},
        {{ target: {spike_target}, duration: '5s' }},
        {{ target: {rate}, duration: '10s' }},
        {{ target: {max(1, rate // 3)}, duration: '5s' }},
      ],
      startTime: '0s',
    }},
  }},
}};
""".strip()
    else:
        raise ValueError(f"Unsupported scenario: {scenario}")

    return f"""
import grpc from 'k6/net/grpc';
import {{ Counter }} from 'k6/metrics';

const client = new grpc.Client();
client.load(['.'], 'klimiter.proto');

const allowedCounter = new Counter('grpc_allowed');
const overLimitCounter = new Counter('grpc_over_limit');
const errorCounter = new Counter('grpc_error');

{options_block}

const GRPC_HOST = '{grpc_host}';
const KEY = '{grpc_key}';
const VALUE = '{grpc_value}';
const COST = {grpc_cost};

export default function () {{
  client.connect(GRPC_HOST, {{ plaintext: true }});

  const response = client.invoke('io.klimiter.KLimiterService/ShouldRateLimit', {{
    keys: [{{ key: KEY, value: VALUE, cost: COST }}],
  }});

  const decision = response.message && response.message.overallDecision;
  if (decision === 0 || decision === 'OK') {{
    allowedCounter.add(1);
  }} else if (decision === 1 || decision === 'OVER_LIMIT') {{
    overLimitCounter.add(1);
  }} else {{
    errorCounter.add(1);
  }}

  client.close();
}}
""".strip()


def ensure_k6() -> None:
    if shutil.which("k6") is None:
        print("Erro: k6 não encontrado no PATH.", file=sys.stderr)
        print("Instale o k6 e tente novamente.", file=sys.stderr)
        sys.exit(1)


def ensure_proto() -> None:
    if not PROTO_FILE.exists():
        print(f"Erro: arquivo proto não encontrado em {PROTO_FILE}", file=sys.stderr)
        sys.exit(1)


def run_k6(
        grpc_host: str,
        grpc_key: str,
        grpc_value: str,
        grpc_cost: int,
        scenario: str,
        rate: int,
        duration: str,
        pre_vus: int,
        max_vus: int,
        output_json: Path,
) -> None:
    ensure_k6()
    ensure_proto()

    script_content = make_k6_script(
        grpc_host=grpc_host,
        grpc_key=grpc_key,
        grpc_value=grpc_value,
        grpc_cost=grpc_cost,
        scenario=scenario,
        rate=rate,
        duration=duration,
        pre_vus=pre_vus,
        max_vus=max_vus,
    )

    with tempfile.TemporaryDirectory() as tmpdir:
        script_path = Path(tmpdir) / "k6_test.js"
        script_path.write_text(script_content, encoding="utf-8")
        shutil.copy(PROTO_FILE, Path(tmpdir) / "klimiter.proto")

        cmd = [
            "k6",
            "run",
            "--out",
            f"json={output_json}",
            str(script_path),
        ]
        subprocess.run(cmd, check=True)


def parse_second(iso_value: str) -> str:
    # Ex.: 2026-04-19T01:01:58.538903329-03:00 -> 2026-04-19T01:01:58
    return iso_value[:19]


def aggregate_results(results_path: Path) -> list[dict[str, Any]]:
    with results_path.open("r", encoding="utf-8") as f:
        points: list[dict[str, Any]] = []
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                continue

            if item.get("type") != "Point":
                continue

            metric = item.get("metric")
            if metric not in ("grpc_allowed", "grpc_over_limit", "grpc_error"):
                continue

            data = item.get("data", {})
            ts = data.get("time")
            if not ts:
                continue

            points.append({
                "second": parse_second(ts),
                "metric": metric,
                "value": data.get("value", 1),
            })

    grouped: dict[str, dict[str, Any]] = defaultdict(lambda: {
        "total": 0,
        "allowed_ok": 0,
        "rate_limited": 0,
        "other": 0,
    })

    for p in points:
        g = grouped[p["second"]]
        v = int(p["value"])
        g["total"] += v
        if p["metric"] == "grpc_allowed":
            g["allowed_ok"] += v
        elif p["metric"] == "grpc_over_limit":
            g["rate_limited"] += v
        else:
            g["other"] += v

    rows = []
    for second in sorted(grouped.keys()):
        rows.append({"second": second, **grouped[second]})
    return rows


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    if not rows:
        return {
            "total": 0,
            "allowed_ok": 0,
            "rate_limited": 0,
            "other": 0,
            "avg_total_per_second": 0.0,
            "avg_allowed_per_second": 0.0,
            "avg_rate_limited_per_second": 0.0,
            "avg_other_per_second": 0.0,
        }

    total = sum(r["total"] for r in rows)
    allowed = sum(r["allowed_ok"] for r in rows)
    rate_limited = sum(r["rate_limited"] for r in rows)
    other = sum(r["other"] for r in rows)
    count = len(rows)

    return {
        "total": total,
        "allowed_ok": allowed,
        "rate_limited": rate_limited,
        "other": other,
        "avg_total_per_second": total / count,
        "avg_allowed_per_second": allowed / count,
        "avg_rate_limited_per_second": rate_limited / count,
        "avg_other_per_second": other / count,
    }


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding="utf-8")


def build_detail_html(run_dir: Path, metadata: RunMetadata, rows: list[dict[str, Any]]) -> None:
    labels = [r["second"] for r in rows]
    totals = [r["total"] for r in rows]
    allowed = [r["allowed_ok"] for r in rows]
    ratelimited = [r["rate_limited"] for r in rows]
    other = [r["other"] for r in rows]

    table_rows = "\n".join(
        f"<tr><td>{escape(r['second'])}</td><td>{r['total']}</td><td>{r['allowed_ok']}</td><td>{r['rate_limited']}</td><td>{r['other']}</td></tr>"
        for r in rows
    )

    html = f"""<!DOCTYPE html>
<html lang="pt-BR">
{theme_head(f"Teste {metadata.run_id}")}
<body>
  {theme_toolbar()}
  <p><a href="../index.html">← Voltar para todos os testes</a></p>
  <h1>Teste {escape(metadata.run_id)}</h1>

  <div class="card">
    <strong>Endpoint:</strong> <code>{escape(metadata.grpc_host)}</code><br/>
    <strong>Key:</strong> <code>{escape(metadata.grpc_key)}</code> &nbsp; | &nbsp;
    <strong>Value:</strong> <code>{escape(metadata.grpc_value)}</code> &nbsp; | &nbsp;
    <strong>Cost:</strong> {metadata.grpc_cost}<br/>
    <strong>Executado em:</strong> {escape(metadata.created_at)}<br/>
    <strong>Scenario:</strong> {escape(metadata.scenario)} &nbsp; | &nbsp;
    <strong>Rate:</strong> {metadata.rate}/s &nbsp; | &nbsp;
    <strong>Duration:</strong> {escape(metadata.duration)} &nbsp; | &nbsp;
    <strong>preAllocatedVUs:</strong> {metadata.pre_vus} &nbsp; | &nbsp;
    <strong>maxVUs:</strong> {metadata.max_vus}
  </div>

  <div class="grid">
    <div class="metric"><div class="label">Total requests</div><div class="value">{metadata.total}</div></div>
    <div class="metric"><div class="label">Allowed (OK)</div><div class="value">{metadata.allowed_ok}</div></div>
    <div class="metric"><div class="label">Rate limit (OVER_LIMIT)</div><div class="value">{metadata.rate_limited}</div></div>
    <div class="metric"><div class="label">Others (ERROR)</div><div class="value">{metadata.other}</div></div>
  </div>

  <div class="grid">
    <div class="metric"><div class="label">Média total/s</div><div class="value">{metadata.avg_total_per_second:.2f}</div></div>
    <div class="metric"><div class="label">Média allowed/s</div><div class="value">{metadata.avg_allowed_per_second:.2f}</div></div>
    <div class="metric"><div class="label">Média rate limit/s</div><div class="value">{metadata.avg_rate_limited_per_second:.2f}</div></div>
    <div class="metric"><div class="label">Média others/s</div><div class="value">{metadata.avg_other_per_second:.2f}</div></div>
  </div>

  <div class="card">
    <h2>Gráfico por segundo</h2>
    <div class="chart-container"><canvas id="chart"></canvas></div>
  </div>

  <div class="card">
    <h2>Tabela por segundo</h2>
    <div class="table-wrapper">
      <table>
        <thead>
          <tr>
            <th>Second</th>
            <th>Total</th>
            <th>Allowed (OK)</th>
            <th>Rate Limit (OVER_LIMIT)</th>
            <th>Others (ERROR)</th>
          </tr>
        </thead>
        <tbody>
          {table_rows}
        </tbody>
      </table>
    </div>
  </div>

  <script>
    const labels = {json.dumps(labels, ensure_ascii=False)};
    const totalData = {json.dumps(totals)};
    const allowedData = {json.dumps(allowed)};
    const rateLimitedData = {json.dumps(ratelimited)};
    const otherData = {json.dumps(other)};

    const ctx = document.getElementById('chart').getContext('2d');
    window.detailChart = new Chart(ctx, {{
      type: 'line',
      data: {{
        labels,
        datasets: [
          {{ label: 'Total', data: totalData, borderColor: '#1f77b4', backgroundColor: 'rgba(31,119,180,0.12)', tension: 0.2, fill: false }},
          {{ label: 'Allowed (OK)', data: allowedData, borderColor: '#2ca02c', backgroundColor: 'rgba(44,160,44,0.12)', tension: 0.2, fill: false }},
          {{ label: 'Rate Limit (OVER_LIMIT)', data: rateLimitedData, borderColor: '#d62728', backgroundColor: 'rgba(214,39,40,0.12)', tension: 0.2, fill: false }},
          {{ label: 'Others (ERROR)', data: otherData, borderColor: '#9467bd', backgroundColor: 'rgba(148,103,189,0.12)', tension: 0.2, fill: false }},
        ]
      }},
      options: {{
        responsive: true,
        maintainAspectRatio: false,
        interaction: {{ mode: 'index', intersect: false }},
        plugins: {{ legend: {{ position: 'top' }} }},
        scales: {{
          x: {{ title: {{ display: true, text: 'Second' }} }},
          y: {{ beginAtZero: true, title: {{ display: true, text: 'Requests' }} }}
        }}
      }}
    }});
  </script>
  {theme_script('detailChart')}
</body>
</html>
"""
    (run_dir / "report.html").write_text(html, encoding="utf-8")


def escape(value: str) -> str:
    import html
    return html.escape(value, quote=True)


def theme_head(title: str) -> str:
    return f"""<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>{escape(title)}</title>
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <style>
    :root {{
      --bg: #f7f7f9;
      --surface: #ffffff;
      --surface-2: #f0f0f0;
      --text: #222222;
      --muted: #666666;
      --border: #dddddd;
      --link: #155dfc;
      --code-bg: #f1f1f1;
      --shadow: 0 1px 4px rgba(0,0,0,.06);
    }}

    html[data-theme="dark"] {{
      --bg: #0f1115;
      --surface: #171a21;
      --surface-2: #20242d;
      --text: #eef2f7;
      --muted: #a9b3c1;
      --border: #2b3340;
      --link: #7db2ff;
      --code-bg: #20242d;
      --shadow: 0 1px 4px rgba(0,0,0,.35);
    }}

    * {{ box-sizing: border-box; }}
    body {{
      font-family: Arial, sans-serif;
      margin: 24px;
      background: var(--bg);
      color: var(--text);
    }}

    a {{ color: var(--link); text-decoration: none; }}
    code {{
      background: var(--code-bg);
      padding: 2px 6px;
      border-radius: 6px;
    }}

    .toolbar {{
      display: flex;
      justify-content: flex-end;
      margin-bottom: 16px;
    }}

    .theme-toggle {{
      border: 1px solid var(--border);
      background: var(--surface);
      color: var(--text);
      border-radius: 10px;
      padding: 10px 14px;
      cursor: pointer;
      box-shadow: var(--shadow);
    }}

    .card {{
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 16px;
      margin-bottom: 20px;
      box-shadow: var(--shadow);
    }}

    .grid {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 12px;
      margin-bottom: 20px;
    }}

    .metric {{
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 16px;
    }}

    .metric .label {{
      font-size: 14px;
      color: var(--muted);
      margin-bottom: 8px;
    }}

    .metric .value {{
      font-size: 28px;
      font-weight: bold;
    }}

    .chart-container {{
      position: relative;
      height: 480px;
      width: 100%;
    }}

    table {{
      width: 100%;
      border-collapse: collapse;
      background: var(--surface);
    }}

    th, td {{
      border: 1px solid var(--border);
      padding: 10px;
      text-align: center;
      font-size: 14px;
    }}

    th {{
      background: var(--surface-2);
      position: sticky;
      top: 0;
    }}

    .table-wrapper {{
      max-height: 500px;
      overflow: auto;
      border-radius: 12px;
      border: 1px solid var(--border);
    }}
  </style>
</head>"""


def theme_toolbar() -> str:
    return """<div class="toolbar">
  <button id="themeToggle" class="theme-toggle" type="button">Alternar tema</button>
</div>"""


def theme_script(chart_var_name: str | None = None) -> str:
    chart_update = ""
    if chart_var_name:
        chart_update = f"""
      if (window.{chart_var_name}) {{
        window.{chart_var_name}.update();
      }}"""
    return f"""<script>
    (function() {{
      const root = document.documentElement;
      const stored = localStorage.getItem('report-theme');
      const preferredDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
      const initial = stored || (preferredDark ? 'dark' : 'light');
      root.setAttribute('data-theme', initial);

      const button = document.getElementById('themeToggle');
      if (button) {{
        button.addEventListener('click', function() {{
          const current = root.getAttribute('data-theme') || 'light';
          const next = current === 'dark' ? 'light' : 'dark';
          root.setAttribute('data-theme', next);
          localStorage.setItem('report-theme', next);{chart_update}
        }});
      }}
    }})();
  </script>"""


def build_index_html() -> None:
    runs = []
    for metadata_file in sorted(RUNS_DIR.glob("*/metadata.json"), reverse=True):
        try:
            metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
            runs.append(metadata)
        except Exception:
            continue

    rows = []
    for item in runs:
        run_id = item["run_id"]
        rows.append(
            f"<tr>"
            f"<td><a href='./{escape(run_id)}/report.html'>{escape(run_id)}</a></td>"
            f"<td>{escape(item['created_at'])}</td>"
            f"<td><code>{escape(item['grpc_host'])}</code></td>"
            f"<td><code>{escape(item['grpc_key'])}</code></td>"
            f"<td>{escape(item.get('scenario', 'constant'))}</td>"
            f"<td>{item['rate']}</td>"
            f"<td>{escape(item['duration'])}</td>"
            f"<td>{item['total']}</td>"
            f"<td>{item['allowed_ok']}</td>"
            f"<td>{item['rate_limited']}</td>"
            f"<td>{item['other']}</td>"
            f"<td>{item['avg_allowed_per_second']:.2f}</td>"
            f"</tr>"
        )

    body_rows = "\n".join(rows) if rows else "<tr><td colspan='12'>Nenhum teste executado ainda.</td></tr>"

    html = f"""<!DOCTYPE html>
<html lang="pt-BR">
{theme_head("Relatórios k6")}
<body>
  {theme_toolbar()}
  <h1>Todos os testes k6</h1>
  <div class="card">
    <p>Abra um teste para ver o gráfico por segundo.</p>
    <p>Diretório base: <code>{escape(str(RUNS_DIR))}</code></p>
  </div>

  <table>
    <thead>
      <tr>
        <th>Run ID</th>
        <th>Executado em</th>
        <th>gRPC Host</th>
        <th>Key</th>
        <th>Scenario</th>
        <th>Rate</th>
        <th>Duration</th>
        <th>Total</th>
        <th>Allowed (OK)</th>
        <th>Rate Limit (OVER_LIMIT)</th>
        <th>Others (ERROR)</th>
        <th>Média allowed/s</th>
      </tr>
    </thead>
    <tbody>
      {body_rows}
    </tbody>
  </table>
  {theme_script()}
</body>
</html>
"""
    (RUNS_DIR / "index.html").write_text(html, encoding="utf-8")


def do_run(args: argparse.Namespace) -> None:
    run_id = datetime.now().strftime("%Y%m%d_%H%M%S")
    run_dir = RUNS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=False)

    results_path = run_dir / "results.json"

    run_k6(
        grpc_host=args.grpc_host,
        grpc_key=args.key,
        grpc_value=args.value,
        grpc_cost=args.cost,
        scenario=args.scenario,
        rate=args.rate,
        duration=args.duration,
        pre_vus=args.pre_vus,
        max_vus=args.max_vus,
        output_json=results_path,
    )

    rows = aggregate_results(results_path)
    write_json(run_dir / "aggregated.json", rows)

    summary = summarize(rows)
    metadata = RunMetadata(
        run_id=run_id,
        created_at=datetime.now().isoformat(timespec="seconds"),
        grpc_host=args.grpc_host,
        grpc_key=args.key,
        grpc_value=args.value,
        grpc_cost=args.cost,
        scenario=args.scenario,
        rate=args.rate,
        duration=args.duration,
        pre_vus=args.pre_vus,
        max_vus=args.max_vus,
        total=summary["total"],
        allowed_ok=summary["allowed_ok"],
        rate_limited=summary["rate_limited"],
        other=summary["other"],
        avg_total_per_second=summary["avg_total_per_second"],
        avg_allowed_per_second=summary["avg_allowed_per_second"],
        avg_rate_limited_per_second=summary["avg_rate_limited_per_second"],
        avg_other_per_second=summary["avg_other_per_second"],
    )
    write_json(run_dir / "metadata.json", asdict(metadata))
    build_detail_html(run_dir, metadata, rows)
    build_index_html()

    print(f"Teste executado com sucesso: {run_id}")
    print(f"Página do teste: {run_dir / 'report.html'}")
    print(f"Índice geral: {RUNS_DIR / 'index.html'}")


def do_rebuild_index(_: argparse.Namespace) -> None:
    build_index_html()
    print(f"Índice reconstruído: {RUNS_DIR / 'index.html'}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Executa testes k6 via gRPC e gera relatórios HTML.")
    sub = parser.add_subparsers(dest="command", required=True)

    run_parser = sub.add_parser("run", help="Executa um teste k6 e gera o relatório.")
    run_parser.add_argument("--grpc-host", default="localhost:9090", help="Host gRPC do klimiter-service.")
    run_parser.add_argument("--key", default="user_id", help="Nome da chave de rate limit (KeyRequest.key).")
    run_parser.add_argument("--value", default="test-user", help="Valor da chave de rate limit (KeyRequest.value).")
    run_parser.add_argument("--cost", type=int, default=1, help="Custo da requisição (KeyRequest.cost).")
    run_parser.add_argument("--scenario", choices=["constant", "rampup", "spike", "mixed"], default="constant", help="Cenário de carga do k6.")
    run_parser.add_argument("--rate", type=int, default=300, help="Requisições por segundo alvo.")
    run_parser.add_argument("--duration", default="30s", help="Duração do teste no formato do k6.")
    run_parser.add_argument("--pre-vus", type=int, default=100, help="preAllocatedVUs do k6.")
    run_parser.add_argument("--max-vus", type=int, default=1000, help="maxVUs do k6.")
    run_parser.set_defaults(func=do_run)

    rebuild_parser = sub.add_parser("rebuild-index", help="Reconstrói a página com todos os testes.")
    rebuild_parser.set_defaults(func=do_rebuild_index)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()