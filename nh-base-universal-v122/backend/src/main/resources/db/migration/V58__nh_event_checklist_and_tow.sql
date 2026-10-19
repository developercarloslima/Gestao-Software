-- NH Checklist integrado à plataforma principal.
-- Eventos/sinistros, checklist por categoria, terceiros e checklist de guincho/reboque.

CREATE TABLE IF NOT EXISTS nh_event_records (
    id UUID PRIMARY KEY,
    protocol VARCHAR(40) NOT NULL UNIQUE,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'WAITING_DOCUMENTS',
    occurred_at TIMESTAMPTZ,
    location VARCHAR(260),
    description TEXT,
    associate_name VARCHAR(180) NOT NULL,
    associate_number VARCHAR(80) NOT NULL,
    plan_name VARCHAR(140) NOT NULL,
    vehicle_plate VARCHAR(20) NOT NULL,
    vehicle_model VARCHAR(180) NOT NULL,
    vehicle_category VARCHAR(24) NOT NULL,
    vehicle_year VARCHAR(20),
    vehicle_color VARCHAR(80),
    chassis VARCHAR(80),
    has_third_party BOOLEAN NOT NULL DEFAULT FALSE,
    tow_service BOOLEAN NOT NULL DEFAULT FALSE,
    pending_reason TEXT,
    manager_notes TEXT,
    created_by_username VARCHAR(160) NOT NULL,
    created_by_name VARCHAR(180) NOT NULL,
    created_by_collaborator_id UUID REFERENCES consultants(id) ON DELETE SET NULL,
    last_updated_by VARCHAR(180),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    registration_completed_at TIMESTAMPTZ,
    analysis_completed_at TIMESTAMPTZ,
    finalized_at TIMESTAMPTZ,
    CONSTRAINT ck_nh_event_type CHECK (event_type IN (
        'COLLISION','GLASS','THEFT','FIRE','COLLISION_FIRE','SETTLEMENT_RELEASE'
    )),
    CONSTRAINT ck_nh_event_status CHECK (status IN (
        'WAITING_DOCUMENTS','WAITING_ANALYSIS','IN_ANALYSIS','PENDING','CHECKLIST_COMPLETED','FINALIZED'
    )),
    CONSTRAINT ck_nh_event_vehicle_category CHECK (vehicle_category IN (
        'MOTORCYCLE','LIGHT_CAR','UTILITY','TRUCK'
    ))
);

