package edu.groupproject.faceauth.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * JSON request bodies. Spring (Jackson) turns incoming JSON into these records automatically,
 * e.g. {"username":"alice","password":"..."} becomes a LoginRequest.
 */
public final class Dtos {
    private Dtos() {}

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 50)
            @Pattern(regexp = "^[A-Za-z0-9_.-]+$", message = "may only contain letters, numbers, _ . -")
            String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be 8-72 characters") String password
    ) {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record CodeRequest(@NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String code) {}

    /** One face sample = 128 numbers from the face-recognition model. */
    public record FaceVerifyRequest(@NotNull List<Double> descriptor) {}

    /** Enrollment sends several samples (different angles / expressions). */
    public record FaceEnrollRequest(@NotEmpty @Size(max = 10) List<List<Double>> descriptors) {}
}
