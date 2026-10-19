-- V89 - Portal do associado, financeiro empresarial e guia oficial de comissionamento 2026.
-- Migração incremental: não apaga dados existentes.

-- Associado passa a poder possuir login próprio no mesmo mecanismo de autenticação.
ALTER TABLE portal_users ADD COLUMN IF NOT EXISTS associate_id UUID REFERENCES associates(id) ON DELETE CASCADE;
CREATE UNIQUE INDEX IF NOT EXISTS ux_portal_users_associate_not_null
    ON portal_users(associate_id) WHERE associate_id IS NOT NULL;

-- Bancos que receberam as V87/V88 historicas podem conter perfis legados
-- ainda presentes e validos na constraint anterior. Nao se pode perder usuarios
-- nem invalidar seu historico so para adicionar o novo perfil ASSOCIATE.
-- Mantemos as roles oficiais e SOMENTE os valores legados que ja existem;
-- nao acrescentamos valores arbitrarios para cadastros futuros.
DO $nh_role_migration$
DECLARE
    allowed_values TEXT;
    legacy_values TEXT[];
BEGIN
    SELECT COALESCE(array_agg(DISTINCT role::TEXT), ARRAY[]::TEXT[])
      INTO legacy_values
      FROM portal_users
     WHERE role::TEXT <> ALL(ARRAY[
        'CONSULTANT','ANALYST','SUPERVISION_ANALYSIS','WORKSHOP_MANAGER',
        'TOW_DRIVER','EVENT_OPERATOR','BUYER','ASSOCIATE','ADMIN'
     ]::TEXT[]);

    IF cardinality(legacy_values) > 0 THEN
        RAISE WARNING 'NH V89: perfis legados encontrados: %. Contas mantidas sem alteracao; revisar compatibilidade da aplicacao.',
                      array_to_string(legacy_values, ', ');
    END IF;

    SELECT string_agg(quote_literal(role_name), ', ' ORDER BY role_name)
      INTO allowed_values
      FROM (
        SELECT unnest(ARRAY[
          'CONSULTANT','ANALYST','SUPERVISION_ANALYSIS','WORKSHOP_MANAGER',
          'TOW_DRIVER','EVENT_OPERATOR','BUYER','ASSOCIATE','ADMIN'
        ]::TEXT[]) AS role_name
        UNION
        SELECT DISTINCT role::TEXT AS role_name FROM portal_users
      ) roles;

    IF allowed_values IS NULL THEN
        RAISE EXCEPTION 'V89 nao conseguiu determinar a lista de perfis permitidos';
    END IF;

    ALTER TABLE portal_users DROP CONSTRAINT IF EXISTS chk_portal_user_role;
    EXECUTE format('ALTER TABLE portal_users ADD CONSTRAINT chk_portal_user_role CHECK (role IN (%s))',
                   allowed_values);
END
$nh_role_migration$;

-- Perfil ampliado do associado. CPF/CNPJ permanece imutável no autoatendimento.
ALTER TABLE associates ADD COLUMN IF NOT EXISTS birth_date DATE;
ALTER TABLE associates ADD COLUMN IF NOT EXISTS secondary_phone VARCHAR(30);
ALTER TABLE associates ADD COLUMN IF NOT EXISTS postal_code VARCHAR(12);
ALTER TABLE associates ADD COLUMN IF NOT EXISTS address_line VARCHAR(220);
ALTER TABLE associates ADD COLUMN IF NOT EXISTS address_number VARCHAR(30);
ALTER TABLE associates ADD COLUMN IF NOT EXISTS address_complement VARCHAR(120);
ALTER TABLE associates ADD COLUMN IF NOT EXISTS neighborhood VARCHAR(120);
ALTER TABLE associates ADD COLUMN IF NOT EXISTS city VARCHAR(120);
ALTER TABLE associates ADD COLUMN IF NOT EXISTS state VARCHAR(2);

