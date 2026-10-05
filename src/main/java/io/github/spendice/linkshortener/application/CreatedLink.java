package io.github.spendice.linkshortener.application;

import java.time.Instant;

/** Resultado del caso de uso de creación. */
public record CreatedLink(String aliasCode, String destination, Instant createdAt, Instant expiresAt) {
}
