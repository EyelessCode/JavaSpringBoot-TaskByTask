-- ============================================================================
-- V1__init_schema.sql — Task by Task Database Baseline Schema
-- Target: PostgreSQL 15+ (Supabase "public" schema)
-- Constraints: Strict validation, RNF-20 extensibility, zero destructive migrations
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. USERS & CREDENTIALS
-- ----------------------------------------------------------------------------
CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    username VARCHAR(30) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    timezone VARCHAR(50) NOT NULL DEFAULT 'UTC',
    time_format VARCHAR(3) NOT NULL DEFAULT '24H',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_users_username_format CHECK (username ~ '^[A-Za-z0-9]{3,30}$'),
    CONSTRAINT chk_users_display_name_len CHECK (char_length(display_name) >= 2 AND char_length(display_name) <= 50),
    CONSTRAINT chk_users_time_format CHECK (time_format IN ('24H', '12H'))
);

-- Case-insensitive unique indexes for login identifiers
CREATE UNIQUE INDEX uq_users_email_lower ON users (LOWER(email));
CREATE UNIQUE INDEX uq_users_username_lower ON users (LOWER(username));

-- ----------------------------------------------------------------------------
-- 2. RECOVERY CODES (8 single-use codes per user, RF-01, RF-03)
-- ----------------------------------------------------------------------------
CREATE TABLE recovery_codes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(255) NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    used_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_recovery_codes_user_id ON recovery_codes (user_id);

-- ----------------------------------------------------------------------------
-- 3. GROUPS & COLLABORATIVE MEMBERSHIPS (RNF-20 Extensibility)
-- ----------------------------------------------------------------------------
CREATE TABLE groups (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    creator_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(30) NOT NULL,
    is_personal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_groups_name_len CHECK (char_length(name) >= 3 AND char_length(name) <= 30)
);

CREATE INDEX idx_groups_creator_id ON groups (creator_id);

-- Enforce exactly ONE "Personal" group per user (FND-REQ-04, RF-06)
CREATE UNIQUE INDEX uq_groups_creator_personal ON groups (creator_id) WHERE is_personal = TRUE;

-- Deterministic Keyset ordering index for groups (FND-REQ-25)
CREATE INDEX idx_groups_keyset_order ON groups (creator_id, is_personal DESC, created_at ASC, id ASC);

-- Group Memberships supporting future shared roles (CREATOR, MANAGER, VISITOR)
CREATE TABLE group_memberships (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_group_memberships_role CHECK (role IN ('CREATOR', 'MANAGER', 'VISITOR')),
    CONSTRAINT uq_group_memberships_group_user UNIQUE (group_id, user_id)
);

CREATE INDEX idx_group_memberships_user_id ON group_memberships (user_id);
CREATE INDEX idx_group_memberships_group_id ON group_memberships (group_id);

-- ----------------------------------------------------------------------------
-- 4. TAGS (Group-Scoped, Case/Whitespace Insensitive, RF-15, RF-22)
-- ----------------------------------------------------------------------------
CREATE TABLE tags (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    name VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_tags_name_len CHECK (char_length(TRIM(name)) >= 1 AND char_length(TRIM(name)) <= 30),
    CONSTRAINT chk_tags_no_delimiters CHECK (name !~ '[,;]')
);

CREATE INDEX idx_tags_group_id ON tags (group_id);

-- Unique tag name per group ignoring case and whitespace (EC-03, FND-REQ-03)
CREATE UNIQUE INDEX uq_tags_group_normalized_name ON tags (group_id, TRIM(LOWER(name)));

-- Deterministic Keyset ordering index for tags (FND-REQ-25)
CREATE INDEX idx_tags_keyset_order ON tags (group_id, name ASC, id ASC);

-- ----------------------------------------------------------------------------
-- 5. TASKS (Core Entity, RNF-20 nullable assignee, composite keyset index)
-- ----------------------------------------------------------------------------
CREATE TABLE tasks (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    assigned_user_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(750) NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMPTZ NULL,
    due_date DATE NULL,
    due_time TIME NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_tasks_title_len CHECK (char_length(title) >= 2 AND char_length(title) <= 100),
    CONSTRAINT chk_tasks_description_len CHECK (description IS NULL OR char_length(description) <= 750),
    CONSTRAINT chk_tasks_due_time_requires_date CHECK (due_time IS NULL OR due_date IS NOT NULL)
);

CREATE INDEX idx_tasks_group_id ON tasks (group_id);
CREATE INDEX idx_tasks_assigned_user_id ON tasks (assigned_user_id) WHERE assigned_user_id IS NOT NULL;

-- Keyset pagination covering index matching default composite order (FND-REQ-24, RF-15)
CREATE INDEX idx_tasks_keyset_order ON tasks (
    group_id,
    completed ASC,
    due_date ASC NULLS LAST,
    due_time ASC NULLS LAST,
    created_at ASC,
    id ASC
);

-- ----------------------------------------------------------------------------
-- 6. TASK_TAGS (Many-to-Many Association)
-- ----------------------------------------------------------------------------
CREATE TABLE task_tags (
    task_id BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, tag_id)
);

CREATE INDEX idx_task_tags_tag_id ON task_tags (tag_id);

-- ----------------------------------------------------------------------------
-- 7. WEB PUSH SUBSCRIPTIONS (Browser Notifications, RF-27)
-- ----------------------------------------------------------------------------
CREATE TABLE push_subscriptions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL UNIQUE,
    p256dh VARCHAR(255) NOT NULL,
    auth VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_push_subscriptions_user_id ON push_subscriptions (user_id);

-- ----------------------------------------------------------------------------
-- 8. NOTIFICATIONS (In-app Notification Center, RF-29, 30-day retention)
-- ----------------------------------------------------------------------------
CREATE TABLE notifications (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    content VARCHAR(500) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notifications_user_id_created ON notifications (user_id, created_at DESC, id DESC);

-- ----------------------------------------------------------------------------
-- 9. SPRING SESSION JDBC INFRASTRUCTURE TABLES (RF-02, RNF-09)
-- ----------------------------------------------------------------------------
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL PRIMARY KEY,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME)
);

-- ----------------------------------------------------------------------------
-- 10. DB-SCHEDULER INFRASTRUCTURE TABLE (Kagkarlsson, RF-13, RF-28)
-- ----------------------------------------------------------------------------
CREATE TABLE scheduled_tasks (
    task_name VARCHAR(100) NOT NULL,
    task_instance VARCHAR(100) NOT NULL,
    task_data BYTEA,
    execution_time TIMESTAMPTZ NOT NULL,
    picked BOOLEAN NOT NULL DEFAULT FALSE,
    picked_by VARCHAR(50),
    last_success TIMESTAMPTZ,
    last_failure TIMESTAMPTZ,
    consecutive_failures INT,
    last_heartbeat TIMESTAMPTZ,
    version BIGINT NOT NULL,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX idx_scheduled_tasks_execution ON scheduled_tasks (execution_time);
