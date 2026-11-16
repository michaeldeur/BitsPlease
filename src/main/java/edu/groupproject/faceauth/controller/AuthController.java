package edu.groupproject.faceauth.controller;

import edu.groupproject.faceauth.model.AuthStage;
import edu.groupproject.faceauth.model.Dtos.CodeRequest;
import edu.groupproject.faceauth.model.Dtos.FaceVerifyRequest;
import edu.groupproject.faceauth.model.Dtos.LoginRequest;
import edu.groupproject.faceauth.model.Dtos.RegisterRequest;
import edu.groupproject.faceauth.model.User;
import edu.groupproject.faceauth.repository.AuditRepository;
import edu.groupproject.faceauth.repository.FaceRepository;
import edu.groupproject.faceauth.repository.UserRepository;
import edu.groupproject.faceauth.service.FaceMatchService;
import edu.groupproject.faceauth.service.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Registration and the 3-factor login:
 *   1. POST /api/auth/login        – password            (something you know)
 *   2. POST /api/auth/verify-totp  – 6-digit app code    (something you have)
 *   3. POST /api/auth/verify-face  – webcam face match   (something you are)
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Used so "unknown user" takes as long as "wrong password" (stops username guessing by timing). */
    private final String dummyHash;

    private final UserRepository users;
    private final FaceRepository faces;
    private final AuditRepository audit;
    private final PasswordEncoder passwords;
    private final TotpService totp;
    private final double faceThreshold;
    private final int maxFailedAttempts;
    private final int lockoutMinutes;

    public AuthController(UserRepository users, FaceRepository faces, AuditRepository audit,
                          PasswordEncoder passwords, TotpService totp,
                          @Value("${app.face.match-threshold}") double faceThreshold,
                          @Value("${app.login.max-failed-attempts}") int maxFailedAttempts,
                          @Value("${app.login.lockout-minutes}") int lockoutMinutes) {
        this.users = users;
        this.faces = faces;
        this.audit = audit;
        this.passwords = passwords;
        this.totp = totp;
        this.faceThreshold = faceThreshold;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutMinutes = lockoutMinutes;
        this.dummyHash = passwords.encode("timing-equaliser-not-a-real-password");
    }

    // ------------------------------------------------------------------ register

    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        if (users.existsByUsernameOrEmail(body.username(), body.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "That username or email is already registered");
        }
        long id = users.create(body.username().trim(), body.email().trim().toLowerCase(),
                passwords.encode(body.password()));
        audit.log(id, body.username(), "REGISTER", true, null, request.getRemoteAddr());

        Sessions.reset(request);
        Sessions.advance(request, id, AuthStage.SETUP);
        return Map.of("ok", true, "next", "/setup.html");
    }

    // ------------------------------------------------------------------ factor 1: password

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        Sessions.reset(request);
        String ip = request.getRemoteAddr();
        User user = users.findByUsername(body.username()).orElse(null);

        if (user == null) {
            passwords.matches(body.password(), dummyHash); // equalise timing
            audit.log(null, body.username(), "PASSWORD", false, "unknown user", ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        if (user.isLocked()) {
            audit.log(user.id(), user.username(), "PASSWORD", false, "account locked", ip);
            throw new ApiException(HttpStatus.LOCKED,
                    "Too many failed attempts. Account locked for " + lockoutMinutes + " minutes.");
        }
        if (!passwords.matches(body.password(), user.passwordHash())) {
            users.recordFailedLogin(user.id(), maxFailedAttempts, lockoutMinutes);
            audit.log(user.id(), user.username(), "PASSWORD", false, "wrong password", ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        audit.log(user.id(), user.username(), "PASSWORD", true, null, ip);

        if (!user.totpEnabled()) {
            // Account never finished setup – let them finish it now.
            Sessions.advance(request, user.id(), AuthStage.SETUP);
            return Map.of("ok", true, "next", "/setup.html");
        }
        Sessions.advance(request, user.id(), AuthStage.PASSWORD_OK);
        return Map.of("ok", true, "next", "totp");
    }

    // ------------------------------------------------------------------ factor 2: authenticator code

    @PostMapping("/verify-totp")
    public Map<String, Object> verifyTotp(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        long userId = Sessions.requireStage(request, AuthStage.PASSWORD_OK);
        User user = users.findById(userId).orElseThrow();

        OptionalLong step = totp.verify(user.totpSecret(), body.code(), user.lastTotpStep());
        if (step.isEmpty()) {
            audit.log(user.id(), user.username(), "TOTP", false, "wrong code", request.getRemoteAddr());
            failStep(request);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "That code is not valid. Check your authenticator app.");
        }
        users.updateLastTotpStep(user.id(), step.getAsLong());
        audit.log(user.id(), user.username(), "TOTP", true, null, request.getRemoteAddr());

        if (!user.faceEnrolled()) {
            Sessions.advance(request, user.id(), AuthStage.SETUP);
            return Map.of("ok", true, "next", "/setup.html");
        }
        Sessions.advance(request, user.id(), AuthStage.TOTP_OK);
        return Map.of("ok", true, "next", "face");
    }

    // ------------------------------------------------------------------ factor 3: face

    @PostMapping("/verify-face")
    public Map<String, Object> verifyFace(@Valid @RequestBody FaceVerifyRequest body, HttpServletRequest request) {
        long userId = Sessions.requireStage(request, AuthStage.TOTP_OK);
        User user = users.findById(userId).orElseThrow();

        double[] live = FaceMatchService.validate(body.descriptor());
        List<double[]> enrolled = faces.findByUserId(userId);
        if (enrolled.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "No face enrolled for this account");
        }
        double distance = FaceMatchService.bestDistance(live, enrolled);
        String detail = String.format("distance=%.3f threshold=%.2f", distance, faceThreshold);

        if (distance > faceThreshold) {
            audit.log(user.id(), user.username(), "FACE", false, detail, request.getRemoteAddr());
            failStep(request);
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    String.format("Face did not match (distance %.2f). Try better lighting and look at the camera.", distance));
        }
        users.resetFailedLogins(user.id());
        audit.log(user.id(), user.username(), "FACE", true, detail, request.getRemoteAddr());
        Sessions.advance(request, user.id(), AuthStage.AUTHENTICATED);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("next", "/dashboard.html");
        res.put("distance", Math.round(distance * 1000) / 1000.0);
        return res;
    }

    // ------------------------------------------------------------------ misc

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        AuthStage stage = Sessions.stage(request.getSession(false));
        return Map.of("stage", stage == null ? "NONE" : stage.name());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute("userId") instanceof Long id) {
            users.findById(id).ifPresent(u ->
                    audit.log(u.id(), u.username(), "LOGOUT", true, null, request.getRemoteAddr()));
        }
        Sessions.reset(request);
        return Map.of("ok", true);
    }

    private void failStep(HttpServletRequest request) {
        if (Sessions.tooManyStepFailures(request)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Too many failed attempts. Please log in again.");
        }
    }
}
