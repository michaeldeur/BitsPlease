package edu.groupproject.faceauth.controller;

import edu.groupproject.faceauth.config.SessionKeys;
import edu.groupproject.faceauth.model.AuthStage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;

import java.util.Arrays;

/** Small helpers for reading / changing the login stage stored in the session. */
final class Sessions {
    private Sessions() {}

    static AuthStage stage(HttpSession session) {
        return session == null ? null : (AuthStage) session.getAttribute(SessionKeys.STAGE);
    }

    /** Returns the user id if the session is in one of the allowed stages, otherwise 401. */
    static long requireStage(HttpServletRequest request, AuthStage... allowed) {
        HttpSession session = request.getSession(false);
        AuthStage current = stage(session);
        if (current == null || Arrays.stream(allowed).noneMatch(a -> a == current)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Please start from the login page");
        }
        return (Long) session.getAttribute(SessionKeys.USER_ID);
    }

    /** Moves to a new stage and issues a fresh session id (prevents session-fixation attacks). */
    static void advance(HttpServletRequest request, long userId, AuthStage next) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(SessionKeys.USER_ID, userId);
        session.setAttribute(SessionKeys.STAGE, next);
        session.removeAttribute(SessionKeys.STEP_FAILURES);
    }

    /** Fresh session for a brand-new login attempt. */
    static void reset(HttpServletRequest request) {
        HttpSession old = request.getSession(false);
        if (old != null) old.invalidate();
    }

    /** Counts a failed TOTP/face attempt. After 5, the whole login is cancelled. */
    static boolean tooManyStepFailures(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Integer n = (Integer) session.getAttribute(SessionKeys.STEP_FAILURES);
        n = (n == null ? 0 : n) + 1;
        session.setAttribute(SessionKeys.STEP_FAILURES, n);
        if (n >= 5) {
            session.invalidate();
            return true;
        }
        return false;
    }
}
