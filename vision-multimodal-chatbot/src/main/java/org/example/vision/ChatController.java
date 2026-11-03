package org.example.vision;

import org.example.vision.ChatModels.ApiError;
import org.example.vision.ChatModels.ChatResponse;
import org.example.vision.ChatModels.HistoryMessage;
import org.example.vision.ChatModels.IndexedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    // The id becomes a database key and a Pinecone metadata filter value: keep it boring.
    private static final Pattern CONVERSATION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Multipart fields:
     * message   - user prompt
     * sessionId - conversation identifier (use a UUID per browser conversation)
     * files     - zero or more images, PDFs, audio or video files, and supported documents
     *
     * "image" is retained as a compatibility field for your original frontend.
     */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> chat(
            @RequestParam(defaultValue = "") String message,
            @RequestParam(defaultValue = "default") String sessionId,
            @RequestParam(name = "files", required = false) List<MultipartFile> files,
            @RequestParam(name = "image", required = false) MultipartFile legacyImage) {

        String conversationId = validId(sessionId);

        if ((message == null || message.isBlank())
                && (files == null || files.isEmpty())
                && (legacyImage == null || legacyImage.isEmpty())) {
            return ResponseEntity.badRequest()
                    .body(new ApiError("Enter a message or attach at least one file."));
        }

        try {
            ChatResponse response = chatService.chat(conversationId, message, files, legacyImage);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        } catch (Exception ex) {
            // Full exception stays in the server log; the client never sees keys or provider internals.
            log.error("Chat request failed for conversation {}", conversationId, ex);
            return ResponseEntity.internalServerError()
                    .body(new ApiError("The chatbot could not process this request. Check the server logs."));
        }
    }

    /** Persisted history, so a page reload can resume the conversation. */
    @GetMapping("/chat/history")
    public List<HistoryMessage> history(@RequestParam(defaultValue = "default") String sessionId) {
        return chatService.history(validId(sessionId));
    }

    /** Deletes the conversation's history and all of its indexed documents. */
    @DeleteMapping("/chat")
    public ResponseEntity<Void> clearChat(
            @RequestParam(defaultValue = "default") String sessionId) {
        chatService.clearConversation(validId(sessionId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/chat/documents")
    public List<IndexedDocument> documents(@RequestParam(defaultValue = "default") String sessionId) {
        return chatService.documents(validId(sessionId));
    }

    @DeleteMapping("/chat/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String documentId,
            @RequestParam(defaultValue = "default") String sessionId) {
        chatService.deleteDocument(validId(sessionId), documentId);
        return ResponseEntity.noContent().build();
    }

    private String validId(String sessionId) {
        String id = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId.trim();
        if (!CONVERSATION_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "sessionId must be 1-64 characters: letters, digits, '-' or '_'.");
        }
        return id;
    }
}
