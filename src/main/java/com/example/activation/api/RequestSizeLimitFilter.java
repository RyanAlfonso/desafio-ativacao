package com.example.activation.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Recusa corpos maiores que o necessário antes de qualquer parsing. O payload válido tem
 * menos de 300 bytes; 16 KB dá folga sem deixar o serviço ler corpos arbitrariamente grandes.
 * Requisições sem Content-Length (chunked) são recusadas pelo mesmo motivo.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    static final long MAX_BODY_BYTES = 16 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean hasBody = "POST".equals(request.getMethod()) || "PUT".equals(request.getMethod());
        long length = request.getContentLengthLong();
        if (hasBody && (length < 0 || length > MAX_BODY_BYTES)) {
            response.setStatus(length < 0 ? HttpStatus.LENGTH_REQUIRED.value() : HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"title":"Corpo da requisição inválido","status":%d,"detail":"O corpo deve ter Content-Length de até %d bytes."}"""
                    .formatted(response.getStatus(), MAX_BODY_BYTES));
            return;
        }
        chain.doFilter(request, response);
    }
}
