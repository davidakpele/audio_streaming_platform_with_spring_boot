package com.example.streaming.serviceImplementation;

import org.springframework.stereotype.Service;
import com.example.streaming.model.AuthorizeUserVerification;
import com.example.streaming.repository.AuthorizeUserVerificationRepository;
import com.example.streaming.services.AuthorizeUserVerificationService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthorizeUserVerificationServiceImplementation implements AuthorizeUserVerificationService {

    private final AuthorizeUserVerificationRepository authorizeUserVerificationRepository;

    @Override
    public void save(Long userId, Long id) {
        AuthorizeUserVerification auth = new AuthorizeUserVerification();
        auth.setId(id);
        auth.setUserId(userId);
        authorizeUserVerificationRepository.save(auth);
    }

}
