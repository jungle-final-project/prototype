package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.agent.PcAgentDiagnosisWebSocketHandler;
import com.buildgraph.prototype.common.BuildGraphCorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class SupportChatWebSocketConfig implements WebSocketConfigurer {
    // PC Agent 진단 결과 프레임은 측정 근거를 담아 컨테이너 기본값(8KB)을 쉽게 넘긴다.
    // 한계를 넘으면 컨테이너가 세션을 끊어버려 Agent가 재접속 루프에 빠진다.
    private static final int MAX_TEXT_MESSAGE_BUFFER_BYTES = 512 * 1024;
    private final SupportChatWebSocketHandler supportChatWebSocketHandler;
    private final AdminSupportChatQueueWebSocketHandler adminSupportChatQueueWebSocketHandler;
    private final PcAgentDiagnosisWebSocketHandler pcAgentDiagnosisWebSocketHandler;
    private final String[] allowedOriginPatterns;

    public SupportChatWebSocketConfig(
            SupportChatWebSocketHandler supportChatWebSocketHandler,
            AdminSupportChatQueueWebSocketHandler adminSupportChatQueueWebSocketHandler,
            PcAgentDiagnosisWebSocketHandler pcAgentDiagnosisWebSocketHandler,
            BuildGraphCorsProperties corsProperties
    ) {
        this.supportChatWebSocketHandler = supportChatWebSocketHandler;
        this.adminSupportChatQueueWebSocketHandler = adminSupportChatQueueWebSocketHandler;
        this.pcAgentDiagnosisWebSocketHandler = pcAgentDiagnosisWebSocketHandler;
        this.allowedOriginPatterns = corsProperties.allowedOrigins();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(supportChatWebSocketHandler, "/ws/support-chat")
                .setAllowedOrigins(allowedOriginPatterns);
        registry.addHandler(adminSupportChatQueueWebSocketHandler, "/ws/admin/support-chat-queue")
                .setAllowedOrigins(allowedOriginPatterns);
        registry.addHandler(pcAgentDiagnosisWebSocketHandler, "/ws/pc-agent/diagnosis")
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BUFFER_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_TEXT_MESSAGE_BUFFER_BYTES);
        return container;
    }
}
