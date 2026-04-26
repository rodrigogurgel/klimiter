from datetime import datetime
from pathlib import Path

from klimiter_load.aggregation import aggregate_results, summarize
from klimiter_load.executor import run_k6
from klimiter_load.io_utils import write_json
from klimiter_load.models import RunMetadata
from klimiter_load.paths import RUNS_DIR
from klimiter_load.report import build_index_html, persist_report


def do_run(args) -> None:
    script_path = Path(args.script).resolve()
    run_id = datetime.now().strftime("%Y%m%d_%H%M%S")
    run_dir = RUNS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=False)

    results_path = run_dir / "results.json"
    responses_path = (run_dir / "responses.jsonl") if getattr(args, "save_responses", False) else None
    run_k6(script_path, results_path, responses_path)

    rows = aggregate_results(results_path)
    write_json(run_dir / "aggregated.json", rows)

    summary = summarize(rows)
    metadata = RunMetadata(
        run_id=run_id,
        created_at=datetime.now().isoformat(timespec="seconds"),
        k6_script=str(script_path),
        total=summary["total"],
        allowed_ok=summary["allowed_ok"],
        rate_limited=summary["rate_limited"],
        other=summary["other"],
        avg_total_per_second=summary["avg_total_per_second"],
        avg_allowed_per_second=summary["avg_allowed_per_second"],
        avg_rate_limited_per_second=summary["avg_rate_limited_per_second"],
        avg_other_per_second=summary["avg_other_per_second"],
    )

    persist_report(run_dir, metadata, rows)

    print(f"Teste executado com sucesso: {run_id}")
    print(f"Página do teste: {run_dir / 'report.html'}")
    print(f"Índice geral: {RUNS_DIR / 'index.html'}")
    if responses_path is not None:
        print(f"Respostas completas: {responses_path}")


def do_rebuild_index(_) -> None:
    build_index_html()
    print(f"Índice reconstruído: {RUNS_DIR / 'index.html'}")
