-- V57: amplia o identificador técnico das entradas de auditoria.
-- A revisão contratual usa INSPECTION_CONTRACT_CHANGE_REQUEST (34 caracteres),
-- acima do limite histórico de VARCHAR(30).
ALTER TABLE catalog_change_audit
    ALTER COLUMN item_type TYPE VARCHAR(80);
