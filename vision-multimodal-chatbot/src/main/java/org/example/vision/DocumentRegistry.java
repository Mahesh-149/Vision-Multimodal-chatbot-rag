package org.example.vision;

import org.example.vision.ChatModels.IndexedDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Tracks which documents (and which Pinecone vector ids) belong to each conversation.
 * Needed because deleting by metadata filter is not available on Pinecone serverless.
 */
@Repository
public class DocumentRegistry {

    private final JdbcTemplate jdbc;

    public DocumentRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void register(String documentId, String conversationId, String filename,
                         String contentType, List<String> vectorIds) {
        jdbc.update("""
                INSERT INTO rag_document (document_id, conversation_id, filename, content_type, chunk_count)
                VALUES (?, ?, ?, ?, ?)
                """, documentId, conversationId, truncate(filename, 255),
                truncate(contentType, 255), vectorIds.size());

        jdbc.batchUpdate("INSERT INTO rag_chunk (document_id, vector_id) VALUES (?, ?)",
                vectorIds, 200, (ps, vectorId) -> {
                    ps.setString(1, documentId);
                    ps.setString(2, vectorId);
                });
    }

    public boolean hasDocuments(String conversationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_document WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count != null && count > 0;
    }

    public List<IndexedDocument> list(String conversationId) {
        return jdbc.query("""
                SELECT document_id, filename, chunk_count FROM rag_document
                WHERE conversation_id = ? ORDER BY created_at
                """,
                (rs, i) -> new IndexedDocument(rs.getString(1), rs.getString(2), rs.getInt(3)),
                conversationId);
    }

    /** Vector ids of one document, or empty if it does not belong to this conversation. */
    public List<String> vectorIds(String conversationId, String documentId) {
        return jdbc.queryForList("""
                SELECT c.vector_id FROM rag_chunk c
                JOIN rag_document d ON d.document_id = c.document_id
                WHERE d.conversation_id = ? AND d.document_id = ?
                """, String.class, conversationId, documentId);
    }

    public List<String> vectorIds(String conversationId) {
        return jdbc.queryForList("""
                SELECT c.vector_id FROM rag_chunk c
                JOIN rag_document d ON d.document_id = c.document_id
                WHERE d.conversation_id = ?
                """, String.class, conversationId);
    }

    /** rag_chunk rows are removed by ON DELETE CASCADE. */
    public void remove(String conversationId, String documentId) {
        jdbc.update("DELETE FROM rag_document WHERE conversation_id = ? AND document_id = ?",
                conversationId, documentId);
    }

    public void removeConversation(String conversationId) {
        jdbc.update("DELETE FROM rag_document WHERE conversation_id = ?", conversationId);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
