CREATE TABLE IF NOT EXISTS document (
    id          VARCHAR(36) PRIMARY KEY,
    path        VARCHAR(500) NOT NULL UNIQUE,
    title       VARCHAR(200) NOT NULL,
    content     CLOB,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at  TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chunk (
    id            VARCHAR(36) PRIMARY KEY,
    document_id   VARCHAR(36) NOT NULL,
    content       CLOB,
    chunk_index   INT NOT NULL,
    vector        CLOB,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS conversation (
    id          VARCHAR(36) PRIMARY KEY,
    title       VARCHAR(200),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS message (
    id                VARCHAR(36) PRIMARY KEY,
    conversation_id   VARCHAR(36) NOT NULL,
    role              VARCHAR(20) NOT NULL,
    content           CLOB,
    tool_call_id      VARCHAR(100),
    tool_name         VARCHAR(100),
    tool_calls_json   CLOB,
    level             INT DEFAULT 0,
    archived          BOOLEAN DEFAULT FALSE,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_document_path ON document(path);
CREATE INDEX IF NOT EXISTS idx_chunk_document_id ON chunk(document_id);
CREATE INDEX IF NOT EXISTS idx_message_conversation_id ON message(conversation_id);
CREATE INDEX IF NOT EXISTS idx_message_level ON message(level);
