from pathlib import Path

APP_DIR = Path(__file__).resolve().parents[1]
RUNS_DIR = APP_DIR / "runs"
DEFAULT_K6_SCRIPT = APP_DIR / "k6/rate-limit.js"
RUNS_DIR.mkdir(parents=True, exist_ok=True)
