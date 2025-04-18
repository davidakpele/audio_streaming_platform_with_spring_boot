package com.example.streaming.services;

import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import com.example.streaming.model.Users;

public interface UserService {

    Users getUserByUsername(String username);

    Users getUserById(Long userId);

    Optional<Users> findById(Long id);

    ResponseEntity<?> resetPassword(Long userId, String password, Authentication authentication);

    Object deactivateAccount(Long id, Authentication authentication);

    ResponseEntity<?> forgetPassword(String email);

    ResponseEntity<?> updateUserProfile(String username, String email, String gender, String profilePath);

}
