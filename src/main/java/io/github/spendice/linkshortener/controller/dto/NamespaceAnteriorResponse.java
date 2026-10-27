package io.github.spendice.linkshortener.controller.dto;

import java.time.Instant;

public record NamespaceAnteriorResponse(String namespace, Instant graceEndsAt) {
}
