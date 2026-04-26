import json
from collections import defaultdict
from pathlib import Path
from typing import Any

def parse_second(iso_value: str) -> str:
    return iso_value[:19]

def aggregate_results(results_path: Path) -> list[dict[str, Any]]:
    points: list[dict[str, Any]] = []

    with results_path.open("r", encoding="utf-8") as f:
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

            points.append({"second": parse_second(ts), "metric": metric, "value": data.get("value", 1)})

    grouped: dict[str, dict[str, Any]] = defaultdict(
        lambda: {"total": 0, "allowed_ok": 0, "rate_limited": 0, "other": 0}
    )

    for point in points:
        row = grouped[point["second"]]
        value = int(point["value"])
        row["total"] += value

        if point["metric"] == "grpc_allowed":
            row["allowed_ok"] += value
        elif point["metric"] == "grpc_over_limit":
            row["rate_limited"] += value
        else:
            row["other"] += value

    return [{"second": second, **grouped[second]} for second in sorted(grouped.keys())]

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

    total = sum(row["total"] for row in rows)
    allowed = sum(row["allowed_ok"] for row in rows)
    rate_limited = sum(row["rate_limited"] for row in rows)
    other = sum(row["other"] for row in rows)
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
