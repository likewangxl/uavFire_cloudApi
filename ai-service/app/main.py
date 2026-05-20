import logging
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from app.api.routes import router
from app.config.settings import Settings


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s | %(message)s",
    force=True,
)

_settings = Settings()
_snapshot_dir = Path(_settings.snapshot_dir)
_snapshot_dir.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="AI Service")
# 前端 (vite :8080) 直接通过 <img src> 拉 ai-service 静态图片，需要简单跨域。
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET"],
    allow_headers=["*"],
)
app.include_router(router)
app.mount(
    "/api/v1/snapshots",
    StaticFiles(directory=str(_snapshot_dir)),
    name="snapshots",
)
