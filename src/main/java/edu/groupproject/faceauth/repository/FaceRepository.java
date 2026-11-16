package edu.groupproject.faceauth.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Stores face descriptors as JSON arrays in the face_descriptors table. */
@Repository
public class FaceRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public FaceRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Replaces any previous enrollment for this user. */
    @Transactional
    public void replaceDescriptors(long userId, List<double[]> descriptors) {
        jdbc.update("DELETE FROM face_descriptors WHERE user_id = ?", userId);
        for (double[] d : descriptors) {
            jdbc.update("INSERT INTO face_descriptors (user_id, descriptor_json) VALUES (?, ?)",
                    userId, toJson(d));
        }
    }

    public List<double[]> findByUserId(long userId) {
        return jdbc.query("SELECT descriptor_json FROM face_descriptors WHERE user_id = ?",
                (rs, i) -> fromJson(rs.getString(1)), userId);
    }

    private String toJson(double[] d) {
        try {
            return json.writeValueAsString(d);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private double[] fromJson(String s) {
        try {
            return json.readValue(s, double[].class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt descriptor in database", e);
        }
    }
}
