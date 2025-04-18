package com.example.streaming.services;

import java.util.function.Function;
import org.springframework.security.core.userdetails.UserDetails;
import io.jsonwebtoken.Claims;

public interface JwtService {
    String extractUsername(String token);

    <T> T extractClaim(String token, Function<Claims, T> claimsResolver);

    Claims extractAllClaims(String token);

    String generateToken(UserDetails userDetails, Long userId);

    // String generateToken(Map<String, Object> claims, UserDetails userDetails);

    boolean isTokenValid(String token, UserDetails userDetails);
}
