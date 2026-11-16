package edu.groupproject.faceauth.config;

import edu.groupproject.faceauth.model.AuthStage;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/** Redirects to the login page if someone opens a protected page without the right session stage. */
public class PageGuardFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        HttpSession session = request.getSession(false);
        Object stage = session == null ? null : session.getAttribute(SessionKeys.STAGE);
        String path = request.getRequestURI();

        boolean allowed = path.endsWith("/dashboard.html")
                ? stage == AuthStage.AUTHENTICATED
                : stage == AuthStage.SETUP || stage == AuthStage.AUTHENTICATED; // setup.html

        if (allowed) {
            chain.doFilter(req, res);
        } else {
            response.sendRedirect("/login.html");
        }
    }
}
