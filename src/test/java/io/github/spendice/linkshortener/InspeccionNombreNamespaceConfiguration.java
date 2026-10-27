package io.github.spendice.linkshortener;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registra {@link InspeccionNombreNamespace} en el entity manager de la
 * suite que la importa: es el punto donde una prueba puede frenar el
 * insert dentro de la sección crítica de adquisición (issue #24).
 * {@code @TestConfiguration} queda fuera del component scan: solo entra
 * al contexto cuando una prueba la declara explícitamente.
 */
@TestConfiguration
public class InspeccionNombreNamespaceConfiguration {

    @Bean
    InspeccionNombreNamespace inspeccionNombreNamespace() {
        return new InspeccionNombreNamespace();
    }

    @Bean
    HibernatePropertiesCustomizer registrarInspeccionNombreNamespace(
            InspeccionNombreNamespace inspeccion) {
        return propiedades -> propiedades.put(
                AvailableSettings.STATEMENT_INSPECTOR, inspeccion);
    }
}
