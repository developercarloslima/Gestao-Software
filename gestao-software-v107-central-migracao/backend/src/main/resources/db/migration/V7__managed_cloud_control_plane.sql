-- Gestão Cloud v104: migração ADITIVA, sem tocar no histórico da NH ou nos bancos dos tenants.
CREATE TABLE gs_cloud_domains (
 company_id UUID PRIMARY KEY REFERENCES platform_companies(id) ON DELETE CASCADE,
 hostname VARCHAR(240) NOT NULL UNIQUE,
 target_vps_id UUID NOT NULL REFERENCES platform_vps(id) ON DELETE RESTRICT,
 status VARCHAR(30) NOT NULL DEFAULT 'RESERVED',
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CONSTRAINT ck_gs_cloud_domain_status CHECK(status IN ('RESERVED','DEPLOYING','ACTIVE','ERROR')),
 CONSTRAINT ck_gs_cloud_domain_host CHECK(hostname = lower(hostname) AND position(' ' in hostname)=0)
);
CREATE INDEX ix_gs_cloud_domains_vps ON gs_cloud_domains(target_vps_id);
CREATE TABLE gs_cloud_agent_heartbeats (
 vps_id UUID PRIMARY KEY REFERENCES platform_vps(id) ON DELETE CASCADE,
 agent_version VARCHAR(40) NOT NULL,
 cpu_percent NUMERIC(5,2),
 memory_percent NUMERIC(5,2),
 disk_percent NUMERIC(5,2),
 running_tenants INTEGER NOT NULL DEFAULT 0,
 last_seen TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CONSTRAINT ck_gs_cloud_heartbeat_cpu CHECK(cpu_percent BETWEEN 0 AND 100 OR cpu_percent IS NULL),
 CONSTRAINT ck_gs_cloud_heartbeat_mem CHECK(memory_percent BETWEEN 0 AND 100 OR memory_percent IS NULL),
 CONSTRAINT ck_gs_cloud_heartbeat_disk CHECK(disk_percent BETWEEN 0 AND 100 OR disk_percent IS NULL),
 CONSTRAINT ck_gs_cloud_heartbeat_count CHECK(running_tenants BETWEEN 0 AND 1000000)
);
CREATE TABLE gs_cloud_audit (
 id UUID PRIMARY KEY,
 company_id UUID REFERENCES platform_companies(id) ON DELETE SET NULL,
 vps_id UUID REFERENCES platform_vps(id) ON DELETE SET NULL,
 action VARCHAR(60) NOT NULL,
 operator VARCHAR(120),
 message VARCHAR(400) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
