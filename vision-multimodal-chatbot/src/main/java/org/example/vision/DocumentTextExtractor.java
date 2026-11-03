package org.example.vision;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Extracts plain text from PDF and DOCX bytes using Apache Tika. */
@Component
public class DocumentTextExtractor {

    public String extract(byte[] bytes) {
        List<Document> documents = new TikaDocumentReader(new ByteArrayResource(bytes)).get();
        return documents.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"))
                .trim();
    }
}
