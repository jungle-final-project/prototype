package com.buildgraph.prototype.ticket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class SupportChatWebSocketConfig implements WebSocketConfigurer {
    private final SupportChatWebSocketHandler supportChatWebSocketHandler;
    private final String[] allowedOriginPatterns;

    public SupportChatWebSocketConfig(
            SupportChatWebSocketHandler supportChatWebSocketHandler,
            @Value("${support-chat.ws.allowed-origins:http://localhost:5173,http://localhost:5174}") String allowedOrigins
    ) {
        this.supportChatWebSocketHandler = supportChatWebSocketHandler;
        this.allowedOriginPatterns = parseOrigins(allowedOrigins);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(supportChatWebSocketHandler, "/ws/support-chat")
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }

    private static String[] parseOrigins(String allowedOrigins) {
        return java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
    }
}
