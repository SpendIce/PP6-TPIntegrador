package io.github.spendice.linkshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Sin `UserDetailsServiceAutoConfiguration`: el servicio autentica con
// OIDC (OidcUserService, issue #19), no con un UserDetailsService; el
// usuario en memoria que esa configuración crea imprimiría una
// contraseña generada en el arranque e instalaría un
// `AuthenticationManager` ajeno al login con Google.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class LinkShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkShortenerApplication.class, args);
    }
}
