package edu.groupproject.faceauth.config;

import edu.groupproject.faceauth.service.TotpService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AppConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public TotpService totpService() {
        return new TotpService();
    }

    /** Blocks dashboard.html / setup.html for sessions that haven't reached the right stage. */
    @Bean
    public FilterRegistrationBean<PageGuardFilter> pageGuardFilter() {
        FilterRegistrationBean<PageGuardFilter> reg = new FilterRegistrationBean<>(new PageGuardFilter());
        reg.addUrlPatterns("/dashboard.html", "/setup.html");
        return reg;
    }

    /** Basic security headers on every response. Camera is allowed only for our own pages. */
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> reg = new FilterRegistrationBean<>(new SecurityHeadersFilter());
        reg.addUrlPatterns("/*");
        return reg;
    }
}
