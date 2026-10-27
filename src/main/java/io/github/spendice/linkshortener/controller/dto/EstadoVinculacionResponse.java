package io.github.spendice.linkshortener.controller.dto;

/**
 * Cuerpo de {@code GET /api/vinculacion/{codigo}} y de {@code POST
 * /api/vinculacion/{codigo}/aprobar} (issue #22): {@code pendiente},
 * {@code aprobada} o {@code vencida}.
 */
public record EstadoVinculacionResponse(String estado) {
}
