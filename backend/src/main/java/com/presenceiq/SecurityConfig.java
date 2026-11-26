package com.presenceiq;

import java.util.List;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    UserDetailsService users(
            @Value("${app.security.admin-username}") String adminName,
            @Value("${app.security.admin-password}") String adminPass,
            @Value("${app.security.reviewer-username}") String reviewerName,
            @Value("${app.security.reviewer-password}") String reviewerPass,
            @Value("${app.security.ingest-username}") String ingestName,
            @Value("${app.security.ingest-password}") String ingestPass) {
        for (String secret : List.of(adminPass, reviewerPass, ingestPass)) {
            if (secret.length() < 20 || secret.startsWith("REPLACE_")) {
                throw new IllegalStateException("Set all PRESENCEIQ_*_PASSWORD values to distinct, strong secrets (20+ chars)");
            }
        }
        if (adminName.equals(reviewerName) || adminName.equals(ingestName) || reviewerName.equals(ingestName)) {
            throw new IllegalStateException("All service usernames must differ.");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(adminName).password("{noop}" + adminPass).roles("ADMIN", "REVIEWER").build(),
                User.withUsername(reviewerName).password("{noop}" + reviewerPass).roles("REVIEWER").build(),
                User.withUsername(ingestName).password("{noop}" + ingestPass).roles("INGEST").build()
        );
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // Local HTTP Basic API MVP; replace with OAuth2/CSRF for internet exposure.
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // Preserve controller error responses without exposing /error directly.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/health", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/internal/**").hasAnyRole("INGEST", "ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("ADMIN", "REVIEWER")
                        .anyRequest().denyAll()
                )
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.allowed-origin}") String allowedOrigin) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of(allowedOrigin));
        cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setExposedHeaders(List.of("Content-Disposition"));
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", cfg);
        return src;
    }
}
