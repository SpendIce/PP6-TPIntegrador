-- Etapa 2 (issue #22): codigos de vinculacion por QR de un solo uso
-- (ADR 0007). El dispositivo que espera muestra el QR con la URL de
-- confirmacion; una sesion principal lo aprueba (usuario_id) y la
-- siguiente consulta del dispositivo lo consume y abre la sesion
-- vinculada. El vencimiento se evalua en el servidor al consultar, como
-- en asignacion: no hay tarea de limpieza. La traduccion HSQLDB vive en
-- db/hsqldb/schema.sql (ADR 0005).
CREATE TABLE codigo_vinculacion (
    codigo     VARCHAR(64) PRIMARY KEY,
    estado     VARCHAR(16) NOT NULL,
    creada_en  TIMESTAMPTZ NOT NULL,
    vence_en   TIMESTAMPTZ NOT NULL,
    usuario_id BIGINT,
    CONSTRAINT fk_codigo_vinculacion_usuario
        FOREIGN KEY (usuario_id) REFERENCES usuario (id)
);
