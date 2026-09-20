-- Apply explicitly before enabling MINIAPP_ENABLED. No existing business rows are modified.
CREATE TABLE IF NOT EXISTS miniapp_identity (
  identity_id VARCHAR(36) PRIMARY KEY,
  app_id VARCHAR(64) NOT NULL,
  open_id VARCHAR(128) NOT NULL,
  binding_status VARCHAR(24) NOT NULL,
  requested_username VARCHAR(128),
  display_name VARCHAR(80),
  organization VARCHAR(160),
  user_id VARCHAR(64),
  workspace_id VARCHAR(64),
  reviewed_by VARCHAR(128),
  review_reason VARCHAR(255),
  create_time BIGINT NOT NULL,
  update_time BIGINT NOT NULL,
  UNIQUE KEY uk_miniapp_openid (app_id, open_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS miniapp_login_ticket (
  ticket_hash VARCHAR(64) PRIMARY KEY,
  identity_id VARCHAR(36) NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  expires_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS miniapp_session (
  session_id VARCHAR(36) PRIMARY KEY,
  identity_id VARCHAR(36) NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  access_hash VARCHAR(64) NOT NULL,
  refresh_hash VARCHAR(64) NOT NULL,
  access_expires_at BIGINT NOT NULL,
  refresh_expires_at BIGINT NOT NULL,
  revoked BOOLEAN NOT NULL DEFAULT FALSE,
  create_time BIGINT NOT NULL,
  UNIQUE KEY uk_miniapp_access (access_hash),
  UNIQUE KEY uk_miniapp_refresh (refresh_hash),
  KEY ix_miniapp_identity (identity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS miniapp_user_state (
  user_id VARCHAR(64) NOT NULL,
  workspace_id VARCHAR(64) NOT NULL,
  state_key VARCHAR(160) NOT NULL,
  state_value VARCHAR(512) NOT NULL,
  update_time BIGINT NOT NULL,
  PRIMARY KEY (user_id, workspace_id, state_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS miniapp_binding_audit (
  audit_id VARCHAR(36) PRIMARY KEY,
  identity_id VARCHAR(36) NOT NULL,
  action VARCHAR(24) NOT NULL,
  actor VARCHAR(128) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  create_time BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
