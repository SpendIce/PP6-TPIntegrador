package io.github.spendice.linkshortener.infrastructure.http;

import io.github.spendice.linkshortener.application.CreateLink;
import io.github.spendice.linkshortener.application.CreatedLink;
import io.github.spendice.linkshortener.infrastructure.config.ShortenerProperties;
import io.github.spendice.linkshortener.infrastructure.http.dto.CreateLinkRequest;
import io.github.spendice.linkshortener.infrastructure.http.dto.LinkCreatedResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LinkApiController {

    private static final String REUSE_NOTICE =
            "Este alias puede reutilizarse con otro destino después del vencimiento.";

    private final CreateLink createLink;
    private final String publicBaseUrl;

    LinkApiController(CreateLink createLink, ShortenerProperties properties) {
        this.createLink = createLink;
        String base = properties.publicBaseUrl().toString();
        this.publicBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @PostMapping("/api/links")
    ResponseEntity<LinkCreatedResponse> create(@RequestBody CreateLinkRequest request) {
        CreatedLink created = createLink.create(request.destination());
        LinkCreatedResponse body = new LinkCreatedResponse(
                publicBaseUrl + "/" + created.aliasCode(),
                created.aliasCode(),
                created.expiresAt(),
                REUSE_NOTICE);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
