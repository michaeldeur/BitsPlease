package edu.groupproject.faceauth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes and reads the login_audit table. */
@Repository
public class AuditRepository {

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(Long userId, String username, String event, boolean success, String detail, String ip) {
        jdbc.update("INSERT INTO login_audit (user_id, username, event, success, detail, ip_address) VALUES (?, ?, ?, ?, ?, ?)",
                userId, truncate(username, 50), event, success, truncate(detail, 255), ip);
    }

    public List<Map<String, Object>> recentForUser(long userId, int limit) {
        return jdbc.query(
                "SELECT event, success, detail, ip_address, created_at FROM login_audit "
                        + "WHERE user_id = ? ORDER BY id DESC LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("event", rs.getString("event"));
                    row.put("success", rs.getBoolean("success"));
                    row.put("detail", rs.getString("detail"));
                    row.put("ip", rs.getString("ip_address"));
                    row.put("time", rs.getTimestamp("created_at").toInstant().toString());
                    return row;
                }, userId, limit);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
