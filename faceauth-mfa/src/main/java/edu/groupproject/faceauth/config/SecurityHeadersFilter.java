package edu.groupproject.faceauth.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class SecurityHeadersFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse r = (HttpServletResponse) res;
        r.setHeader("X-Content-Type-Options", "nosniff");
        r.setHeader("Referrer-Policy", "no-referrer");
        r.setHeader("Permissions-Policy", "camera=(self), microphone=()");
        r.setHeader("X-Frame-Options", "SAMEORIGIN"); // SAMEORIGIN so the H2 console still works
        chain.doFilter(req, res);
    }
}
