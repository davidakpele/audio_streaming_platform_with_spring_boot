package com.example.streaming.controllers;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class AudioStreamController {

    @MessageMapping("/stream-audio")
    @SendTo("/topic/audio") 
    public byte[] streamAudioChunk(byte[] audioChunk) {
        return audioChunk;
    }

}
