package io.github.spendice.linkshortener;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verificación compartida de lo persistido para un alias: su única
 * fila de {@code asignacion} con el destino esperado, la referencia
 * {@code asignacion_actual_id} del alias apuntando a esa fila y los
 * instantes de creación y vencimiento conservados. La usan las suites
 * que levantan contextos programáticos sobre su propio contenedor
 * ({@link EvolucionDuracionHttpTest}, {@link ReinicioServicioHttpTest}).
 */
final class AsignacionesPersistidas {

    private AsignacionesPersistidas() {
    }

    static void verificar(JdbcTemplate db, String alias,
                          String destino, Instant creadaEn, Instant venceEn) {
        Map<String, Object> row = db.queryForMap(
                "SELECT id, destino FROM asignacion WHERE alias_codigo = ?", alias);
        assertThat(row.get("destino")).isEqualTo(destino);
        Long id = (Long) row.get("id");
        assertThat(db.queryForObject(
                "SELECT asignacion_actual_id FROM alias WHERE codigo = ?",
                Long.class, alias)).isEqualTo(id);
        assertThat(db.queryForObject(
                "SELECT creada_en FROM asignacion WHERE id = ?",
                OffsetDateTime.class, id).toInstant()).isEqualTo(creadaEn);
        assertThat(db.queryForObject(
                "SELECT vence_en FROM asignacion WHERE id = ?",
                OffsetDateTime.class, id).toInstant()).isEqualTo(venceEn);
    }
}
