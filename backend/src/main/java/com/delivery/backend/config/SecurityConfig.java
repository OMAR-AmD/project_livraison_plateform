package com.delivery.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;

    /**
     * Comma-separated browser origins. The frontend normally reaches the API
     * through its own same-origin proxy, so this list only matters for direct
     * API clients. It must never be "*" with credentials.
     */
    @Value("${application.security.cors.allowed-origins:http://localhost:3000,http://localhost:3001,https://swiftdeliver-frontend.onrender.com}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter, AuthenticationProvider authenticationProvider) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authenticationProvider = authenticationProvider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .headers(headers -> headers
                // The API answers JSON, never HTML: no source, frame, form or
                // base URL should be loadable from a response.
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer.policy(
                        ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                // Only emitted on HTTPS requests; Render terminates TLS and the
                // forwarded-headers strategy lets Spring see the original scheme.
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31_536_000)))
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable) // Disable CSRF as we are using stateless JWTs
            .authorizeHttpRequests(auth -> auth
                // The platform's health checker cannot present a JWT, so this one
                // path is public. Deliberately an exact path and not a wildcard:
                // it exposes liveness only, and widening it to "/api/v1/**" would
                // quietly unauthenticate the entire API.
                .requestMatchers("/api/v1/health").permitAll()
                // Signing out has to know who is signing out, so it is the one
                // /auth path that is not public. Listed before the wildcard below
                // because the first matching rule wins; the other auth endpoints
                // (login, register, verify) genuinely are open.
                .requestMatchers("/api/v1/auth/logout").authenticated()
                // Allow anyone to access the login and registration endpoints
                .requestMatchers("/api/v1/auth/**").permitAll() 
                // Persona-based routing for deliveries
                .requestMatchers("/api/v1/client/**").hasRole("CLIENT")
                .requestMatchers("/api/v1/courier/**").hasRole("LIVREUR")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // Any other request MUST be authenticated
                .anyRequest().authenticated() 
            )
            .sessionManagement(session -> session
                // Crucial for JWTs: Do not store session state on the server
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS) 
            )
            .authenticationProvider(authenticationProvider)
            // Inject our custom filter before the standard Spring password filter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept"));
        configuration.setExposedHeaders(Arrays.asList("Retry-After"));
        // Authentication is a bearer token in a header, not a cookie. Credentials
        // must stay false so a wildcard-style CORS mistake cannot become a
        // browser-authenticated cross-origin read.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}