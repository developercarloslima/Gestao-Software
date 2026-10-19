-- V65 - vincula fotos/evidencias da oficina ao item especifico do checklist.

ALTER TABLE nh_event_attachments
    ADD COLUMN IF NOT EXISTS checklist_item_id UUID REFERENCES nh_event_checklist_items(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_nh_event_attachment_checklist_item
    ON nh_event_attachments(event_id, checklist_item_id, created_at);

COMMENT ON COLUMN nh_event_attachments.checklist_item_id IS
'Item do checklist tecnico da oficina ao qual a foto/evidencia esta vinculada; nulo para arquivos gerais da oficina/evento.';
