-- V62 - garante o checklist padrão do reboque e permite foto/evidência por item.

ALTER TABLE nh_tow_record_photos
    ADD COLUMN IF NOT EXISTS checklist_item_id UUID REFERENCES nh_tow_record_items(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_nh_tow_photo_checklist_item
    ON nh_tow_record_photos(checklist_item_id, created_at);

-- O checklist do guincho/reboque deve sempre chegar pronto para preenchimento.
-- Mantemos somente acessórios e pertences; a avaliação técnica de avarias pertence à oficina.
UPDATE nh_tow_checklist_template_items
   SET active = FALSE,
       updated_at = NOW();

INSERT INTO nh_tow_checklist_template_items(id, section, label, sort_order, active)
SELECT gen_random_uuid(), x.section, x.label, x.ord, TRUE
FROM (VALUES
    ('Acessórios','Chave do veículo',1),
    ('Acessórios','Estepe',2),
    ('Acessórios','Triângulo',3),
    ('Acessórios','Macaco',4),
    ('Acessórios','Chave de roda',5),
    ('Acessórios','Central multimídia',6),
    ('Acessórios','Som/Rádio',7),
    ('Acessórios','Tapetes',8),
    ('Acessórios','Antena',9),
    ('Acessórios','Manual/Documentos do veículo',10),
    ('Acessórios','Chave reserva',11),
    ('Pertences','Objetos pessoais',20),
    ('Pertences','Ferramentas ou itens soltos',21),
    ('Pertences','Bagagem/Carga',22),
    ('Pertences','Capacete (quando motocicleta)',23)
) AS x(section,label,ord)
ON CONFLICT (label) DO UPDATE
SET section = EXCLUDED.section,
    sort_order = EXCLUDED.sort_order,
    active = TRUE,
    updated_at = NOW();

-- Corrige rascunhos criados em versões anteriores: remove apenas itens de templates
-- desativados e inclui os itens oficiais que estejam faltando.
DELETE FROM nh_tow_record_items i
USING nh_tow_records r
WHERE i.tow_record_id = r.id
  AND r.status = 'DRAFT'
  AND i.template_item_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
        FROM nh_tow_checklist_template_items t
       WHERE t.id = i.template_item_id
         AND t.active = TRUE
  );

INSERT INTO nh_tow_record_items(
    id, tow_record_id, template_item_id, section, label, sort_order, answer, updated_at
)
SELECT gen_random_uuid(), r.id, t.id, t.section, t.label, t.sort_order, 'UNANSWERED', NOW()
  FROM nh_tow_records r
 CROSS JOIN nh_tow_checklist_template_items t
 WHERE r.status = 'DRAFT'
   AND t.active = TRUE
ON CONFLICT (tow_record_id, label) DO NOTHING;

COMMENT ON COLUMN nh_tow_record_photos.checklist_item_id IS
'Item do checklist de reboque ao qual a foto/evidência está vinculada; nulo para fotos gerais do veículo.';
