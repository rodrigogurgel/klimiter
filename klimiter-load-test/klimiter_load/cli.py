import argparse

from klimiter_load.commands import do_rebuild_index, do_run
from klimiter_load.paths import DEFAULT_K6_SCRIPT


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Executa um script k6 gRPC e gera relatórios HTML."
    )

    sub = parser.add_subparsers(dest="command", required=True)

    run_parser = sub.add_parser("run", help="Executa o script k6 e gera relatório.")
    run_parser.add_argument(
        "--script",
        default=str(DEFAULT_K6_SCRIPT),
        help="Arquivo .js do k6. A configuração principal fica nele.",
    )
    run_parser.set_defaults(func=do_run)

    rebuild_parser = sub.add_parser(
        "rebuild-index",
        help="Reconstrói a página com todos os testes.",
    )
    rebuild_parser.set_defaults(func=do_rebuild_index)

    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)
