package io.github.spendice.linkshortener.controller.dto;

import java.time.Instant;

/** Cuerpo 201 de {@code POST /api/vinculacion} (issue #22). */
public record CodigoVinculacionResponse(String codigo, String confirmacionUrl,
                                        Instant expiresAt) {
}
