package edu.groupproject.faceauth.model;

import java.time.LocalDateTime;

/** One row of the {@code users} table. */
public record User(
        long id,
        String username,
        String email,
        String passwordHash,
        String totpSecret,
        boolean totpEnabled,
        long lastTotpStep,
        boolean faceEnrolled,
        int failedAttempts,
        LocalDateTime lockedUntil,
        LocalDateTime createdAt
) {
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    public boolean isSetupComplete() {
        return totpEnabled && faceEnrolled;
    }
}
