package com.example.streaming.responses;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VerificationTokenResult {
    private boolean success;
    private Object data;
}
