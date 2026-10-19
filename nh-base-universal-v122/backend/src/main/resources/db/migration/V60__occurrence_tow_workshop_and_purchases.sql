-- V60 - Fluxo revisado do NH Checklist conforme operação da Novo Horizonte.
-- 1) O colaborador cadastra somente a ocorrência, documentos e fotos.
-- 2) O guincho/reboque possui checklist independente por placa e pode ou não usar a plataforma.
-- 3) O gerente/supervisor da oficina realiza o checklist técnico (avaria + recuperar/trocar).
-- 4) Itens marcados para troca alimentam a aba Compras do Evento.

ALTER TABLE portal_users DROP CONSTRAINT IF EXISTS chk_portal_user_role;
ALTER TABLE portal_users
    ADD CONSTRAINT chk_portal_user_role
    CHECK (role IN ('CONSULTANT', 'ANALYST', 'SUPERVISION_ANALYSIS', 'WORKSHOP_MANAGER', 'TOW_DRIVER', 'ADMIN'));

ALTER TABLE nh_event_records DROP CONSTRAINT IF EXISTS ck_nh_event_status;
ALTER TABLE nh_event_records
    ADD CONSTRAINT ck_nh_event_status CHECK (status IN (
        'WAITING_DOCUMENTS','WAITING_ANALYSIS','IN_ANALYSIS','PENDING','CHECKLIST_COMPLETED','FINALIZED',
        'WAITING_WORKSHOP','IN_WORKSHOP','WORKSHOP_COMPLETED'
    ));

ALTER TABLE nh_event_records
    ADD COLUMN IF NOT EXISTS workshop_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS workshop_started_by VARCHAR(180),
    ADD COLUMN IF NOT EXISTS workshop_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS workshop_completed_by VARCHAR(180);

ALTER TABLE nh_event_checklist_items
    ADD COLUMN IF NOT EXISTS damage_state VARCHAR(16) NOT NULL DEFAULT 'UNASSESSED',
    ADD COLUMN IF NOT EXISTS repair_action VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS workshop_notes TEXT,
    ADD COLUMN IF NOT EXISTS workshop_updated_by VARCHAR(180),
    ADD COLUMN IF NOT EXISTS workshop_updated_at TIMESTAMPTZ;

ALTER TABLE nh_event_checklist_items DROP CONSTRAINT IF EXISTS ck_nh_workshop_damage_state;
ALTER TABLE nh_event_checklist_items
    ADD CONSTRAINT ck_nh_workshop_damage_state CHECK (damage_state IN ('UNASSESSED','YES','NO','NOT_APPLICABLE'));
ALTER TABLE nh_event_checklist_items DROP CONSTRAINT IF EXISTS ck_nh_workshop_repair_action;
ALTER TABLE nh_event_checklist_items
    ADD CONSTRAINT ck_nh_workshop_repair_action CHECK (repair_action IN ('NONE','REPAIR','REPLACE'));

CREATE TABLE IF NOT EXISTS nh_tow_records (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    vehicle_plate VARCHAR(20) NOT NULL,
    vehicle_model VARCHAR(180),
    vehicle_category VARCHAR(24),
    provider_name VARCHAR(180),
    driver_name VARCHAR(180),
    driver_phone VARCHAR(40),
    general_notes TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by_username VARCHAR(160) NOT NULL,
    created_by_name VARCHAR(180) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    completed_by_name VARCHAR(180),
    CONSTRAINT ck_nh_tow_record_status CHECK (status IN ('DRAFT','COMPLETED')),
    CONSTRAINT ck_nh_tow_record_category CHECK (vehicle_category IS NULL OR vehicle_category IN ('MOTORCYCLE','LIGHT_CAR','UTILITY','TRUCK'))
);

