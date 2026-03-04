from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from pathlib import Path

from app.core.config import load_config
from app.core.logging_setup import setup_logging
from app.core.request_id import RequestIdMiddleware
from app.services.bl import BL

from app.features.days.router import router as days_router
from app.features.foods.router import router as foods_router
from app.features.settings.router import router as settings_router
from app.features.history.router import router as history_router


def create_app() -> FastAPI:
    cfg = load_config()
    logger = setup_logging(level=cfg.log_level)

    bl = BL(
        cfg.mongo_uri,
        cfg.mongo_db,
        logger=logger
    )

    app = FastAPI(title="Plan API")
    app.state.cfg = cfg
    app.state.logger = logger
    app.state.bl = bl

    app.add_middleware(RequestIdMiddleware)

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(days_router)
    app.include_router(foods_router)
    app.include_router(settings_router)
    app.include_router(history_router)

    web_dir = Path("/web")
    html_path = web_dir / "plan.html"
    static_dir = web_dir / "static"
    app.mount("/static", StaticFiles(directory=static_dir), name="static")

    @app.get("/", response_class=HTMLResponse)
    def root():
        env = app.state.cfg.app_env
        suffix = "" if env == "PROD" else f"{env}"

        html = html_path.read_text(encoding="utf-8")
        html = html.replace("<!--ENV_SUFFIX-->", suffix)
        html = html.replace("<!--APP_BASE_PATH-->", app.state.cfg.app_base_path)
        return html

    return app


app = create_app()