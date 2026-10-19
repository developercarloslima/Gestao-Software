-- v105: Reserva conservadora, reversível e auditável da capacidade lógica do servidor.
-- Sem alterações nas tabelas de tenant e sem apagar registros anteriores.
CREATE TABLE gs_cloud_capacity_reservations (
 company_id UUID PRIMARY KEY REFERENCES platform_companies(id) ON DELETE CASCADE,
 vps_id UUID NOT NULL REFERENCES platform_vps(id) ON DELETE RESTRICT,
 cpu_vcpu NUMERIC(10,2) NOT NULL CHECK(cpu_vcpu > 0),
 ram_gb NUMERIC(12,2) NOT NULL CHECK(ram_gb > 0),
 storage_gb NUMERIC(14,2) NOT NULL CHECK(storage_gb > 0),
 status VARCHAR(24) NOT NULL DEFAULT 'RESERVED',
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CONSTRAINT ck_gs_cloud_capacity_status CHECK(status IN ('RESERVED','ACTIVE','HELD_FAILED'))
);
CREATE INDEX ix_gs_cloud_reservations_vps ON gs_cloud_capacity_reservations(vps_id);
-- Dados de clientes já existentes não são inventados; reconciliar antes de reservar novos recursos.
-- DNS opcional com Cloudflare; exige aceitação explícita e pagamento confirmado.
CREATE TABLE gs_cloud_dns_records (
 company_id UUID PRIMARY KEY REFERENCES platform_companies(id) ON DELETE CASCADE,
 vps_id UUID NOT NULL REFERENCES platform_vps(id) ON DELETE RESTRICT,
 hostname VARCHAR(240) NOT NULL UNIQUE,
 ip_address VARCHAR(80) NOT NULL,
 provider VARCHAR(30) NOT NULL DEFAULT 'CLOUDFLARE',
 record_id VARCHAR(180),
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CONSTRAINT ck_gs_cloud_dns_status CHECK(status IN ('PENDING','READY','ERROR'))
);
