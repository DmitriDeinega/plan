from dataclasses import dataclass
import os
from dotenv import load_dotenv


@dataclass(frozen=True)
class AppConfig:
    pg_dsn: str
    log_level: str
    app_env: str
    app_base_path: str


def _require_env(name: str) -> str:
    v = os.getenv(name)
    if v is None or v.strip() == "":
        raise RuntimeError(f"{name} is missing")
    return v.strip()


def load_config() -> AppConfig:
    load_dotenv(override=False)

    pg_dsn = _require_env("PG_DSN")
    log_level = _require_env("LOG_LEVEL").upper()

    app_env = _require_env("APP_ENV")
    app_base_path = _require_env("APP_BASE_PATH")

    return AppConfig(
        pg_dsn=pg_dsn,
        log_level=log_level,
        app_env=app_env,
        app_base_path=app_base_path
    )
