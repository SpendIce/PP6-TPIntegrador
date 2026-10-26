package io.github.spendice.linkshortener.controller;

import io.github.spendice.linkshortener.controller.dto.ApiErrorResponse;
import io.github.spendice.linkshortener.service.InvalidDestinationException;
import io.github.spendice.linkshortener.service.InvalidNamespaceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(InvalidDestinationException.class)
    ResponseEntity<ApiErrorResponse> invalidDestination(InvalidDestinationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(exception.reason().name(), exception.getMessage()));
    }

    /**
     * Un namespace con formato inválido o reservado es un error de la
     * solicitud ({@code 400}); uno tomado o un usuario que ya eligió es
     * un conflicto con el estado actual ({@code 409}).
     */
    @ExceptionHandler(InvalidNamespaceException.class)
    ResponseEntity<ApiErrorResponse> invalidNamespace(InvalidNamespaceException exception) {
        HttpStatus status = switch (exception.reason()) {
            case NAMESPACE_TAKEN, NAMESPACE_ALREADY_CHOSEN -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(exception.reason().name(), exception.getMessage()));
    }

    /** Cuerpo ausente, JSON inválido o propiedades desconocidas del contrato. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST",
                        "El cuerpo de la solicitud no es un JSON válido del contrato."));
    }
}
