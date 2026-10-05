-- Reciclaje de alias (reutilizacion de vencidos): la busqueda de
-- candidatos filtra la asignacion actual de cada alias por vence_en.
-- El indice permite encontrar las vencidas sin recorrer todo el
-- historial; no altera datos existentes.
CREATE INDEX ix_asignacion_vence_en ON asignacion (vence_en);
