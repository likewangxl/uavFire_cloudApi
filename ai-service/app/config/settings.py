from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    host: str = "0.0.0.0"
    port: int = 9000
    log_level: str = "INFO"
    max_concurrent_tasks: int = 2
    backend_base_url: str = ""
    use_continuous_runner: bool = False

    model_config = SettingsConfigDict(
        env_prefix="AI_SERVICE_",
        case_sensitive=False,
    )
