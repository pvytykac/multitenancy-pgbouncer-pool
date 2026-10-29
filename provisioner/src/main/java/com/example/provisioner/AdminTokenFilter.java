package com.example.provisioner;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AdminTokenFilter extends OncePerRequestFilter {

    @Value("${admin.token:}")
    private String adminToken;

    @PostConstruct
    public void requireToken() {
        if (adminToken == null || adminToken.isBlank()) {
            throw new IllegalStateException("admin.token must be set (ADMIN_TOKEN env var)");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (path.startsWith("/api/")) {
            if (adminToken != null && !adminToken.isBlank()) {
                String provided = request.getHeader("X-Admin-Token");
                if (!adminToken.equals(provided)) {
                    response.setStatus(401);
                    response.setContentType("text/plain");
                    response.getWriter().write("unauthorized");
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
