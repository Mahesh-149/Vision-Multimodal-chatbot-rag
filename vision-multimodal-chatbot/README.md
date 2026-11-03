# Vision — Spring Boot 4 + Spring AI 2.0.1 + Gemini 3.8 Flash

Multimodal chatbot with **persistent conversation history** (PostgreSQL) and **RAG over uploaded documents** (Pinecone).

## Features

- Text, image, PDF, DOCX, TXT/MD/CSV, audio and video input
- Persistent chat memory: Spring AI `ChatMemory` on a JDBC repository (PostgreSQL), sliding window of the last 20 messages sent to the model
- RAG: PDF / DOCX / TXT / MD / CSV uploads are chunked, embedded with Gemini and stored in Pinecone; later questions retrieve the relevant passages
- Documents and history are isolated per `sessionId` (conversation)
- Page reload resumes the conversation; indexed documents are listed and removable in the UI
- `POST /api/chat` keeps its original multipart contract (`message`, `sessionId`, `files`, legacy `image`)

## Requirements

- JDK 21, Maven 3.9+
- Docker (for local PostgreSQL) or any PostgreSQL 14+ database
- Google AI Studio Gemini API key
- Pinecone account (free serverless plan is enough)

## 1. Create the Pinecone index

In the Pinecone console create a **serverless index**:

| Setting | Value |
|---|---|
| Name | `vision-chat` (or set `PINECONE_INDEX`) |
| Dimensions | `768` — must equal `spring.ai.google.genai.embedding.text.dimensions` |
| Metric | `cosine` |

Spring AI does not create the index for you. If you change the embedding model or dimension, create a new index.

## 2. Start PostgreSQL

```bash
docker compose up -d
```

This matches the defaults in `application.properties` (db `vision`, user `vision`, password `vision`). To use another database set `DB_URL`, `DB_USER`, `DB_PASSWORD`. On first start Spring AI creates `SPRING_AI_CHAT_MEMORY` and `schema.sql` creates `rag_document` / `rag_chunk`.

## 3. Run

Windows CMD:
```cmd
set GEMINI_API_KEY=YOUR_GEMINI_KEY
set PINECONE_API_KEY=YOUR_PINECONE_KEY
mvn clean spring-boot:run
```

PowerShell:
```powershell
$env:GEMINI_API_KEY="YOUR_GEMINI_KEY"
$env:PINECONE_API_KEY="YOUR_PINECONE_KEY"
mvn clean spring-boot:run
```

Open `http://localhost:8080`. Do not commit keys to Git.

## 4. How it works

```
upload ─┬─ image / audio / video / PDF ──► sent to Gemini natively (this turn only)
        ├─ TXT / MD / CSV / DOCX ────────► text inlined into this turn's prompt (first 60k chars)
        └─ PDF / DOCX / TXT / MD / CSV ──► Tika text ► TokenTextSplitter ► Gemini embeddings ► Pinecone
                                           (after the answer succeeds; vector ids tracked in rag_chunk)

question ─► similarity search in Pinecone (filter: conversationId) ─► excerpts added to the system prompt
         ─► last 20 messages from PostgreSQL + the new message ─► Gemini
         ─► typed text + attachment names + answer saved to SPRING_AI_CHAT_MEMORY
```

Design choices worth knowing:

- **History stores a compact user message** (typed text plus attachment names), not the inlined document text or media. Media cannot be stored in the chat-memory table, and document text already lives in the vector store. Consequence: an image/audio/video is only "seen" on the turn it is uploaded; later turns rely on the model's earlier answer about it. PDFs and documents stay answerable through RAG.
- **Pinecone serverless cannot delete by metadata filter**, so `rag_chunk` records every vector id; deleting a document or conversation deletes those ids.
- **Retrieval is best effort.** If Pinecone is unreachable the chat still answers, just without excerpts (logged as a warning). Ingestion failures are returned as `warnings`, not errors.
- **Scanned PDFs** are read natively by Gemini on the upload turn but have no extractable text, so they are not searchable later (a warning says so).
- Pinecone is eventually consistent: a document uploaded a moment ago may take a few seconds to be retrievable. The upload turn itself is unaffected because the content is sent directly.

## 5. API

### Chat — `POST /api/chat` (multipart/form-data)

| Field | Required | Description |
|---|---:|---|
| `message` | No* | Prompt/question |
| `sessionId` | No | Conversation key, 1–64 chars of `A-Za-z0-9_-` (default `default`) |
| `files` | No | Repeat for each attachment |
| `image` | No | Legacy single-image field |

*Provide a message or at least one file.

Response (**changed from plain text to JSON**):
```json
{
  "answer": "…",
  "indexed": [{ "documentId": "…", "filename": "report.pdf", "chunks": 14 }],
  "warnings": []
}
```
Errors: `{ "error": "…" }` with status 400, 413 or 500.

```bash
curl -X POST http://localhost:8080/api/chat \
  -F "sessionId=demo-123" \
  -F "message=Summarize this and list the key points" \
  -F "files=@./sample.pdf"
```

### Other endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/chat/history?sessionId=` | Persisted messages `[{role, text}]` |
| GET | `/api/chat/documents?sessionId=` | Indexed documents |
| DELETE | `/api/chat/documents/{documentId}?sessionId=` | Remove one document from RAG |
| DELETE | `/api/chat?sessionId=` | Delete history **and** all indexed documents |

## 6. Configuration

| Property | Default | Meaning |
|---|---|---|
| `app.chat.max-history-messages` | 20 | Messages of history sent per request |
| `app.rag.top-k` | 5 | Passages retrieved per question |
| `app.rag.max-chunks-per-document` | 500 | Upload is not indexed above this (cost guard) |
| `spring.ai.google.genai.embedding.text.model` | `gemini-embedding-001` | Embedding model |
| `spring.ai.google.genai.embedding.text.dimensions` | 768 | Must match the Pinecone index |

## 7. Still to do for production

- Authentication (Spring Security + JWT): `sessionId` is currently a client-chosen string, so anyone who knows it can read that conversation. Derive the conversation owner from the authenticated user.
- Streaming responses (SSE), Gemini Files API for large media, file scanning, quotas and rate limits, retention rules.
- Flyway/Liquibase instead of `initialize-schema=always` / `schema.sql`.
