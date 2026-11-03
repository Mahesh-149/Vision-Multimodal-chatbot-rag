package org.example.vision;

import jakarta.annotation.PostConstruct;
import org.example.vision.ChatModels.IndexedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
/**
 * Document chunking, embedding and retrieval backed by Pinecone.
 * Every chunk carries a {@code conversationId} metadata field and every search is
 * filtered on it, so one conversation never sees another conversation's documents.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int EMBED_BATCH_SIZE = 50;

    private final VectorStore vectorStore;
    private final DocumentRegistry registry;
    private final TokenTextSplitter splitter = new TokenTextSplitter();
    private final int topK;
    private final int maxChunks;

    public RagService(VectorStore vectorStore, DocumentRegistry registry,
                      @Value("${app.rag.top-k:5}") int topK,
                      @Value("${app.rag.max-chunks-per-document:500}") int maxChunks) {
        this.vectorStore = vectorStore;
        this.registry = registry;
        this.topK = topK;
        this.maxChunks = maxChunks;
    }

    public IndexedDocument ingest(String conversationId, String filename,
                                  String contentType, String text) {
        List<Document> chunks = splitter.apply(List.of(new Document(text)));
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("no text to index");
        }
        if (chunks.size() > maxChunks) {
            throw new IllegalArgumentException("too large to index (" + chunks.size()
                    + " chunks, limit " + maxChunks + ")");
        }

        String documentId = UUID.randomUUID().toString();
        List<Document> toStore = new ArrayList<>();
        List<String> vectorIds = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String vectorId = UUID.randomUUID().toString();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("conversationId", conversationId);
            metadata.put("documentId", documentId);
            metadata.put("filename", filename);
            metadata.put("chunkIndex", i);
            toStore.add(Document.builder()
                    .id(vectorId)
                    .text(chunks.get(i).getText())
                    .metadata(metadata)
                    .build());
            vectorIds.add(vectorId);
        }

        List<String> stored = new ArrayList<>();
        try {
            for (int from = 0; from < toStore.size(); from += EMBED_BATCH_SIZE) {
                int to = Math.min(from + EMBED_BATCH_SIZE, toStore.size());
                vectorStore.add(toStore.subList(from, to));
                stored.addAll(vectorIds.subList(from, to));
            }
            registry.register(documentId, conversationId, filename, contentType, vectorIds);
        } catch (RuntimeException ex) {
            // Don't leave orphaned vectors behind if a later batch or the DB insert fails.
            if (!stored.isEmpty()) {
                try {
                    vectorStore.delete(stored);
                } catch (RuntimeException cleanupFailure) {
                    log.warn("Could not clean up {} vectors for failed ingest of {}",
                            stored.size(), filename, cleanupFailure);
                }
            }
            throw ex;
        }
        return new IndexedDocument(documentId, filename, vectorIds.size());
    }

    /** Best effort: a Pinecone outage should degrade to "no excerpts", not break chat. */
    public List<Document> retrieve(String conversationId, String query) {
        if (query == null || query.isBlank() || !registry.hasDocuments(conversationId)) {
            return List.of();
        }
        try {
            Filter.Expression filter = new FilterExpressionBuilder()
                    .eq("conversationId", conversationId).build();
            return vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(filter)
                    .build());
        } catch (RuntimeException ex) {
            log.warn("Retrieval failed for conversation {}", conversationId, ex);
            return List.of();
        }
    }

    public List<IndexedDocument> list(String conversationId) {
        return registry.list(conversationId);
    }

    public void deleteDocument(String conversationId, String documentId) {
        List<String> ids = registry.vectorIds(conversationId, documentId);
        if (!ids.isEmpty()) {
            vectorStore.delete(ids);
        }
        registry.remove(conversationId, documentId);
    }

    public void deleteConversation(String conversationId) {
        List<String> ids = registry.vectorIds(conversationId);
        if (!ids.isEmpty()) {
            vectorStore.delete(ids);
        }
        registry.removeConversation(conversationId);
    }
}
