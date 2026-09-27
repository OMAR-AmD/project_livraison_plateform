package com.delivery.backend.config;

import com.delivery.backend.modules.auth.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        
        String authHeader = request.getHeader("Authorization");
        String tokenParam = request.getParameter("token");
        final String jwt;
        final String userEmail;

        // 1. Check if the token is in the header or in the query parameter (for SSE)
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7);
        } else if (tokenParam != null && !tokenParam.isEmpty()) {
            jwt = tokenParam;
        } else {
            filterChain.doFilter(request, response); // Pass it down the chain
            return;
        }

        try {
            userEmail = jwtService.extractUsername(jwt);

            // 3. If we have an email and the user is not yet authenticated in this session
            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);

                // 4. Validate token cryptographically
                if (jwtService.isTokenValid(jwt, userDetails)) {
                    
                    // 5. Create an authentication token and attach it to the Security Context
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception ex) {
            // Log the security event instead of silently swallowing it.
            // The request continues unauthenticated — Spring Security will reject it
            // if the endpoint requires authentication.
            log.warn("JWT authentication failed for request {} {}: {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
        }

        // 6. Continue to the next filter
        filterChain.doFilter(request, response);
    }
}