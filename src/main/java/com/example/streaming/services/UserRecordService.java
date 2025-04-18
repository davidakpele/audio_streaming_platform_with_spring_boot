package com.example.streaming.services;

import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import com.example.streaming.dtos.UserDTO;
import com.example.streaming.model.UserRecord;


public interface UserRecordService {

    UserDTO getUserDetailsById(Long id, Authentication authentication);

    UserDTO getUserByUsername(String username, Authentication authentication);

    Optional<UserRecord> getUserNames(Long userId);

    boolean isLockedAccount(Long userId);

    boolean isBlockedAccount(Long userId);

    ResponseEntity<?> lockUserAccount(Long userId);

    ResponseEntity<?> blockUserAccount(Long userId);
}
