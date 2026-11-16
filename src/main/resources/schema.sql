-- FaceAuth MFA database schema
-- Portable between H2 (MySQL mode) and MySQL 8.

CREATE TABLE IF NOT EXISTS users (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    username          VARCHAR(50)  NOT NULL UNIQUE,
    email             VARCHAR(255) NOT NULL UNIQUE,
    password_hash     VARCHAR(100) NOT NULL,          -- BCrypt hash, never the plain password
    totp_secret       VARCHAR(64),                    -- Base32 secret for the authenticator app
    totp_enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    last_totp_step    BIGINT       NOT NULL DEFAULT 0, -- blocks re-use of the same 6-digit code
    face_enrolled     BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_attempts   INT          NOT NULL DEFAULT 0,
    locked_until      TIMESTAMP    NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One row per enrolled face sample. The 128 numbers produced by the AI model
-- are stored as a JSON array, e.g. [0.0123, -0.0871, ...]
CREATE TABLE IF NOT EXISTS face_descriptors (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    descriptor_json VARCHAR(4000) NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Audit trail of every authentication step (shown on the dashboard)
CREATE TABLE IF NOT EXISTS login_audit (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NULL,
    username    VARCHAR(50)  NOT NULL,
    event       VARCHAR(30)  NOT NULL,   -- PASSWORD, TOTP, FACE, REGISTER, LOGOUT
    success     BOOLEAN      NOT NULL,
    detail      VARCHAR(255),
    ip_address  VARCHAR(64),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);
-- Foreign keys above also give us indexes on user_id (needed for fast lookups).
