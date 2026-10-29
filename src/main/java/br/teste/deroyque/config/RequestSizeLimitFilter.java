package br.teste.deroyque.config;

import br.teste.deroyque.responses.ProblemTypes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Recusa corpos maiores que o limite antes de chegarem ao parser JSON. Baseado no Content-Length;
 * corpos chunked sem tamanho declarado são barrados no gateway (ver README).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeLimitFilter(@Value("${api.max-request-size:16KB}") DataSize maxSize) {
        this.maxBytes = maxSize.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("""
                    {"type":"%s","title":"Requisição muito grande","status":413,\
                    "detail":"O corpo da requisição excede o limite de %d bytes."}"""
                    .formatted(ProblemTypes.PAYLOAD_TOO_LARGE, maxBytes));
            return;
        }
        chain.doFilter(request, response);
    }
}
