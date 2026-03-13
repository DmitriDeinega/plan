from dataclasses import dataclass
import os
from dotenv import load_dotenv


@dataclass(frozen=True)
class AppConfig:
    mongo_uri: str
    mongo_db: str
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

    mongo_uri = _require_env("MONGO_URI")
    mongo_db = _require_env("MONGO_DB")
    log_level = _require_env("LOG_LEVEL").upper()

    app_env = _require_env("APP_ENV")
    app_base_path = _require_env("APP_BASE_PATH")

    return AppConfig(
        mongo_uri=mongo_uri,
        mongo_db=mongo_db,
        log_level=log_level,
        app_env=app_env,
        app_base_path=app_base_path
    )