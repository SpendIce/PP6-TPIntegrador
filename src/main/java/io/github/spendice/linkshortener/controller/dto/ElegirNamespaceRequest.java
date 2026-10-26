package io.github.spendice.linkshortener.controller.dto;

/**
 * DTO de {@code POST /api/namespace}: nombre propuesto por el usuario en
 * su primer login. Se normaliza a minúsculas antes de validar y guardar.
 */
public record ElegirNamespaceRequest(String namespace) {
}
