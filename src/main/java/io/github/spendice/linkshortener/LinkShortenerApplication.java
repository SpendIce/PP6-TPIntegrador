package io.github.spendice.linkshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Sin `UserDetailsServiceAutoConfiguration`: el servicio no autentica todavía
// (#19) y el usuario en memoria que esa configuración crea imprime una
// contraseña generada en el arranque, además de instalar un
// `AuthenticationManager` que no es el del login con Google.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class LinkShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkShortenerApplication.class, args);
    }
}
