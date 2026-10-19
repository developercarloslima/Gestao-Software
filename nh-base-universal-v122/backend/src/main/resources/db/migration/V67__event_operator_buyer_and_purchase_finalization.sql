-- V67 - Perfis operacionais de Eventos e Compras/Financeiro.
-- Preserva todos os dados existentes e amplia somente as permissões/estados necessários.

ALTER TABLE portal_users DROP CONSTRAINT IF EXISTS chk_portal_user_role;
ALTER TABLE portal_users
    ADD CONSTRAINT chk_portal_user_role
    CHECK (role IN (
        'CONSULTANT','ANALYST','SUPERVISION_ANALYSIS','WORKSHOP_MANAGER',
        'TOW_DRIVER','EVENT_OPERATOR','BUYER','ADMIN'
    ));

-- Normaliza os estados legados para o fluxo atual do Financeiro.
UPDATE nh_event_purchase_items SET status='REQUESTED' WHERE status='ORDERED';
UPDATE nh_event_purchase_items SET status='FINALIZED' WHERE status IN ('RECEIVED','CANCELLED');

ALTER TABLE nh_event_purchase_items DROP CONSTRAINT IF EXISTS ck_nh_event_purchase_status;
ALTER TABLE nh_event_purchase_items
    ADD CONSTRAINT ck_nh_event_purchase_status
    CHECK (status IN ('REQUESTED','FINALIZED'));

COMMENT ON COLUMN portal_users.role IS
    'Perfis internos. EVENT_OPERATOR cadastra/edita eventos. BUYER opera Compras do Evento.';
