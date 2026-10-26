-- Etapa 2 (issue #20): namespace elegido por el usuario en su primer
-- login. Se guarda normalizado a minusculas, asi que la restriccion
-- unica sobre la columna ya es unica sin distinguir mayusculas. La
-- traduccion HSQLDB vive en db/hsqldb/schema.sql (ADR 0005).
ALTER TABLE usuario
    ADD CONSTRAINT uq_usuario_namespace UNIQUE (namespace);
