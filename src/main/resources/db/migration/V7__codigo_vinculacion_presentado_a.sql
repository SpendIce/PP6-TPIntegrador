-- Etapa 2 (issue #22): la aprobacion queda ligada al usuario al que se
-- presento la confirmacion. La primera sesion principal que la abre
-- registra presentado_a y solo ese usuario puede aprobarla: otra sesion
-- principal, o una aprobacion sin presentacion previa, reciben el mismo
-- 404 que un codigo que no sirve. La traduccion HSQLDB vive en
-- db/hsqldb/schema.sql (ADR 0005).
ALTER TABLE codigo_vinculacion
    ADD COLUMN presentado_a BIGINT,
    ADD CONSTRAINT fk_codigo_vinculacion_presentado
        FOREIGN KEY (presentado_a) REFERENCES usuario (id);