-- Indicação de consultores para o bônus de 3% da base dos indicados.
ALTER TABLE consultants ADD COLUMN IF NOT EXISTS referrer_consultant_id UUID REFERENCES consultants(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_consultants_referrer ON consultants(referrer_consultant_id);

-- Tipos adicionais de cobrança/saque.
ALTER TABLE financial_charges DROP CONSTRAINT IF EXISTS ck_financial_charges_billing_type;
ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charges_billing_type
    CHECK (billing_type IN ('CASH_HAND','BOLETO','PIX','CREDIT_CARD'));
ALTER TABLE commercial_vehicles DROP CONSTRAINT IF EXISTS ck_commercial_vehicle_recurring_type;
ALTER TABLE commercial_vehicles ADD CONSTRAINT ck_commercial_vehicle_recurring_type
    CHECK (recurring_billing_type IN ('BOLETO','PIX','CREDIT_CARD'));

ALTER TABLE consultant_withdrawals ADD COLUMN IF NOT EXISTS payout_method VARCHAR(20) NOT NULL DEFAULT 'PIX';
ALTER TABLE consultant_withdrawals ADD COLUMN IF NOT EXISTS bank_details TEXT;
ALTER TABLE consultant_withdrawals ADD COLUMN IF NOT EXISTS finance_note VARCHAR(700);
ALTER TABLE consultant_withdrawals DROP CONSTRAINT IF EXISTS ck_consultant_withdrawal_payout_method;
ALTER TABLE consultant_withdrawals ADD CONSTRAINT ck_consultant_withdrawal_payout_method
    CHECK (payout_method IN ('PIX','TED'));

-- Regras do guia de comissionamento. Podem ser editadas pelo Financeiro/Admin sem alterar código.
CREATE TABLE IF NOT EXISTS consultant_commission_rules (
    rule_key VARCHAR(80) PRIMARY KEY,
    label VARCHAR(220) NOT NULL,
    numeric_value NUMERIC(18,4) NOT NULL DEFAULT 0,
    unit VARCHAR(30) NOT NULL DEFAULT 'BRL',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by VARCHAR(160),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO consultant_commission_rules(rule_key,label,numeric_value,unit) VALUES
('LADDER_10','10 boletos baixados',600,'BRL'),
('LADDER_20','20 boletos baixados',1200,'BRL'),
('LADDER_30','30 boletos baixados',2000,'BRL'),
('LADDER_40','40 boletos baixados',2500,'BRL'),
('LADDER_50','50 boletos baixados',3000,'BRL'),
('RATE_10_20','Percentual da base: 10 a 20 placas novas',5,'PERCENT'),
('RATE_21_30','Percentual da base: 21 a 30 placas novas',5.5,'PERCENT'),
('RATE_31_40','Percentual da base: 31 a 40 placas novas',6,'PERCENT'),
('RATE_41_60','Percentual da base: 41 a 60 placas novas',7,'PERCENT'),
('RATE_61_PLUS','Percentual da base: 61 ou mais placas novas',8,'PERCENT'),
('SALE_MOTORCYCLE','Venda baixada - motocicleta',30,'BRL'),
('SALE_LIGHT','Venda baixada - veículo leve',50,'BRL'),
('SALE_UTILITY_TRUCK','Venda baixada - utilitário/caminhão',70,'BRL'),
('REFERRAL_RATE','Percentual sobre base ativa dos consultores indicados',3,'PERCENT'),
('FUEL_SILVER','Auxílio combustível Prata',200,'BRL'),
('FUEL_GOLD','Auxílio combustível Ouro',300,'BRL'),
('FUEL_DIAMOND','Auxílio combustível Diamante',400,'BRL'),
('RANK_SALES_1','1º lugar vendas baixadas',700,'BRL'),
('RANK_SALES_2','2º lugar vendas baixadas',350,'BRL'),
('RANK_SALES_3','3º lugar vendas baixadas',250,'BRL'),
('RANK_SALES_4','4º lugar vendas baixadas',200,'BRL'),
('RANK_SALES_5','5º lugar vendas baixadas',150,'BRL'),
('RANK_DEFAULT_1','1º lugar adimplência >= 80%',500,'BRL'),
('RANK_DEFAULT_2','2º lugar adimplência >= 80%',200,'BRL'),
('RANK_DEFAULT_3','3º lugar adimplência >= 80%',150,'BRL'),
('RANK_TICKET_1','1º lugar ticket médio >= R$90',500,'BRL'),
('RANK_TICKET_2','2º lugar ticket médio >= R$90',200,'BRL'),
('RANK_TICKET_3','3º lugar ticket médio >= R$90',150,'BRL')
ON CONFLICT (rule_key) DO NOTHING;

-- Documentos do financeiro: NF do consultor, comprovante de pagamento e holerites.
CREATE TABLE IF NOT EXISTS finance_documents (
    id UUID PRIMARY KEY,
    document_type VARCHAR(30) NOT NULL,
    consultant_id UUID REFERENCES consultants(id) ON DELETE SET NULL,
    collaborator_id UUID REFERENCES consultants(id) ON DELETE SET NULL,
    collaborator_name VARCHAR(180),
    collaborator_reference VARCHAR(120),
    withdrawal_id UUID REFERENCES consultant_withdrawals(id) ON DELETE SET NULL,
    reference_month DATE,
    title VARCHAR(220) NOT NULL,
    notes TEXT,
    file_name VARCHAR(260) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    file_data BYTEA NOT NULL,
    uploaded_by VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_finance_documents_type_month ON finance_documents(document_type, reference_month DESC);
CREATE INDEX IF NOT EXISTS idx_finance_documents_consultant ON finance_documents(consultant_id, created_at DESC);
ALTER TABLE finance_documents DROP CONSTRAINT IF EXISTS ck_finance_document_type;
ALTER TABLE finance_documents ADD CONSTRAINT ck_finance_document_type
    CHECK (document_type IN ('CONSULTANT_INVOICE','CONSULTANT_PAYMENT_RECEIPT','PAYSLIP','SUPPLIER_INVOICE','TAX_DOCUMENT','ISSUED_INVOICE','OTHER'));

-- Livro financeiro central: contas, receitas, despesas, impostos, folha e ajustes de conciliação.
CREATE TABLE IF NOT EXISTS finance_ledger_entries (
    id UUID PRIMARY KEY,
    entry_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    category VARCHAR(120),
    counterparty VARCHAR(180),
    description VARCHAR(600) NOT NULL,
    amount NUMERIC(16,2) NOT NULL,
    due_date DATE,
    effective_date DATE,
    payment_method VARCHAR(60),
    external_reference VARCHAR(180),
    department VARCHAR(120),
    cost_nature VARCHAR(20),
    reconciled BOOLEAN NOT NULL DEFAULT FALSE,
    reconciliation_note VARCHAR(600),
    created_by VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_finance_ledger_type_date ON finance_ledger_entries(entry_type, effective_date DESC, due_date DESC);
CREATE INDEX IF NOT EXISTS idx_finance_ledger_status_due ON finance_ledger_entries(status, due_date);
ALTER TABLE finance_ledger_entries DROP CONSTRAINT IF EXISTS ck_finance_ledger_type;
ALTER TABLE finance_ledger_entries ADD CONSTRAINT ck_finance_ledger_type
    CHECK (entry_type IN ('REVENUE','EXPENSE','PAYABLE','RECEIVABLE','TAX','PAYROLL','BANK_ADJUSTMENT'));
ALTER TABLE finance_ledger_entries DROP CONSTRAINT IF EXISTS ck_finance_ledger_status;
ALTER TABLE finance_ledger_entries ADD CONSTRAINT ck_finance_ledger_status
    CHECK (status IN ('PENDING','PAID','RECEIVED','OVERDUE','CANCELLED'));
ALTER TABLE finance_ledger_entries DROP CONSTRAINT IF EXISTS ck_finance_ledger_amount;
ALTER TABLE finance_ledger_entries ADD CONSTRAINT ck_finance_ledger_amount CHECK (amount >= 0);

ALTER TABLE finance_ledger_entries DROP CONSTRAINT IF EXISTS ck_finance_ledger_cost_nature;
ALTER TABLE finance_ledger_entries ADD CONSTRAINT ck_finance_ledger_cost_nature
    CHECK (cost_nature IS NULL OR cost_nature IN ('FIXED','VARIABLE','CAPEX'));

CREATE TABLE IF NOT EXISTS finance_settings (
    setting_key VARCHAR(80) PRIMARY KEY,
    numeric_value NUMERIC(18,2),
    text_value VARCHAR(500),
    updated_by VARCHAR(160),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO finance_settings(setting_key,numeric_value,text_value) VALUES
('INVESTED_CAPITAL',0,'Capital investido usado no cálculo gerencial de ROI'),
('MINIMUM_CASH_RESERVE',0,'Reserva mínima de caixa desejada')
ON CONFLICT (setting_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS finance_suppliers (
    id UUID PRIMARY KEY,
    name VARCHAR(180) NOT NULL,
    document VARCHAR(20),
    contact VARCHAR(180),
    payment_terms VARCHAR(500),
    notes TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS finance_corporate_cards (
    id UUID PRIMARY KEY,
    label VARCHAR(120) NOT NULL,
    holder VARCHAR(160),
    last4 VARCHAR(4),
    credit_limit NUMERIC(16,2) NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS finance_budgets (
    id UUID PRIMARY KEY,
    department VARCHAR(120) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    planned_amount NUMERIC(16,2) NOT NULL DEFAULT 0,
    notes TEXT,
    created_by VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_finance_budgets_period ON finance_budgets(period_start, period_end, department);

-- Adesão é 100% do consultor conforme o guia; mantida configurável.
INSERT INTO consultant_commission_rules(rule_key,label,numeric_value,unit) VALUES
('ADHESION_RATE','Percentual da adesão destinado ao consultor',100,'PERCENT')
ON CONFLICT (rule_key) DO NOTHING;

-- Carreira e premiações de base ativa do Guia de Comissionamento 2026.
CREATE TABLE IF NOT EXISTS consultant_career_levels (
    level_code VARCHAR(30) PRIMARY KEY,
    label VARCHAR(80) NOT NULL,
    min_active INTEGER NOT NULL,
    max_active INTEGER,
    fuel_allowance NUMERIC(14,2) NOT NULL DEFAULT 0,
    benefits TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO consultant_career_levels(level_code,label,min_active,max_active,fuel_allowance,benefits) VALUES
('BRONZE','Bronze',1,400,0,'Camisa gola simples + crachá bronze; com 200 placas ativas, camisa gola polo.'),
('PRATA','Prata',401,800,200,'Camisa gola polo + crachá prateado; ao atingir 500 placas, escritório na região de atuação.'),
('OURO','Ouro',801,1600,300,'Camisa social azul + crachá dourado; escritório regional e moto da empresa.'),
('DIAMANTE','Diamante',1601,NULL,400,'Camisa social preta + crachá diamante; escritório regional e carro da empresa.')
ON CONFLICT (level_code) DO NOTHING;

CREATE TABLE IF NOT EXISTS consultant_base_prizes (
    active_threshold INTEGER PRIMARY KEY,
    reward_description VARCHAR(300) NOT NULL,
    cash_value NUMERIC(16,2),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO consultant_base_prizes(active_threshold,reward_description,cash_value) VALUES
(500,'1 tablet ou celular Android',NULL),
(1500,'1 iPhone 15 de 256 GB',NULL),
(2500,'1 Honda Titan 160cc 0 km',NULL),
(4000,'1 Fiat Mobi 0 km',NULL),
(6000,'R$ 60.000,00 para trocar de veículo',60000),
(8000,'R$ 150.000,00 no Pix',150000),
(10000,'R$ 200.000,00 no Pix',200000)
ON CONFLICT (active_threshold) DO NOTHING;
