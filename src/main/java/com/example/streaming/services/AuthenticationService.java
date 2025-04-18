package com.example.streaming.services;

import java.util.Optional;
import org.springframework.http.ResponseEntity;
import com.example.streaming.model.Users;
import com.example.streaming.payloads.UserSignInRequest;
import com.example.streaming.payloads.UserSignUpRequest;
import com.example.streaming.responses.VerificationTokenResult;

import jakarta.servlet.http.HttpServletResponse;

public interface AuthenticationService {

    ResponseEntity<?> createAccount(UserSignUpRequest request);

    ResponseEntity<?> login(UserSignInRequest request, HttpServletResponse response);

    Optional<Users> findByEmail(String email);

    Optional<Users> findByUsername(String username);

    ResponseEntity<?> verifyUser(String token, Long id);

    VerificationTokenResult generateVerificationToken(String oldToken);
}
