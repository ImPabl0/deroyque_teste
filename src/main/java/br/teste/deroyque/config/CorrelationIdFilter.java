package br.teste.deroyque.config;

import br.teste.deroyque.utils.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Aceita o {@code X-Correlation-Id} do consumidor (se bem formado) ou gera um novo; coloca no MDC para
 * aparecer em todos os logs da requisição e devolve no header da resposta.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String received = request.getHeader(CorrelationId.HEADER);
        String correlationId = received != null && VALID.matcher(received).matches()
                ? received
                : UUID.randomUUID().toString().replace("-", "");
        response.setHeader(CorrelationId.HEADER, correlationId);
        try (var ignored = MDC.putCloseable(CorrelationId.MDC_KEY, correlationId)) {
            chain.doFilter(request, response);
        }
    }
}
