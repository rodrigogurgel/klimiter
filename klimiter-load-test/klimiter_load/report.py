import html
import json
from dataclasses import asdict
from pathlib import Path

from klimiter_load.io_utils import write_json
from klimiter_load.models import RunMetadata
from klimiter_load.paths import RUNS_DIR


def esc(value: object) -> str:
    return html.escape(str(value), quote=True)


def theme_head(title: str) -> str:
    return f"""<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>{esc(title)}</title>
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
      box-shadow: var(--shadow);
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
          localStorage.setItem('report-theme', next);
{chart_update}
        }});
      }}
    }})();
  </script>"""


def build_detail_html(run_dir: Path, metadata: RunMetadata, rows: list[dict]) -> None:
    labels = [row["second"] for row in rows]
    totals = [row["total"] for row in rows]
    allowed = [row["allowed_ok"] for row in rows]
    limited = [row["rate_limited"] for row in rows]
    other = [row["other"] for row in rows]

    table_rows = "\n".join(
        f"<tr>"
        f"<td>{esc(row['second'])}</td>"
        f"<td>{row['total']}</td>"
        f"<td>{row['allowed_ok']}</td>"
        f"<td>{row['rate_limited']}</td>"
        f"<td>{row['other']}</td>"
        f"</tr>"
        for row in rows
    )

    html_content = f"""<!DOCTYPE html>
<html lang="pt-BR">
{theme_head(f"Teste {metadata.run_id}")}
<body>
  {theme_toolbar()}
  <p><a href="../index.html">← Voltar para todos os testes</a></p>

  <h1>Teste {esc(metadata.run_id)}</h1>

  <div class="card">
    <strong>Script k6:</strong> <code>{esc(metadata.k6_script)}</code><br/>
    <strong>Executado em:</strong> {esc(metadata.created_at)}
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
    const rateLimitedData = {json.dumps(limited)};
    const otherData = {json.dumps(other)};

    const ctx = document.getElementById('chart').getContext('2d');
    window.detailChart = new Chart(ctx, {{
      type: 'line',
      data: {{
        labels,
        datasets: [
          {{ label: 'Total', data: totalData, tension: 0.2, fill: false }},
          {{ label: 'Allowed (OK)', data: allowedData, tension: 0.2, fill: false }},
          {{ label: 'Rate Limit (OVER_LIMIT)', data: rateLimitedData, tension: 0.2, fill: false }},
          {{ label: 'Others (ERROR)', data: otherData, tension: 0.2, fill: false }},
        ]
      }},
      options: {{
        responsive: true,
        maintainAspectRatio: false,
        interaction: {{ mode: 'index', intersect: false }},
        plugins: {{ legend: {{ position: 'top' }} }},
        scales: {{ y: {{ beginAtZero: true }} }}
      }}
    }});
  </script>

  {theme_script('detailChart')}
</body>
</html>"""

    (run_dir / "report.html").write_text(html_content, encoding="utf-8")


def build_index_html() -> None:
    rows = []

    for metadata_file in sorted(RUNS_DIR.glob("*/metadata.json"), reverse=True):
        try:
            item = json.loads(metadata_file.read_text(encoding="utf-8"))
        except Exception:
            continue

        run_id = item["run_id"]
        rows.append(
            f"<tr>"
            f"<td><a href='./{esc(run_id)}/report.html'>{esc(run_id)}</a></td>"
            f"<td>{esc(item['created_at'])}</td>"
            f"<td><code>{esc(item.get('k6_script', '-'))}</code></td>"
            f"<td>{item['total']}</td>"
            f"<td>{item['allowed_ok']}</td>"
            f"<td>{item['rate_limited']}</td>"
            f"<td>{item['other']}</td>"
            f"<td>{item['avg_allowed_per_second']:.2f}</td>"
            f"</tr>"
        )

    body_rows = "\n".join(rows) if rows else "<tr><td colspan='8'>Nenhum teste executado ainda.</td></tr>"

    html_content = f"""<!DOCTYPE html>
<html lang="pt-BR">
{theme_head("Relatórios k6")}
<body>
  {theme_toolbar()}

  <h1>Todos os testes k6</h1>

  <div class="card">
    <p>Abra um teste para ver o gráfico por segundo.</p>
    <p>Diretório base: <code>{esc(str(RUNS_DIR))}</code></p>
  </div>

  <div class="table-wrapper">
    <table>
      <thead>
        <tr>
          <th>Run ID</th>
          <th>Executado em</th>
          <th>Script k6</th>
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
  </div>

  {theme_script()}
</body>
</html>"""

    (RUNS_DIR / "index.html").write_text(html_content, encoding="utf-8")


def persist_report(run_dir: Path, metadata: RunMetadata, rows: list[dict]) -> None:
    write_json(run_dir / "metadata.json", asdict(metadata))
    build_detail_html(run_dir, metadata, rows)
    build_index_html()
