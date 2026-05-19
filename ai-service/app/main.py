import logging

from fastapi import FastAPI

from app.api.routes import router


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s | %(message)s",
    force=True,
)

app = FastAPI(title="AI Service")
app.include_router(router)
