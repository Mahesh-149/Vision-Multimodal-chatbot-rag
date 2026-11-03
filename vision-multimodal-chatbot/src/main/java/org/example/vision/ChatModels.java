package org.example.vision;

import java.util.List;

/** Request/response shapes for the REST API. */
public final class ChatModels {

    private ChatModels() {
    }

    /** A document that was split, embedded and stored in Pinecone. */
    public record IndexedDocument(String documentId, String filename, int chunks) {
    }

    /** answer = model reply; indexed = documents added to RAG this turn; warnings = non-fatal problems. */
    public record ChatResponse(String answer, List<IndexedDocument> indexed, List<String> warnings) {
    }

    public record HistoryMessage(String role, String text) {
    }

    public record ApiError(String error) {
    }
}
