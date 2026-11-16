package edu.groupproject.faceauth.controller;

import edu.groupproject.faceauth.config.SessionKeys;
import edu.groupproject.faceauth.model.AuthStage;
import edu.groupproject.faceauth.model.Dtos.CodeRequest;
import edu.groupproject.faceauth.model.Dtos.FaceEnrollRequest;
import edu.groupproject.faceauth.model.User;
import edu.groupproject.faceauth.repository.AuditRepository;
import edu.groupproject.faceauth.repository.FaceRepository;
import edu.groupproject.faceauth.repository.UserRepository;
import edu.groupproject.faceauth.service.FaceMatchService;
import edu.groupproject.faceauth.service.QrCodeService;
import edu.groupproject.faceauth.service.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * MFA enrollment: link an authenticator app, then register your face.
 * Available right after registering (stage SETUP) or later from the dashboard (AUTHENTICATED).
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private static final int MIN_FACE_SAMPLES = 3;
    /** Enrollment samples must all look like the same person. */
    private static final double SAME_PERSON_LIMIT = 0.6;

    private final UserRepository users;
    private final FaceRepository faces;
    private final AuditRepository audit;
    private final TotpService totp;
    private final QrCodeService qr;
    private final String issuer;

    public SetupController(UserRepository users, FaceRepository faces, AuditRepository audit,
                           TotpService totp, QrCodeService qr, @Value("${app.totp.issuer}") String issuer) {
        this.users = users;
        this.faces = faces;
        this.audit = audit;
        this.totp = totp;
        this.qr = qr;
        this.issuer = issuer;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        long userId = Sessions.requireStage(request, AuthStage.SETUP, AuthStage.AUTHENTICATED);
        User u = users.findById(userId).orElseThrow();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("username", u.username());
        res.put("totpEnabled", u.totpEnabled());
        res.put("faceEnrolled", u.faceEnrolled());
        res.put("stage", Sessions.stage(request.getSession(false)).name());
        return res;
    }

    // ------------------------------------------------------------------ authenticator app

    @PostMapping("/totp/start")
    public Map<String, Object> startTotp(HttpServletRequest request) {
        long userId = Sessions.requireStage(request, AuthStage.SETUP, AuthStage.AUTHENTICATED);
        User u = users.findById(userId).orElseThrow();
        // During first-time setup you can't replace an app that is already linked:
        // that would let someone who only knows the password take over the account.
        if (u.totpEnabled() && Sessions.stage(request.getSession(false)) == AuthStage.SETUP) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Authenticator app is already linked");
        }
        String secret = totp.generateSecret();
        request.getSession(false).setAttribute(SessionKeys.PENDING_TOTP_SECRET, secret);
        String uri = totp.buildOtpAuthUri(issuer, u.username(), secret);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("secret", secret);
        res.put("otpauthUri", uri);
        res.put("qrDataUrl", qr.toPngDataUrl(uri, 220));
        return res;
    }

    @PostMapping("/totp/confirm")
    public Map<String, Object> confirmTotp(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        long userId = Sessions.requireStage(request, AuthStage.SETUP, AuthStage.AUTHENTICATED);
        HttpSession session = request.getSession(false);
        String secret = (String) session.getAttribute(SessionKeys.PENDING_TOTP_SECRET);
        if (secret == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Start authenticator setup first");
        }
        OptionalLong step = totp.verify(secret, body.code(), 0);
        if (step.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Code didn't match. Make sure you scanned the newest QR code.");
        }
        users.enableTotp(userId, secret);
        users.updateLastTotpStep(userId, step.getAsLong());
        session.removeAttribute(SessionKeys.PENDING_TOTP_SECRET);
        User u = users.findById(userId).orElseThrow();
        audit.log(userId, u.username(), "TOTP_SETUP", true, null, request.getRemoteAddr());
        return finishIfComplete(request, u);
    }

    // ------------------------------------------------------------------ face

    @PostMapping("/face/enroll")
    public Map<String, Object> enrollFace(@Valid @RequestBody FaceEnrollRequest body, HttpServletRequest request) {
        long userId = Sessions.requireStage(request, AuthStage.SETUP, AuthStage.AUTHENTICATED);
        User u = users.findById(userId).orElseThrow();
        if (!u.totpEnabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Link your authenticator app first");
        }
        if (u.faceEnrolled() && Sessions.stage(request.getSession(false)) == AuthStage.SETUP) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Face already enrolled. Log in fully to re-enroll.");
        }
        if (body.descriptors().size() < MIN_FACE_SAMPLES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please capture at least " + MIN_FACE_SAMPLES + " samples");
        }

        List<double[]> samples = new ArrayList<>();
        for (List<Double> d : body.descriptors()) {
            samples.add(FaceMatchService.validate(d));
        }
        for (int i = 0; i < samples.size(); i++) {
            for (int j = i + 1; j < samples.size(); j++) {
                if (FaceMatchService.euclideanDistance(samples.get(i), samples.get(j)) > SAME_PERSON_LIMIT) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "Samples don't look like the same person. Make sure only you are in frame and try again.");
                }
            }
        }

        faces.replaceDescriptors(userId, samples);
        users.setFaceEnrolled(userId, true);
        audit.log(userId, u.username(), "FACE_SETUP", true, samples.size() + " samples", request.getRemoteAddr());
        return finishIfComplete(request, users.findById(userId).orElseThrow());
    }

    /** Once both factors are set up during first-time setup, log out and make them sign in properly. */
    private Map<String, Object> finishIfComplete(HttpServletRequest request, User u) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("totpEnabled", u.totpEnabled());
        res.put("faceEnrolled", u.faceEnrolled());
        boolean firstTimeSetup = Sessions.stage(request.getSession(false)) == AuthStage.SETUP;
        if (u.isSetupComplete() && firstTimeSetup) {
            Sessions.reset(request);
            res.put("next", "/login.html?setup=done");
        } else if (!firstTimeSetup) {
            res.put("next", "/dashboard.html");
        }
        return res;
    }
}
