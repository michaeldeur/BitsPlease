package edu.groupproject.faceauth.controller;

import edu.groupproject.faceauth.model.AuthStage;
import edu.groupproject.faceauth.model.User;
import edu.groupproject.faceauth.repository.AuditRepository;
import edu.groupproject.faceauth.repository.FaceRepository;
import edu.groupproject.faceauth.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Data for the dashboard. Only available after all three factors pass. */
@RestController
@RequestMapping("/api/me")
public class AccountController {

    private final UserRepository users;
    private final FaceRepository faces;
    private final AuditRepository audit;

    public AccountController(UserRepository users, FaceRepository faces, AuditRepository audit) {
        this.users = users;
        this.faces = faces;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> me(HttpServletRequest request) {
        long userId = Sessions.requireStage(request, AuthStage.AUTHENTICATED);
        User u = users.findById(userId).orElseThrow();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("username", u.username());
        res.put("email", u.email());
        res.put("memberSince", u.createdAt() == null ? null : u.createdAt().toString());
        res.put("totpEnabled", u.totpEnabled());
        res.put("faceEnrolled", u.faceEnrolled());
        res.put("faceSamples", faces.findByUserId(userId).size());
        return res;
    }

    @GetMapping("/logins")
    public List<Map<String, Object>> logins(HttpServletRequest request) {
        long userId = Sessions.requireStage(request, AuthStage.AUTHENTICATED);
        return audit.recentForUser(userId, 25);
    }
}
