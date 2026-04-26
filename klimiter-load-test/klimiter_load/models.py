from dataclasses import dataclass

@dataclass
class RunMetadata:
    run_id: str
    created_at: str
    k6_script: str
    total: int
    allowed_ok: int
    rate_limited: int
    other: int
    avg_total_per_second: float
    avg_allowed_per_second: float
    avg_rate_limited_per_second: float
    avg_other_per_second: float
