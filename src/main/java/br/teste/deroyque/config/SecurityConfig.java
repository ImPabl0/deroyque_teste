package br.teste.deroyque.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Resource Server OAuth 2.0: valida JWT (assinatura via JWKS, emissor, audiência e expiração, configurados
 * em {@code spring.security.oauth2.resourceserver.jwt.*}) e autoriza por escopo.
 */
@Configuration
public class SecurityConfig {

    public static final String SCOPE_READ = "SCOPE_activation:read";
    public static final String SCOPE_WRITE = "SCOPE_activation:write";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {
        AuthenticationEntryPoint entryPoint = problemEntryPoint(resolver);
        AccessDeniedHandler accessDeniedHandler = problemAccessDeniedHandler(resolver);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(SCOPE_WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*").hasAuthority(SCOPE_READ)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /** Mantém o header WWW-Authenticate padrão e escreve o corpo como Problem Details. */
    private static AuthenticationEntryPoint problemEntryPoint(HandlerExceptionResolver resolver) {
        var bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, ex) -> {
            bearer.commence(request, response, ex);
            resolver.resolveException(request, response, null, ex);
        };
    }

    private static AccessDeniedHandler problemAccessDeniedHandler(HandlerExceptionResolver resolver) {
        var bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, ex) -> {
            bearer.handle(request, response, ex);
            resolver.resolveException(request, response, null, ex);
        };
    }
}
