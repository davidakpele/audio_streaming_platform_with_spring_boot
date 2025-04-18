package com.example.streaming.responses;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StreamLinkMessage {
    private String type;
    private String event_id;
    private String join_url;
}