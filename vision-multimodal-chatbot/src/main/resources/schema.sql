-- Registry of documents indexed into Pinecone, so they can be listed and deleted.
-- (Pinecone serverless cannot delete by metadata filter, so every vector id is tracked.)
CREATE TABLE IF NOT EXISTS rag_document (
    document_id     VARCHAR(36)  PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    content_type    VARCHAR(255),
    chunk_count     INT          NOT NULL,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rag_document_conversation ON rag_document (conversation_id);

CREATE TABLE IF NOT EXISTS rag_chunk (
    document_id VARCHAR(36) NOT NULL REFERENCES rag_document (document_id) ON DELETE CASCADE,
    vector_id   VARCHAR(36) NOT NULL,
    PRIMARY KEY (document_id, vector_id)
);
