package io.github.spendice.linkshortener.controller.dto;

/**
 * DTO de error de la API: {@code error} es el código estable del
 * contrato (enum de {@code ApiError} en openapi.yaml) y {@code message}
 * una descripción legible para el usuario.
 */
public record ApiErrorResponse(String error, String message) {
}
