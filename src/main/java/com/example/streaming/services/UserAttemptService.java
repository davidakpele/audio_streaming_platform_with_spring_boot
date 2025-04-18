package com.example.streaming.services;

import org.springframework.http.ResponseEntity;
import com.example.streaming.enums.AttemptType;

public interface UserAttemptService {

    ResponseEntity<?> createFailAttempt(Long id, AttemptType login);

    ResponseEntity<?> UpdateUserAccount(Long id);

}
