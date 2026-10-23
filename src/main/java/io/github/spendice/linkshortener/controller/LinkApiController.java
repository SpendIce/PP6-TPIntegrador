package io.github.spendice.linkshortener.controller;

import io.github.spendice.linkshortener.controller.dto.CreateLinkRequest;
import io.github.spendice.linkshortener.service.AcortadorService;
import io.github.spendice.linkshortener.service.EnlaceCreado;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada HTTP de la creación: pasa el destino al service y devuelve el
 * resultado como cuerpo del contrato, sin lógica propia (pass-through).
 */
@RestController
class LinkApiController {

    @Autowired
    AcortadorService service;

    @PostMapping("/api/links")
    ResponseEntity<EnlaceCreado> crear(@RequestBody CreateLinkRequest request) {
        EnlaceCreado creado = service.acortar(request.destination());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(creado);
    }
}
