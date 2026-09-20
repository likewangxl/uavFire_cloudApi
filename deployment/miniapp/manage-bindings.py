"""Server-only wrapper for the current release's binding review tool; never prints credentials."""
import json
import os
from pathlib import Path
import re
import runpy

root = Path(__file__).resolve().parents[1]
config = dict((key, json.loads(value)) for key, value in (
    row.split('=', 1) for row in (root / 'config/miniapp.env').read_text().splitlines() if row.strip()))
database = re.search(r'^jdbc:mysql://127\.0\.0\.1:3306/([a-zA-Z0-9_]+)\?', config['MINIAPP_DB_JDBC_URL'])
if not database:
    raise SystemExit('Unexpected database target; review server configuration.')
os.environ.update(MINIAPP_DB_HOST='127.0.0.1', MINIAPP_DB_PORT='3306', MINIAPP_DB_NAME=database.group(1),
                  MINIAPP_DB_USER=config['MINIAPP_DB_USER'], MINIAPP_DB_PASSWORD=config['MINIAPP_DB_PASSWORD'])
runpy.run_path(str(root / 'current/miniapp-bindings.py'), run_name='__main__')
