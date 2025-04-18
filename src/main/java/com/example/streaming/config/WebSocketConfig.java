package com.example.streaming.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import com.example.streaming.domain.AudioStreamHandlerConsumer;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AudioStreamHandlerConsumer audioStreamHandler;

    public WebSocketConfig(AudioStreamHandlerConsumer audioStreamHandler) {
        this.audioStreamHandler = audioStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(audioStreamHandler, "/ws/stream/**")
                .setAllowedOrigins("*");
    }
}
