package io.github.spendice.linkshortener.controller.dto;

/**
 * DTO de {@code GET /api/csrf}: el valor del token CSRF, para clientes
 * que no pueden leer la cookie {@code XSRF-TOKEN} (las extensiones no
 * ven {@code Set-Cookie} desde {@code fetch}). La web puede leer la
 * cookie o el cuerpo indistintamente.
 */
public record CsrfTokenResponse(String token) {
}
