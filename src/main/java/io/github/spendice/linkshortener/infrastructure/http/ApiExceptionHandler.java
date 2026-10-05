package io.github.spendice.linkshortener.infrastructure.http;

import io.github.spendice.linkshortener.domain.InvalidDestinationException;
import io.github.spendice.linkshortener.infrastructure.http.dto.ApiErrorResponse;
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

    /** Cuerpo ausente, JSON inválido o propiedades desconocidas del contrato. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST",
                        "El cuerpo de la solicitud no es un JSON válido del contrato."));
    }
}
