package org.example.vision;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryConfig {

    /**
     * Sliding-window memory on top of the JDBC repository auto-configured by
     * spring-ai-starter-model-chat-memory-repository-jdbc. Everything is persisted in
     * PostgreSQL; only the most recent N messages are sent to the model.
     */
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository,
                          @Value("${app.chat.max-history-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(maxMessages)
                .build();
    }
}
