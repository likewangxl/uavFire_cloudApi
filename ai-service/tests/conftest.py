from app.config.settings import Settings

# Tests assume Settings() defaults. Disable .env loading at import time so even
# module-level Settings() instances (e.g. app.main._settings) stay isolated
# from the developer's local .env. conftest is imported before test modules,
# hence before app.main.
Settings.model_config["env_file"] = None