CREATE INDEX IF NOT EXISTS idx_nh_event_status_created ON nh_event_records(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_nh_event_plate ON nh_event_records(vehicle_plate);
CREATE INDEX IF NOT EXISTS idx_nh_event_associate_number ON nh_event_records(associate_number);
CREATE INDEX IF NOT EXISTS idx_nh_event_creator ON nh_event_records(created_by_collaborator_id, created_at DESC);

CREATE TABLE IF NOT EXISTS nh_event_checklist_template_items (
    id UUID PRIMARY KEY,
    vehicle_category VARCHAR(24) NOT NULL,
    section VARCHAR(100) NOT NULL,
    label VARCHAR(180) NOT NULL,
    sort_order INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_nh_template_vehicle_category CHECK (vehicle_category IN (
        'MOTORCYCLE','LIGHT_CAR','UTILITY','TRUCK'
    )),
    CONSTRAINT uq_nh_template_item UNIQUE(vehicle_category, label)
);

CREATE INDEX IF NOT EXISTS idx_nh_template_active_order
    ON nh_event_checklist_template_items(vehicle_category, active, sort_order);

CREATE TABLE IF NOT EXISTS nh_event_third_parties (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES nh_event_records(id) ON DELETE CASCADE,
    name VARCHAR(180),
    document VARCHAR(60),
    phone VARCHAR(30),
    vehicle_plate VARCHAR(20),
    vehicle_model VARCHAR(180) NOT NULL,
    vehicle_category VARCHAR(24) NOT NULL,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_nh_third_vehicle_category CHECK (vehicle_category IN (
        'MOTORCYCLE','LIGHT_CAR','UTILITY','TRUCK'
    ))
);

CREATE INDEX IF NOT EXISTS idx_nh_third_event ON nh_event_third_parties(event_id, created_at);

CREATE TABLE IF NOT EXISTS nh_event_checklist_items (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES nh_event_records(id) ON DELETE CASCADE,
    third_party_id UUID REFERENCES nh_event_third_parties(id) ON DELETE CASCADE,
    template_item_id UUID REFERENCES nh_event_checklist_template_items(id) ON DELETE SET NULL,
    section VARCHAR(100) NOT NULL,
    label VARCHAR(180) NOT NULL,
    sort_order INTEGER NOT NULL,
    reported_state VARCHAR(24) NOT NULL DEFAULT 'UNANSWERED',
    reported_notes TEXT,
    analysis_state VARCHAR(24) NOT NULL DEFAULT 'UNASSESSED',
    analysis_notes TEXT,
    updated_by VARCHAR(180),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_nh_reported_state CHECK (reported_state IN (
        'UNANSWERED','DAMAGED','NO_DAMAGE','NOT_APPLICABLE'
    )),
    CONSTRAINT ck_nh_analysis_state CHECK (analysis_state IN (
        'UNASSESSED','CLAIM_RELATED','PREEXISTING','NOT_DAMAGED','NOT_APPLICABLE'
    ))
);

CREATE INDEX IF NOT EXISTS idx_nh_checklist_event ON nh_event_checklist_items(event_id, third_party_id, sort_order);

CREATE TABLE IF NOT EXISTS nh_tow_checklist_template_items (
    id UUID PRIMARY KEY,
    section VARCHAR(100) NOT NULL,
    label VARCHAR(180) NOT NULL UNIQUE,
    sort_order INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS nh_event_tow_items (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES nh_event_records(id) ON DELETE CASCADE,
    template_item_id UUID REFERENCES nh_tow_checklist_template_items(id) ON DELETE SET NULL,
    section VARCHAR(100) NOT NULL,
    label VARCHAR(180) NOT NULL,
    sort_order INTEGER NOT NULL,
    answer VARCHAR(16) NOT NULL DEFAULT 'UNANSWERED',
    notes TEXT,
    updated_by VARCHAR(180),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_nh_tow_answer CHECK (answer IN ('UNANSWERED','YES','NO','NOT_APPLICABLE')),
    CONSTRAINT uq_nh_tow_event_item UNIQUE(event_id, label)
);

CREATE INDEX IF NOT EXISTS idx_nh_tow_event ON nh_event_tow_items(event_id, sort_order);

CREATE TABLE IF NOT EXISTS nh_event_document_requirements (
    id UUID PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL,
    attachment_kind VARCHAR(40) NOT NULL,
    label VARCHAR(180) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL,
    CONSTRAINT uq_nh_event_requirement UNIQUE(event_type, attachment_kind),
    CONSTRAINT ck_nh_req_event_type CHECK (event_type IN (
        'COLLISION','GLASS','THEFT','FIRE','COLLISION_FIRE','SETTLEMENT_RELEASE'
    ))
);

CREATE TABLE IF NOT EXISTS nh_event_attachments (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES nh_event_records(id) ON DELETE CASCADE,
    third_party_id UUID REFERENCES nh_event_third_parties(id) ON DELETE SET NULL,
    context VARCHAR(24) NOT NULL,
    attachment_kind VARCHAR(40) NOT NULL,
    original_name VARCHAR(240) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    file_size BIGINT NOT NULL,
    file_data BYTEA NOT NULL,
    notes VARCHAR(1000),
    uploaded_by VARCHAR(180) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_nh_attachment_context CHECK (context IN (
        'EVENT','DOCUMENT','PRE_COLLISION','TOW','THIRD_PARTY','OTHER'
    )),
    CONSTRAINT ck_nh_attachment_size CHECK (file_size > 0 AND file_size <= 15728640)
);

CREATE INDEX IF NOT EXISTS idx_nh_attachment_event ON nh_event_attachments(event_id, created_at);
CREATE INDEX IF NOT EXISTS idx_nh_attachment_kind ON nh_event_attachments(event_id, attachment_kind);

CREATE TABLE IF NOT EXISTS nh_event_audit_logs (
    id UUID PRIMARY KEY,
    event_id UUID REFERENCES nh_event_records(id) ON DELETE CASCADE,
    actor_username VARCHAR(160) NOT NULL,
    actor_name VARCHAR(180) NOT NULL,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID,
    details TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_nh_event_audit ON nh_event_audit_logs(event_id, created_at DESC);

-- Templates: motocicleta
INSERT INTO nh_event_checklist_template_items(id,vehicle_category,section,label,sort_order)
SELECT gen_random_uuid(), 'MOTORCYCLE', x.section, x.label, x.ord
FROM (VALUES
 ('Dianteira','Farol',1),('Dianteira','Painel/Carenagem frontal',2),('Dianteira','Guidão',3),('Dianteira','Manetes',4),
 ('Dianteira','Retrovisor direito',5),('Dianteira','Retrovisor esquerdo',6),('Dianteira','Garfo/Suspensão dianteira',7),('Dianteira','Roda/Pneu dianteiro',8),
 ('Lateral direita','Carenagem lateral direita',9),('Lateral direita','Pedaleira direita',10),('Lateral direita','Escapamento',11),
 ('Lateral esquerda','Carenagem lateral esquerda',12),('Lateral esquerda','Pedaleira esquerda',13),
 ('Centro','Tanque',14),('Centro','Banco',15),('Centro','Chassi',16),
 ('Traseira','Lanterna traseira',17),('Traseira','Rabeta',18),('Traseira','Roda/Pneu traseiro',19),
 ('Mecânica','Motor',20),('Mecânica','Transmissão',21)
) AS x(section,label,ord)
ON CONFLICT (vehicle_category,label) DO NOTHING;

-- Templates: carro leve
INSERT INTO nh_event_checklist_template_items(id,vehicle_category,section,label,sort_order)
SELECT gen_random_uuid(), 'LIGHT_CAR', x.section, x.label, x.ord
FROM (VALUES
 ('Dianteira','Para-choque dianteiro',1),('Dianteira','Capô',2),('Dianteira','Grade frontal',3),('Dianteira','Farol direito',4),('Dianteira','Farol esquerdo',5),
 ('Dianteira','Para-lama dianteiro direito',6),('Dianteira','Para-lama dianteiro esquerdo',7),
 ('Lateral direita','Retrovisor direito',8),('Lateral direita','Porta dianteira lado direito',9),('Lateral direita','Porta traseira lado direito',10),
 ('Lateral direita','Caixa de ar direita',11),('Lateral direita','Lateral traseira direita',12),
 ('Lateral esquerda','Retrovisor esquerdo',13),('Lateral esquerda','Porta dianteira lado esquerdo',14),('Lateral esquerda','Porta traseira lado esquerdo',15),
 ('Lateral esquerda','Caixa de ar esquerda',16),('Lateral esquerda','Lateral traseira esquerda',17),
 ('Traseira','Para-choque traseiro',18),('Traseira','Tampa traseira/Porta-malas',19),('Traseira','Lanterna direita',20),('Traseira','Lanterna esquerda',21),
 ('Superior/Vidros','Teto',22),('Superior/Vidros','Para-brisa',23),('Superior/Vidros','Vidro traseiro',24),('Superior/Vidros','Vidros laterais',25),
 ('Rodagem','Roda/Pneu dianteiro direito',26),('Rodagem','Roda/Pneu dianteiro esquerdo',27),('Rodagem','Roda/Pneu traseiro direito',28),('Rodagem','Roda/Pneu traseiro esquerdo',29),
 ('Estrutural/Mecânica','Longarina/Estrutura',30),('Estrutural/Mecânica','Suspensão',31),('Estrutural/Mecânica','Radiador/Condensador',32)
) AS x(section,label,ord)
ON CONFLICT (vehicle_category,label) DO NOTHING;

-- Templates: utilitário
INSERT INTO nh_event_checklist_template_items(id,vehicle_category,section,label,sort_order)
SELECT gen_random_uuid(), 'UTILITY', x.section, x.label, x.ord
FROM (VALUES
 ('Dianteira','Para-choque dianteiro',1),('Dianteira','Capô',2),('Dianteira','Grade frontal',3),('Dianteira','Faróis',4),('Dianteira','Para-lamas dianteiros',5),
 ('Lateral direita','Retrovisor direito',6),('Lateral direita','Porta dianteira direita',7),('Lateral direita','Porta lateral/carga direita',8),('Lateral direita','Lateral/Caixa direita',9),
 ('Lateral esquerda','Retrovisor esquerdo',10),('Lateral esquerda','Porta dianteira esquerda',11),('Lateral esquerda','Porta lateral/carga esquerda',12),('Lateral esquerda','Lateral/Caixa esquerda',13),
 ('Traseira','Para-choque traseiro',14),('Traseira','Portas/Tampa traseira',15),('Traseira','Lanternas traseiras',16),
 ('Superior/Vidros','Teto',17),('Superior/Vidros','Para-brisa',18),('Superior/Vidros','Vidros laterais/traseiros',19),
 ('Rodagem','Rodas/Pneus',20),('Estrutural/Mecânica','Chassi/Longarinas',21),('Estrutural/Mecânica','Suspensão',22),('Estrutural/Mecânica','Radiador/Condensador',23),
 ('Carga','Baú/Carroceria/Compartimento de carga',24)
) AS x(section,label,ord)
ON CONFLICT (vehicle_category,label) DO NOTHING;

-- Templates: caminhão
INSERT INTO nh_event_checklist_template_items(id,vehicle_category,section,label,sort_order)
SELECT gen_random_uuid(), 'TRUCK', x.section, x.label, x.ord
FROM (VALUES
 ('Cabine dianteira','Para-choque dianteiro',1),('Cabine dianteira','Grade frontal',2),('Cabine dianteira','Farol direito',3),('Cabine dianteira','Farol esquerdo',4),
 ('Cabine','Capô/Painel frontal da cabine',5),('Cabine','Para-lamas dianteiros',6),
 ('Cabine direita','Retrovisor direito',7),('Cabine direita','Porta direita',8),('Cabine esquerda','Retrovisor esquerdo',9),('Cabine esquerda','Porta esquerda',10),
 ('Cabine','Para-brisa',11),('Cabine','Teto da cabine',12),
 ('Estrutura','Chassi/Longarinas',13),('Estrutura','Eixos',14),('Estrutura','Suspensão',15),('Estrutura','Tanques/Reservatórios',16),
 ('Rodagem','Rodas/Pneus dianteiros',17),('Rodagem','Rodas/Pneus traseiros',18),
 ('Traseira','Para-choque traseiro',19),('Traseira','Lanternas traseiras',20),
 ('Implemento','Carroceria/Baú/Implemento',21),('Implemento','Laterais do implemento',22),('Implemento','Portas/Tampa do implemento',23),
 ('Mecânica','Motor',24),('Mecânica','Radiador/Arrefecimento',25),('Mecânica','Transmissão',26)
) AS x(section,label,ord)
ON CONFLICT (vehicle_category,label) DO NOTHING;

-- Checklist do guincho/reboque. Não possui sequência fotográfica obrigatória.
INSERT INTO nh_tow_checklist_template_items(id,section,label,sort_order)
SELECT gen_random_uuid(), x.section, x.label, x.ord
FROM (VALUES
 ('Itens do veículo','Chave do veículo',1),
 ('Itens do veículo','Estepe',2),
 ('Itens do veículo','Macaco',3),
 ('Itens do veículo','Chave de roda',4),
 ('Itens do veículo','Triângulo',5),
 ('Itens do veículo','Tapetes',6),
 ('Itens do veículo','Central multimídia',7),
 ('Itens do veículo','Som/Rádio',8),
 ('Itens do veículo','Antena',9),
 ('Itens do veículo','Documentos no veículo',10),
 ('Pertences','Objetos pessoais no interior',11),
 ('Pertences','Ferramentas/itens soltos',12),
 ('Pertences','Carga ou bagagem',13),
 ('Pertences','Capacete (quando motocicleta)',14),
 ('Condição externa','Placa dianteira',15),
 ('Condição externa','Placa traseira',16),
 ('Condição externa','Para-choque dianteiro presente',17),
 ('Condição externa','Para-choque traseiro presente',18),
 ('Condição externa','Capô presente',19),
 ('Condição externa','Para-lama dianteiro direito presente',20),
 ('Condição externa','Para-lama dianteiro esquerdo presente',21),
 ('Condição externa','Porta dianteira direita presente',22),
 ('Condição externa','Porta dianteira esquerda presente',23),
 ('Condição externa','Porta traseira direita presente',24),
 ('Condição externa','Porta traseira esquerda presente',25),
 ('Condição externa','Tampa traseira/Porta-malas presente',26),
 ('Condição externa','Teto presente',27),
 ('Condição externa','Retrovisor direito presente',28),
 ('Condição externa','Retrovisor esquerdo presente',29),
 ('Condição externa','Faróis presentes',30),
 ('Condição externa','Lanternas presentes',31),
 ('Condição externa','Para-brisa presente',32),
 ('Condição externa','Vidros laterais presentes',33),
 ('Condição externa','Rodas/Calotas presentes',34),
 ('Condição externa','Pneus aparentes em condição de remoção',35),
 ('Observação operacional','Veículo com vazamento aparente',36),
 ('Observação operacional','Veículo com peças soltas',37),
 ('Observação operacional','Veículo com avaria estrutural aparente',38),
 ('Observação operacional','Veículo travado/impossibilitado de rodar',39)
) AS x(section,label,ord)
ON CONFLICT (label) DO NOTHING;

-- Requisitos documentais iniciais conforme o briefing do cliente.
INSERT INTO nh_event_document_requirements(id,event_type,attachment_kind,label,required,sort_order)
SELECT gen_random_uuid(), x.event_type, x.kind, x.label, x.required, x.ord
FROM (VALUES
 ('COLLISION','EVENT_PHOTO','Fotos do evento',TRUE,1),
 ('COLLISION','POLICE_REPORT','Boletim de ocorrência',TRUE,2),
 ('COLLISION','PARTICIPATION_PAYMENT','Comprovante da taxa de participação',TRUE,3),
 ('COLLISION','PRE_COLLISION_INSPECTION','Fotos da vistoria anterior à colisão',TRUE,4),
 ('COLLISION','THIRD_PARTY_PHOTO','Fotos do terceiro',FALSE,5),
 ('GLASS','EVENT_PHOTO','Fotos dos vidros/danos',TRUE,1),
 ('GLASS','PARTICIPATION_PAYMENT','Comprovante da taxa de participação',TRUE,2),
 ('GLASS','PRE_COLLISION_INSPECTION','Vistoria anterior',TRUE,3),
 ('GLASS','POLICE_REPORT','Boletim de ocorrência',FALSE,4),
 ('THEFT','POLICE_REPORT','Boletim de ocorrência',TRUE,1),
 ('THEFT','PRE_COLLISION_INSPECTION','Vistoria anterior',TRUE,2),
 ('THEFT','EVENT_PHOTO','Fotos/evidências do evento',FALSE,3),
 ('THEFT','PARTICIPATION_PAYMENT','Comprovante da taxa de participação',FALSE,4),
 ('FIRE','EVENT_PHOTO','Fotos do incêndio/danos',TRUE,1),
 ('FIRE','POLICE_REPORT','Boletim de ocorrência',TRUE,2),
 ('FIRE','PRE_COLLISION_INSPECTION','Vistoria anterior',TRUE,3),
 ('FIRE','PARTICIPATION_PAYMENT','Comprovante da taxa de participação',FALSE,4),
 ('COLLISION_FIRE','EVENT_PHOTO','Fotos do evento/incêndio',TRUE,1),
 ('COLLISION_FIRE','POLICE_REPORT','Boletim de ocorrência',TRUE,2),
 ('COLLISION_FIRE','PARTICIPATION_PAYMENT','Comprovante da taxa de participação',TRUE,3),
 ('COLLISION_FIRE','PRE_COLLISION_INSPECTION','Fotos da vistoria anterior',TRUE,4),
 ('COLLISION_FIRE','THIRD_PARTY_PHOTO','Fotos do terceiro',FALSE,5),
 ('SETTLEMENT_RELEASE','SETTLEMENT_RELEASE','Termo de acordo/quitação assinado',TRUE,1),
 ('SETTLEMENT_RELEASE','OTHER','Documentos complementares',FALSE,2)
) AS x(event_type,kind,label,required,ord)
ON CONFLICT (event_type,attachment_kind) DO NOTHING;

COMMENT ON TABLE nh_event_records IS 'Eventos/sinistros do módulo NH Checklist integrado à plataforma principal.';
COMMENT ON TABLE nh_event_tow_items IS 'Checklist do guincheiro/reboque preenchido no atendimento; fotos podem ser enviadas sem sequência obrigatória.';
COMMENT ON COLUMN nh_event_checklist_items.reported_state IS 'Condição informada no registro do evento pelo colaborador.';
COMMENT ON COLUMN nh_event_checklist_items.analysis_state IS 'Classificação posterior da análise: relacionado ao sinistro, preexistente, sem dano ou não aplicável.';
