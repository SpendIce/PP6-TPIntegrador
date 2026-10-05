package io.github.spendice.linkshortener.infrastructure.http.dto;

import java.time.Instant;

/**
 * DTO de salida de la creación: URL pública acortada, alias asignado,
 * instante de vencimiento y aviso de reutilización posterior del alias.
 */
public record LinkCreatedResponse(String shortUrl, String alias, Instant expiresAt, String reuseNotice) {
}
