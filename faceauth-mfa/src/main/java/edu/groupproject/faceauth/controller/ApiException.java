package edu.groupproject.faceauth.controller;

import org.springframework.http.HttpStatus;

/** Thrown by controllers; turned into a JSON error like {"error":"..."} by ApiErrorHandler. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
