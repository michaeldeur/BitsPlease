package edu.groupproject.faceauth.repository;

import edu.groupproject.faceauth.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * All SQL for the users table. Every query uses "?" placeholders (prepared statements),
 * which prevents SQL injection.
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<User> MAPPER = (rs, i) -> {
        Timestamp locked = rs.getTimestamp("locked_until");
        Timestamp created = rs.getTimestamp("created_at");
        return new User(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("totp_secret"),
                rs.getBoolean("totp_enabled"),
                rs.getLong("last_totp_step"),
                rs.getBoolean("face_enrolled"),
                rs.getInt("failed_attempts"),
                locked == null ? null : locked.toLocalDateTime(),
                created == null ? null : created.toLocalDateTime());
    };

    public Optional<User> findById(long id) {
        return jdbc.query("SELECT * FROM users WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        return jdbc.query("SELECT * FROM users WHERE LOWER(username) = LOWER(?)", MAPPER, username)
                .stream().findFirst();
    }

    public boolean existsByUsernameOrEmail(String username, String email) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?)",
                Integer.class, username, email);
        return n != null && n > 0;
    }

    public long create(String username, String email, String passwordHash) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            // Ask only for the "id" column back. With RETURN_GENERATED_KEYS, H2 also
            // returns created_at (it has a default value), and getKey() then fails.
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)",
                    new String[] {"id"});
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, passwordHash);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void enableTotp(long userId, String secret) {
        jdbc.update("UPDATE users SET totp_secret = ?, totp_enabled = TRUE, last_totp_step = 0 WHERE id = ?",
                secret, userId);
    }

    public void updateLastTotpStep(long userId, long step) {
        jdbc.update("UPDATE users SET last_totp_step = ? WHERE id = ?", step, userId);
    }

    public void setFaceEnrolled(long userId, boolean enrolled) {
        jdbc.update("UPDATE users SET face_enrolled = ? WHERE id = ?", enrolled, userId);
    }

    public void recordFailedLogin(long userId, int maxAttempts, int lockoutMinutes) {
        jdbc.update("UPDATE users SET failed_attempts = failed_attempts + 1 WHERE id = ?", userId);
        jdbc.update("UPDATE users SET locked_until = ?, failed_attempts = 0 WHERE id = ? AND failed_attempts >= ?",
                Timestamp.valueOf(LocalDateTime.now().plusMinutes(lockoutMinutes)), userId, maxAttempts);
    }

    public void resetFailedLogins(long userId) {
        jdbc.update("UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = ?", userId);
    }
}
