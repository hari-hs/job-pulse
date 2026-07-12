package com.jobpulse.auth.dto;

public record AuthResponse(String token, String tokenType, String email, String fullName) {

    public AuthResponse(String token, String email, String fullName) {
        this(token, "Bearer", email, fullName);
    }
}
