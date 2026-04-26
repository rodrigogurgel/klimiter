import shutil
import subprocess
import sys
from pathlib import Path
from klimiter_load.paths import APP_DIR

def ensure_k6() -> None:
    if shutil.which("k6") is None:
        print("Erro: k6 não encontrado no PATH.", file=sys.stderr)
        print("Instale o k6 e tente novamente.", file=sys.stderr)
        sys.exit(1)

def ensure_file(path: Path, label: str) -> None:
    if not path.exists():
        print(f"Erro: {label} não encontrado em {path}", file=sys.stderr)
        sys.exit(1)

def run_k6(script_path: Path, output_json: Path, responses_path: Path | None = None) -> None:
    ensure_k6()
    ensure_file(script_path, "arquivo k6")

    cmd = [
        "k6",
        "run",
        "--out",
        f"json={output_json}",
    ]

    if responses_path is not None:
        cmd += [
            "--log-output", f"file={responses_path}",
            "--log-format", "json",
            "-e", "SAVE_RESPONSES=true",
        ]

    cmd.append(str(script_path))

    subprocess.run(cmd, check=True, cwd=APP_DIR)
