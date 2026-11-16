package edu.groupproject.faceauth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Run with:  mvn spring-boot:run   then open http://localhost:8080
 */
@SpringBootApplication
public class FaceAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(FaceAuthApplication.class, args);
    }
}
