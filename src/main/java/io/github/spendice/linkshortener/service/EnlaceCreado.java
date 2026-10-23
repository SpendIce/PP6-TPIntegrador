package io.github.spendice.linkshortener.service;

import java.time.Instant;

/**
 * Resultado del caso de uso de creación: el controller lo serializa
 * directo como cuerpo del 201 de {@code POST /api/links}. Los nombres
 * de los componentes son los del contrato (estilo {@code ClienteResumen}
 * del ejemplo de clase).
 */
public record EnlaceCreado(String shortUrl, String alias, Instant expiresAt, String reuseNotice) {
}