CREATE INDEX IF NOT EXISTS idx_nh_tow_record_plate_completed
    ON nh_tow_records(vehicle_plate, completed_at DESC, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_nh_tow_record_status
    ON nh_tow_records(status, created_at DESC);

CREATE TABLE IF NOT EXISTS nh_tow_record_items (
    id UUID PRIMARY KEY,
    tow_record_id UUID NOT NULL REFERENCES nh_tow_records(id) ON DELETE CASCADE,
    template_item_id UUID REFERENCES nh_tow_checklist_template_items(id) ON DELETE SET NULL,
    section VARCHAR(100) NOT NULL,
    label VARCHAR(180) NOT NULL,
    sort_order INTEGER NOT NULL,
    answer VARCHAR(16) NOT NULL DEFAULT 'UNANSWERED',
    notes TEXT,
    updated_by VARCHAR(180),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_nh_tow_record_answer CHECK (answer IN ('UNANSWERED','YES','NO','NOT_APPLICABLE')),
    CONSTRAINT uq_nh_tow_record_item UNIQUE(tow_record_id, label)
);

CREATE INDEX IF NOT EXISTS idx_nh_tow_record_items_record
    ON nh_tow_record_items(tow_record_id, sort_order);

CREATE TABLE IF NOT EXISTS nh_tow_record_photos (
    id UUID PRIMARY KEY,
    tow_record_id UUID NOT NULL REFERENCES nh_tow_records(id) ON DELETE CASCADE,
    photo_kind VARCHAR(20) NOT NULL,
    original_name VARCHAR(240) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    file_size BIGINT NOT NULL,
    file_data BYTEA NOT NULL,
    notes VARCHAR(1000),
    uploaded_by VARCHAR(180) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_nh_tow_photo_kind CHECK (photo_kind IN ('FRONT','LEFT_SIDE','RIGHT_SIDE','REAR','OTHER')),
    CONSTRAINT ck_nh_tow_photo_size CHECK (file_size > 0 AND file_size <= 15728640)
);

CREATE INDEX IF NOT EXISTS idx_nh_tow_record_photos_record
    ON nh_tow_record_photos(tow_record_id, created_at);

ALTER TABLE nh_event_records
    ADD COLUMN IF NOT EXISTS linked_tow_record_id UUID REFERENCES nh_tow_records(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_nh_event_linked_tow ON nh_event_records(linked_tow_record_id);

CREATE TABLE IF NOT EXISTS nh_event_purchase_items (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES nh_event_records(id) ON DELETE CASCADE,
    checklist_item_id UUID NOT NULL REFERENCES nh_event_checklist_items(id) ON DELETE CASCADE,
    third_party_id UUID REFERENCES nh_event_third_parties(id) ON DELETE CASCADE,
    item_label VARCHAR(180) NOT NULL,
    supplier VARCHAR(180),
    amount NUMERIC(14,2),
    delivery_deadline DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(180),
    CONSTRAINT uq_nh_event_purchase_checklist UNIQUE(checklist_item_id),
    CONSTRAINT ck_nh_event_purchase_status CHECK (status IN ('REQUESTED','ORDERED','RECEIVED','CANCELLED')),
    CONSTRAINT ck_nh_event_purchase_amount CHECK (amount IS NULL OR amount >= 0)
);
CREATE INDEX IF NOT EXISTS idx_nh_event_purchase_event ON nh_event_purchase_items(event_id, status, created_at);

-- O checklist do guincho é apenas de acessórios/pertences e condição de entrega.
UPDATE nh_tow_checklist_template_items SET active = FALSE, updated_at = NOW();
INSERT INTO nh_tow_checklist_template_items(id,section,label,sort_order,active)
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
 ('Pertences','Objetos pessoais',11),
 ('Pertences','Ferramentas ou itens soltos',12),
 ('Pertences','Bagagem/Carga',13),
 ('Pertences','Capacete (quando motocicleta)',14)
) AS x(section,label,ord)
ON CONFLICT (label) DO UPDATE
SET section = EXCLUDED.section, sort_order = EXCLUDED.sort_order, active = TRUE, updated_at = NOW();

-- Checklist técnico da oficina: cobertura ampla de componentes do veículo.
-- Itens existentes são preservados; os novos são adicionados sem duplicidade.
INSERT INTO nh_event_checklist_template_items(id,vehicle_category,section,label,sort_order)
SELECT gen_random_uuid(), 'LIGHT_CAR', x.section, x.label, x.ord
FROM (VALUES
 ('Segurança','Kit airbag',100),('Segurança','Airbag motorista',101),('Segurança','Airbag passageiro',102),('Segurança','Airbags laterais/cortina',103),
 ('Segurança','Cinto de segurança dianteiro direito',104),('Segurança','Cinto de segurança dianteiro esquerdo',105),('Segurança','Cintos de segurança traseiros',106),
 ('Direção/Pedais','Volante',110),('Direção/Pedais','Coluna de direção',111),('Direção/Pedais','Caixa de direção',112),('Direção/Pedais','Pedal de freio',113),('Direção/Pedais','Pedal de acelerador',114),('Direção/Pedais','Pedal de embreagem',115),
 ('Mecânica','Motor',120),('Mecânica','Câmbio/Transmissão',121),('Mecânica','Embreagem',122),('Mecânica','Sistema de arrefecimento',123),('Mecânica','Radiador',124),('Mecânica','Condensador do ar-condicionado',125),('Mecânica','Ventoinha',126),('Mecânica','Escapamento',127),('Mecânica','Catalisador',128),
 ('Suspensão/Freios','Suspensão dianteira direita',130),('Suspensão/Freios','Suspensão dianteira esquerda',131),('Suspensão/Freios','Suspensão traseira direita',132),('Suspensão/Freios','Suspensão traseira esquerda',133),('Suspensão/Freios','Sistema de freio',134),('Suspensão/Freios','ABS/Sensores de roda',135),
 ('Estrutura','Longarina dianteira direita',140),('Estrutura','Longarina dianteira esquerda',141),('Estrutura','Painel dianteiro',142),('Estrutura','Travessa dianteira',143),('Estrutura','Coluna A direita',144),('Estrutura','Coluna A esquerda',145),('Estrutura','Coluna B direita',146),('Estrutura','Coluna B esquerda',147),('Estrutura','Assoalho',148),('Estrutura','Painel traseiro',149),
 ('Elétrica/Eletrônica','Bateria',160),('Elétrica/Eletrônica','Alternador',161),('Elétrica/Eletrônica','Motor de partida',162),('Elétrica/Eletrônica','Módulo de injeção/ECU',163),('Elétrica/Eletrônica','Módulo ABS',164),('Elétrica/Eletrônica','Módulo airbag',165),('Elétrica/Eletrônica','Chicote elétrico',166),('Elétrica/Eletrônica','Sensores de estacionamento',167),('Elétrica/Eletrônica','Câmera de ré',168),
 ('Interior','Painel de instrumentos',180),('Interior','Console central',181),('Interior','Central multimídia',182),('Interior','Ar-condicionado',183),('Interior','Banco dianteiro direito',184),('Interior','Banco dianteiro esquerdo',185),('Interior','Banco traseiro',186),('Interior','Forro do teto',187),('Interior','Forro porta dianteira direita',188),('Interior','Forro porta dianteira esquerda',189),
 ('Vidros/Fechaduras','Máquina de vidro dianteira direita',200),('Vidros/Fechaduras','Máquina de vidro dianteira esquerda',201),('Vidros/Fechaduras','Máquina de vidro traseira direita',202),('Vidros/Fechaduras','Máquina de vidro traseira esquerda',203),('Vidros/Fechaduras','Fechadura porta dianteira direita',204),('Vidros/Fechaduras','Fechadura porta dianteira esquerda',205),('Vidros/Fechaduras','Fechadura porta traseira direita',206),('Vidros/Fechaduras','Fechadura porta traseira esquerda',207),
 ('Iluminação','Farol de neblina direito',220),('Iluminação','Farol de neblina esquerdo',221),('Iluminação','Luz de placa',222),('Iluminação','Terceira luz de freio',223)
) AS x(section,label,ord)
ON CONFLICT (vehicle_category,label) DO NOTHING;

INSERT INTO nh_event_checklist_template_items(id,vehicle_category,section,label,sort_order)
SELECT gen_random_uuid(), 'UTILITY', x.section, x.label, x.ord
FROM (VALUES
 ('Segurança','Kit airbag',100),('Segurança','Airbag motorista',101),('Segurança','Airbag passageiro',102),('Segurança','Cintos de segurança',103),
 ('Direção/Pedais','Volante',110),('Direção/Pedais','Coluna de direção',111),('Direção/Pedais','Caixa de direção',112),('Direção/Pedais','Pedais',113),
 ('Mecânica','Motor',120),('Mecânica','Câmbio/Transmissão',121),('Mecânica','Embreagem',122),('Mecânica','Sistema de arrefecimento',123),('Mecânica','Escapamento',124),
 ('Suspensão/Freios','Suspensão dianteira',130),('Suspensão/Freios','Suspensão traseira',131),('Suspensão/Freios','Sistema de freio/ABS',132),
 ('Estrutura','Longarina direita',140),('Estrutura','Longarina esquerda',141),('Estrutura','Painel dianteiro',142),('Estrutura','Travessas',143),('Estrutura','Assoalho',144),
 ('Elétrica/Eletrônica','Bateria',160),('Elétrica/Eletrônica','Alternador',161),('Elétrica/Eletrônica','Módulos eletrônicos',162),('Elétrica/Eletrônica','Chicote elétrico',163),('Elétrica/Eletrônica','Sensores/Câmera de ré',164),
 ('Interior','Painel de instrumentos',180),('Interior','Central multimídia',181),('Interior','Ar-condicionado',182),('Interior','Bancos',183),('Interior','Forros internos',184),
 ('Carga/Implemento','Piso do compartimento de carga',200),('Carga/Implemento','Portas do compartimento de carga',201),('Carga/Implemento','Fechaduras do compartimento de carga',202)
) AS x(section,label,ord)
ON CONFLICT (vehicle_category,label) DO NOTHING;

INSERT INTO nh_event_checklist_template_items(id,vehicle_category,section,label,sort_order)
SELECT gen_random_uuid(), 'TRUCK', x.section, x.label, x.ord
FROM (VALUES
 ('Segurança','Airbag (quando equipado)',100),('Segurança','Cintos de segurança',101),
 ('Direção/Pedais','Volante',110),('Direção/Pedais','Coluna/Caixa de direção',111),('Direção/Pedais','Pedais',112),
 ('Mecânica','Motor',120),('Mecânica','Câmbio/Transmissão',121),('Mecânica','Embreagem',122),('Mecânica','Sistema de arrefecimento',123),('Mecânica','Escapamento',124),('Mecânica','Diferencial',125),
 ('Freios/Suspensão','Sistema de freio',130),('Freios/Suspensão','ABS',131),('Freios/Suspensão','Suspensão dianteira',132),('Freios/Suspensão','Suspensão traseira',133),
 ('Estrutura','Longarina direita',140),('Estrutura','Longarina esquerda',141),('Estrutura','Travessas do chassi',142),('Estrutura','Eixos',143),
 ('Elétrica/Eletrônica','Baterias',160),('Elétrica/Eletrônica','Alternador',161),('Elétrica/Eletrônica','Módulos eletrônicos',162),('Elétrica/Eletrônica','Chicote elétrico',163),
 ('Cabine/Interior','Painel de instrumentos',180),('Cabine/Interior','Central multimídia/Rádio',181),('Cabine/Interior','Ar-condicionado',182),('Cabine/Interior','Bancos',183),('Cabine/Interior','Cama da cabine (quando houver)',184),
 ('Implemento','Sistema hidráulico do implemento',200),('Implemento','Fechaduras/travas do implemento',201),('Implemento','Piso do implemento',202)
) AS x(section,label,ord)
ON CONFLICT (vehicle_category,label) DO NOTHING;

INSERT INTO nh_event_checklist_template_items(id,vehicle_category,section,label,sort_order)
SELECT gen_random_uuid(), 'MOTORCYCLE', x.section, x.label, x.ord
FROM (VALUES
 ('Segurança','Sistema ABS (quando equipado)',100),
 ('Comandos','Guidão',110),('Comandos','Manete de freio',111),('Comandos','Manete de embreagem',112),('Comandos','Pedal de freio',113),('Comandos','Pedal de câmbio',114),('Comandos','Painel de instrumentos',115),
 ('Mecânica','Motor',120),('Mecânica','Câmbio/Transmissão',121),('Mecânica','Embreagem',122),('Mecânica','Sistema de arrefecimento',123),('Mecânica','Escapamento',124),
 ('Suspensão/Freios','Suspensão dianteira',130),('Suspensão/Freios','Suspensão traseira',131),('Suspensão/Freios','Freio dianteiro',132),('Suspensão/Freios','Freio traseiro',133),
 ('Estrutura','Chassi',140),('Estrutura','Balança traseira',141),
 ('Elétrica','Bateria',160),('Elétrica','Chicote elétrico',161),('Elétrica','Painel/ECU',162),('Elétrica','Pisca dianteiro direito',163),('Elétrica','Pisca dianteiro esquerdo',164),('Elétrica','Pisca traseiro direito',165),('Elétrica','Pisca traseiro esquerdo',166)
) AS x(section,label,ord)
ON CONFLICT (vehicle_category,label) DO NOTHING;

COMMENT ON TABLE nh_tow_records IS 'Atendimentos/checklists de reboque independentes de evento, localizados principalmente pela placa.';
COMMENT ON TABLE nh_event_purchase_items IS 'Itens para compra gerados exclusivamente a partir do checklist da oficina marcado como TROCA.';
COMMENT ON COLUMN nh_event_checklist_items.damage_state IS 'Avaliação da oficina: avaria sim/não/não aplicável.';
COMMENT ON COLUMN nh_event_checklist_items.repair_action IS 'Ação da oficina quando há avaria: recuperar/reparar ou trocar.';
