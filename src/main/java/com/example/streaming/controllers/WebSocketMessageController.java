package com.example.streaming.controllers;

import java.io.IOException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Controller
public class WebSocketMessageController {

    @MessageMapping("/event/{eventId}")
    public void handleWebSocketMessage(
            @DestinationVariable String eventId,
            TextMessage message,
            WebSocketSession session) throws IOException {
    }
}
