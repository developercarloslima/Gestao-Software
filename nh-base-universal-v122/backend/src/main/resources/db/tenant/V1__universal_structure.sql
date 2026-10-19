-- Universal tenant schema, built only from DDL present in historical repository.
-- No NH catalogue, account, discount, commission, employee or contract values are imported.
-- This is a new, independent Flyway history for NEW isolated databases; never point it to a NH database.



-- Structural evolution source: V1__schema.sql


CREATE TABLE vehicle_categories (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE plans (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    subtitle VARCHAR(180),
    category_id BIGINT NOT NULL REFERENCES vehicle_categories(id),
    region VARCHAR(20) NOT NULL,
    display_order INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    extra_above NUMERIC(14,2),
    extra_step NUMERIC(14,2),
    extra_increment NUMERIC(14,2),
    extra_base_price NUMERIC(14,2)
);

CREATE TABLE coverages (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(180) NOT NULL
);

CREATE TABLE plan_coverages (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    coverage_id BIGINT NOT NULL REFERENCES coverages(id),
    status VARCHAR(20) NOT NULL,
    detail VARCHAR(240),
    sort_order INTEGER NOT NULL,
    UNIQUE(plan_id, coverage_id)
);

CREATE TABLE price_ranges (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    min_value NUMERIC(14,2) NOT NULL,
    max_value NUMERIC(14,2) NOT NULL,
    monthly_price NUMERIC(14,2) NOT NULL,
    CONSTRAINT chk_price_range CHECK (max_value >= min_value),
    UNIQUE(plan_id, min_value, max_value)
);

CREATE INDEX idx_price_ranges_lookup ON price_ranges(plan_id, min_value, max_value);

CREATE TABLE quotations (
    id UUID PRIMARY KEY,
    quote_number VARCHAR(30) NOT NULL UNIQUE,
    consultant_name VARCHAR(120) NOT NULL,
    customer_name VARCHAR(120) NOT NULL,
    whatsapp VARCHAR(30),
    plate VARCHAR(10) NOT NULL,
    model VARCHAR(120) NOT NULL,
    manufacture_year INTEGER NOT NULL,
    fipe_value NUMERIC(14,2) NOT NULL,
    category_code VARCHAR(50) NOT NULL,
    region VARCHAR(20) NOT NULL,
    selected_plan_code VARCHAR(80) NOT NULL,
    selected_plan_name VARCHAR(120) NOT NULL,
    monthly_value NUMERIC(14,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ
);

CREATE INDEX idx_quotations_created_at ON quotations(created_at DESC);

CREATE INDEX idx_quotations_plate ON quotations(plate);




-- Structural evolution source: V3__optional_coverages.sql


ALTER TABLE plan_coverages
    ADD COLUMN monthly_price NUMERIC(14,2);

ALTER TABLE plan_coverages
    ADD CONSTRAINT chk_plan_coverage_monthly_price
    CHECK (monthly_price IS NULL OR monthly_price >= 0);

ALTER TABLE quotations
    ADD COLUMN base_monthly_value NUMERIC(14,2);

ALTER TABLE quotations
    ALTER COLUMN base_monthly_value SET NOT NULL;

CREATE TABLE quotation_optional_coverages (
    id BIGSERIAL PRIMARY KEY,
    quotation_id UUID NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
    coverage_code VARCHAR(80) NOT NULL,
    coverage_name VARCHAR(180) NOT NULL,
    detail VARCHAR(240),
    monthly_price NUMERIC(14,2) NOT NULL,
    CONSTRAINT chk_quote_optional_monthly_price CHECK (monthly_price >= 0),
    CONSTRAINT uk_quotation_optional_coverage UNIQUE (quotation_id, coverage_code)
);

CREATE INDEX idx_quote_optional_quotation
    ON quotation_optional_coverages(quotation_id);




-- Structural evolution source: V4__inspection_drive.sql


ALTER TABLE quotations
    ADD COLUMN drive_folder_id VARCHAR(160),
    ADD COLUMN drive_folder_url VARCHAR(500),
    ADD COLUMN drive_pdf_file_id VARCHAR(160),
    ADD COLUMN drive_pdf_url VARCHAR(500),
    ADD COLUMN inspection_completed_at TIMESTAMPTZ;

CREATE TABLE inspection_photos (
    id UUID PRIMARY KEY,
    quotation_id UUID NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
    label VARCHAR(120) NOT NULL,
    file_name VARCHAR(180) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    file_size BIGINT NOT NULL,
    sort_order INTEGER NOT NULL,
    drive_file_id VARCHAR(160) NOT NULL,
    drive_file_url VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_inspection_photo_size CHECK (file_size > 0),
    CONSTRAINT uk_inspection_photo_order UNIQUE (quotation_id, sort_order)
);

CREATE INDEX idx_inspection_photos_quotation
    ON inspection_photos(quotation_id, sort_order);




-- Structural evolution source: V5__portal_consultants.sql


CREATE TABLE consultants (
    id UUID PRIMARY KEY,
    name VARCHAR(140) NOT NULL,
    normalized_name VARCHAR(160) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    source VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE quotations ALTER COLUMN consultant_name TYPE VARCHAR(140);

ALTER TABLE quotations ADD COLUMN consultant_id UUID REFERENCES consultants(id);

ALTER TABLE quotations ADD COLUMN valid_until TIMESTAMPTZ;

ALTER TABLE quotations ALTER COLUMN valid_until SET NOT NULL;

CREATE INDEX idx_consultants_active_name ON consultants(active, name);

CREATE INDEX idx_quotations_consultant ON quotations(consultant_id);

CREATE INDEX idx_quotations_valid_until ON quotations(valid_until);




-- Structural evolution source: V6__retrato_nh.sql


CREATE TABLE inspection_requests (
    id UUID PRIMARY KEY,
    public_token VARCHAR(80) NOT NULL UNIQUE,
    request_type VARCHAR(30) NOT NULL,
    associate_name VARCHAR(140) NOT NULL,
    cpf VARCHAR(14) NOT NULL,
    whatsapp VARCHAR(30),
    plate VARCHAR(10) NOT NULL,
    consultant_id UUID NOT NULL REFERENCES consultants(id),
    consultant_name VARCHAR(140) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    drive_folder_id VARCHAR(160),
    drive_folder_url VARCHAR(500),
    report_file_id VARCHAR(160),
    report_url VARCHAR(500)
);

CREATE TABLE inspection_assets (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL REFERENCES inspection_requests(id) ON DELETE CASCADE,
    asset_type VARCHAR(20) NOT NULL,
    label VARCHAR(140) NOT NULL,
    file_name VARCHAR(220) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    sort_order INTEGER NOT NULL,
    drive_file_id VARCHAR(160) NOT NULL,
    drive_file_url VARCHAR(500)
);

CREATE INDEX idx_inspection_consultant ON inspection_requests(consultant_id);

CREATE INDEX idx_inspection_created_at ON inspection_requests(created_at DESC);

CREATE INDEX idx_inspection_plate ON inspection_requests(plate);

CREATE INDEX idx_inspection_status ON inspection_requests(status);

CREATE INDEX idx_inspection_assets_request ON inspection_assets(inspection_id, sort_order);




-- Structural evolution source: V7__catalog_audit.sql


CREATE TABLE catalog_change_audit (
    id BIGSERIAL PRIMARY KEY,
    item_type VARCHAR(30) NOT NULL,
    item_id BIGINT NOT NULL,
    description VARCHAR(260) NOT NULL,
    old_value NUMERIC(14,2),
    new_value NUMERIC(14,2),
    changed_by VARCHAR(160) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_catalog_audit_changed_at ON catalog_change_audit(changed_at DESC);




-- Structural evolution source: V8__official_values_2026.sql


ALTER TABLE plans ADD COLUMN IF NOT EXISTS tracker_required_above NUMERIC(14,2);

ALTER TABLE plans ADD COLUMN IF NOT EXISTS tracker_installation_fee NUMERIC(14,2);

ALTER TABLE plans ADD COLUMN IF NOT EXISTS tracker_monthly_fee NUMERIC(14,2);

ALTER TABLE quotations ADD COLUMN IF NOT EXISTS mandatory_monthly_fee NUMERIC(14,2) NOT NULL DEFAULT 0.00;

ALTER TABLE quotations ADD COLUMN IF NOT EXISTS one_time_fee NUMERIC(14,2) NOT NULL DEFAULT 0.00;

ALTER TABLE quotations ADD COLUMN IF NOT EXISTS mandatory_fee_description VARCHAR(240);




-- Structural evolution source: V9__quotation_zero_km.sql


ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS zero_km BOOLEAN NOT NULL DEFAULT FALSE;




-- Structural evolution source: V11__admin_catalog_management.sql


ALTER TABLE catalog_change_audit
    ADD COLUMN IF NOT EXISTS old_text VARCHAR(500),
    ADD COLUMN IF NOT EXISTS new_text VARCHAR(500);




-- Structural evolution source: V12__admin_full_management.sql


ALTER TABLE quotations
    DROP CONSTRAINT IF EXISTS quotations_consultant_id_fkey;

ALTER TABLE quotations
    ADD CONSTRAINT quotations_consultant_id_fkey
        FOREIGN KEY (consultant_id) REFERENCES consultants(id) ON DELETE SET NULL;

ALTER TABLE inspection_requests
    ALTER COLUMN consultant_id DROP NOT NULL;

ALTER TABLE inspection_requests
    DROP CONSTRAINT IF EXISTS inspection_requests_consultant_id_fkey;

ALTER TABLE inspection_requests
    ADD CONSTRAINT inspection_requests_consultant_id_fkey
        FOREIGN KEY (consultant_id) REFERENCES consultants(id) ON DELETE SET NULL;

ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS admin_note VARCHAR(1200),
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS admin_note VARCHAR(1200),
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS app_settings (
    setting_key VARCHAR(80) PRIMARY KEY,
    setting_value VARCHAR(500),
    updated_by VARCHAR(160),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE catalog_change_audit
    ALTER COLUMN item_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS item_key VARCHAR(120),
    ALTER COLUMN old_text TYPE VARCHAR(2000),
    ALTER COLUMN new_text TYPE VARCHAR(2000);

CREATE INDEX IF NOT EXISTS idx_catalog_audit_type_time
    ON catalog_change_audit(item_type, changed_at DESC);




-- Structural evolution source: V13__quotation_coverage_snapshots.sql


CREATE TABLE quotation_coverage_snapshots (
    id BIGSERIAL PRIMARY KEY,
    quotation_id UUID NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
    coverage_code VARCHAR(80) NOT NULL,
    coverage_name VARCHAR(180) NOT NULL,
    coverage_status VARCHAR(20) NOT NULL,
    detail VARCHAR(240),
    monthly_price NUMERIC(14,2),
    sort_order INTEGER NOT NULL,
    CONSTRAINT uk_quote_coverage_snapshot UNIQUE (quotation_id, coverage_code)
);

CREATE INDEX idx_quote_coverage_snapshot_order
    ON quotation_coverage_snapshots(quotation_id, sort_order, id);




-- Structural evolution source: V14__public_self_service_quotes.sql


ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS customer_cpf VARCHAR(11),
    ADD COLUMN IF NOT EXISTS origin VARCHAR(30) NOT NULL DEFAULT 'CONSULTANT';

ALTER TABLE quotations
    DROP CONSTRAINT IF EXISTS chk_quotation_origin;

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_origin
        CHECK (origin IN ('CONSULTANT', 'SELF_SERVICE'));

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS quotation_id UUID;

ALTER TABLE inspection_requests
    DROP CONSTRAINT IF EXISTS inspection_requests_quotation_id_fkey;

ALTER TABLE inspection_requests
    ADD CONSTRAINT inspection_requests_quotation_id_fkey
        FOREIGN KEY (quotation_id) REFERENCES quotations(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_inspection_request_quotation
    ON inspection_requests(quotation_id)
    WHERE quotation_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_quotations_origin_created
    ON quotations(origin, created_at DESC);




-- Structural evolution source: V15__zero_km_without_plate_and_inspection_registration.sql


ALTER TABLE quotations
    ALTER COLUMN plate DROP NOT NULL;

ALTER TABLE inspection_requests
    ALTER COLUMN plate DROP NOT NULL;

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS residence_address VARCHAR(600);




-- Structural evolution source: V16__manual_whatsapp_communication.sql


ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS completion_message_sent_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS decision_message_sent_at TIMESTAMPTZ;




-- Structural evolution source: V17__inspection_vehicle_type_and_guided_capture.sql


ALTER TABLE inspection_requests
    ADD COLUMN vehicle_type VARCHAR(30) NOT NULL DEFAULT 'FOUR_WHEELS_OR_MORE';

ALTER TABLE inspection_requests
    ALTER COLUMN vehicle_type DROP DEFAULT;




-- Structural evolution source: V18__assign_public_quotes_to_last_logged_consultant.sql


ALTER TABLE consultants
    ADD COLUMN IF NOT EXISTS last_portal_login_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_consultants_last_portal_login
    ON consultants(active, last_portal_login_at DESC)
    WHERE last_portal_login_at IS NOT NULL;




-- Structural evolution source: V19__national_plans_and_motorcycle_origin.sql


ALTER TABLE plans ADD COLUMN motorcycle_origin VARCHAR(20);

ALTER TABLE quotations ADD COLUMN motorcycle_origin VARCHAR(20);

ALTER TABLE plans
    ADD CONSTRAINT chk_plans_national_region
    CHECK (region = 'NATIONAL');

ALTER TABLE plans
    ADD CONSTRAINT chk_plans_motorcycle_origin
    CHECK (motorcycle_origin IS NULL OR motorcycle_origin IN ('NORTHEAST', 'CAPITAL'));

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotations_national_region
    CHECK (region = 'NATIONAL');

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotations_motorcycle_origin
    CHECK (motorcycle_origin IS NULL OR motorcycle_origin IN ('NORTHEAST', 'CAPITAL'));

CREATE INDEX idx_plans_category_origin_active
    ON plans(category_id, motorcycle_origin, active, display_order);




-- Structural evolution source: V21__inspection_asset_unique_order.sql


CREATE UNIQUE INDEX IF NOT EXISTS uq_inspection_asset_type_order
    ON inspection_assets(inspection_id, asset_type, sort_order);




-- Structural evolution source: V24__inspection_assets_in_postgresql.sql


ALTER TABLE inspection_assets
    ALTER COLUMN drive_file_id DROP NOT NULL;

ALTER TABLE inspection_assets
    ADD COLUMN IF NOT EXISTS storage_kind VARCHAR(20) NOT NULL DEFAULT 'DRIVE',
    ADD COLUMN IF NOT EXISTS stored_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS purged_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS inspection_asset_contents (
    asset_id UUID PRIMARY KEY REFERENCES inspection_assets(id) ON DELETE CASCADE,
    file_data BYTEA NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_inspection_assets_expiration
    ON inspection_assets(expires_at)
    WHERE storage_kind = 'DATABASE' AND purged_at IS NULL;




-- Structural evolution source: V25__database_asset_storage_defaults.sql


ALTER TABLE inspection_assets
    ALTER COLUMN storage_kind SET DEFAULT 'DATABASE';




-- Structural evolution source: V26__repair_consultant_activity_ownership.sql


CREATE INDEX IF NOT EXISTS idx_quotations_consultant_name_lower
    ON quotations(lower(consultant_name));

CREATE INDEX IF NOT EXISTS idx_inspection_requests_consultant_name_lower
    ON inspection_requests(lower(consultant_name));




-- Structural evolution source: V27__inspection_binary_chunks_in_postgresql.sql


CREATE TABLE IF NOT EXISTS inspection_asset_blobs (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL REFERENCES inspection_requests(id) ON DELETE CASCADE,
    asset_id UUID UNIQUE REFERENCES inspection_assets(id) ON DELETE CASCADE,
    asset_type VARCHAR(20) NOT NULL,
    sort_order INTEGER NOT NULL,
    upload_id VARCHAR(140) NOT NULL,
    label VARCHAR(140) NOT NULL,
    file_name VARCHAR(220) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    total_size BIGINT NOT NULL CHECK (total_size > 0),
    total_chunks INTEGER NOT NULL CHECK (total_chunks BETWEEN 1 AND 512),
    status VARCHAR(20) NOT NULL CHECK (status IN ('UPLOADING', 'COMPLETE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_inspection_asset_blob_slot UNIQUE (inspection_id, asset_type, sort_order),
    CONSTRAINT chk_inspection_asset_blob_completion CHECK (
        (status = 'UPLOADING' AND asset_id IS NULL AND completed_at IS NULL)
        OR
        (status = 'COMPLETE' AND asset_id IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS inspection_asset_blob_chunks (
    blob_id UUID NOT NULL REFERENCES inspection_asset_blobs(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    chunk_size INTEGER NOT NULL CHECK (chunk_size > 0),
    chunk_sha256 VARCHAR(64) NOT NULL,
    chunk_data BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blob_id, chunk_index),
    CONSTRAINT chk_inspection_asset_blob_chunk_size
        CHECK (octet_length(chunk_data) = chunk_size)
);

CREATE INDEX IF NOT EXISTS idx_inspection_asset_blobs_asset
    ON inspection_asset_blobs(asset_id)
    WHERE asset_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_inspection_asset_blobs_cleanup
    ON inspection_asset_blobs(updated_at)
    WHERE status = 'UPLOADING';




-- Structural evolution source: V28__portal_users.sql


CREATE TABLE IF NOT EXISTS portal_users (
    id UUID PRIMARY KEY,
    username VARCHAR(160) NOT NULL,
    normalized_username VARCHAR(160) NOT NULL UNIQUE,
    display_name VARCHAR(160),
    password_hash VARCHAR(120) NOT NULL,
    role VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    password_changed_at TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ,
    created_by VARCHAR(160),
    CONSTRAINT chk_portal_user_role CHECK (role IN ('CONSULTANT', 'ANALYST', 'ADMIN'))
);

CREATE INDEX IF NOT EXISTS idx_portal_users_active_role
    ON portal_users(active, role, normalized_username);




-- Structural evolution source: V29__discounts_billing_plan_and_electric_scooters.sql


ALTER TABLE quotations
    ADD COLUMN pre_discount_monthly_value NUMERIC(14,2);

ALTER TABLE quotations
    ALTER COLUMN pre_discount_monthly_value SET NOT NULL;

ALTER TABLE quotations
    ADD COLUMN discount_percent INTEGER NOT NULL DEFAULT 0;

ALTER TABLE quotations
    ADD COLUMN rear_window_branding VARCHAR(40) NOT NULL DEFAULT 'NOT_APPLICABLE';

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_discount_percent
    CHECK (discount_percent IN (0, 5, 10, 15, 30));

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_rear_window_branding
    CHECK (rear_window_branding IN ('NOT_APPLICABLE', 'NH_AND_OTHER_COMPANY', 'NH_ONLY'));

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_discount_branding
    CHECK (
        (discount_percent = 15 AND rear_window_branding = 'NH_AND_OTHER_COMPANY')
        OR (discount_percent = 30 AND rear_window_branding = 'NH_ONLY')
        OR (discount_percent IN (0, 5, 10) AND rear_window_branding = 'NOT_APPLICABLE')
    );

ALTER TABLE inspection_requests
    ADD COLUMN contracted_plan VARCHAR(160);




-- Structural evolution source: V30__scooter_fipe_and_strict_new_inspection.sql


CREATE OR REPLACE FUNCTION nh_enforce_new_inspection_accepted_quote()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.request_type = 'NEW_INSPECTION' THEN
        IF NEW.quotation_id IS NULL THEN
            RAISE EXCEPTION 'Nova vistoria exige cotação aceita vinculada.'
                USING ERRCODE = '23514';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM quotations q
            WHERE q.id = NEW.quotation_id
              AND q.status = 'ACCEPTED'
        ) THEN
            RAISE EXCEPTION 'Nova vistoria só pode ser criada para cotação com status ACCEPTED.'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_inspection_requires_accepted_quote ON inspection_requests;

CREATE TRIGGER trg_inspection_requires_accepted_quote
BEFORE INSERT OR UPDATE OF request_type, quotation_id
ON inspection_requests
FOR EACH ROW
EXECUTE FUNCTION nh_enforce_new_inspection_accepted_quote();




-- Structural evolution source: V31__quote_observation_motorcycle_promo_and_identity_sides.sql


ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS observation VARCHAR(1200),
    ADD COLUMN IF NOT EXISTS motorcycle_cc INTEGER;

ALTER TABLE quotations
    DROP CONSTRAINT IF EXISTS chk_quotations_motorcycle_cc;

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotations_motorcycle_cc
    CHECK (motorcycle_cc IS NULL OR motorcycle_cc BETWEEN 1 AND 2500);




-- Structural evolution source: V32__quote_validity_and_expired_inspection_guard.sql


CREATE OR REPLACE FUNCTION nh_enforce_new_inspection_accepted_quote()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.request_type = 'NEW_INSPECTION' THEN
        IF NEW.quotation_id IS NULL THEN
            RAISE EXCEPTION 'Nova vistoria exige cotação aceita vinculada.'
                USING ERRCODE = '23514';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM quotations q
            WHERE q.id = NEW.quotation_id
              AND q.status = 'ACCEPTED'
              AND q.valid_until >= NOW()
        ) THEN
            RAISE EXCEPTION 'Nova vistoria exige cotação ACCEPTED e dentro do prazo de validade.'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;




-- Structural evolution source: V33__quotation_billing_due_date.sql


ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS billing_due_day INTEGER,
    ADD COLUMN IF NOT EXISTS first_billing_due_date DATE;

ALTER TABLE quotations
    DROP CONSTRAINT IF EXISTS ck_quotations_billing_due_day;

ALTER TABLE quotations
    ADD CONSTRAINT ck_quotations_billing_due_day
    CHECK (billing_due_day IS NULL OR billing_due_day IN (5, 10, 15, 20, 25, 30));

ALTER TABLE quotations
    DROP CONSTRAINT IF EXISTS ck_quotations_billing_due_pair;

ALTER TABLE quotations
    ADD CONSTRAINT ck_quotations_billing_due_pair
    CHECK (
        (billing_due_day IS NULL AND first_billing_due_date IS NULL)
        OR
        (billing_due_day IS NOT NULL AND first_billing_due_date IS NOT NULL)
    );




-- Structural evolution source: V34__promotional_motorcycle_prices.sql


CREATE TABLE promotional_motorcycle_prices (
    id BIGSERIAL PRIMARY KEY,
    tier_code VARCHAR(40) NOT NULL UNIQUE,
    label VARCHAR(120) NOT NULL,
    min_cc INTEGER NOT NULL,
    max_cc INTEGER NOT NULL,
    max_fipe NUMERIC(14,2),
    monthly_price NUMERIC(14,2) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 100,
    CONSTRAINT chk_promotional_motorcycle_cc CHECK (min_cc > 0 AND max_cc >= min_cc),
    CONSTRAINT chk_promotional_motorcycle_max_fipe CHECK (max_fipe IS NULL OR max_fipe > 0),
    CONSTRAINT chk_promotional_motorcycle_monthly_price CHECK (monthly_price >= 0)
);




-- Structural evolution source: V35__editable_promotional_motorcycle_conditions.sql


ALTER TABLE promotional_motorcycle_prices
    ADD COLUMN min_fipe NUMERIC(14,2) NOT NULL DEFAULT 0.00;

ALTER TABLE promotional_motorcycle_prices
    ADD CONSTRAINT chk_promotional_motorcycle_min_fipe CHECK (min_fipe >= 0),
    ADD CONSTRAINT chk_promotional_motorcycle_fipe_range CHECK (max_fipe IS NULL OR max_fipe >= min_fipe);




-- Structural evolution source: V36__vehicle_category_activation.sql


ALTER TABLE vehicle_categories
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_vehicle_categories_active ON vehicle_categories(active);




-- Structural evolution source: V37__site_documents.sql


CREATE TABLE IF NOT EXISTS site_documents (
    document_key VARCHAR(80) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    file_data BYTEA NOT NULL,
    updated_by VARCHAR(160) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);




-- Structural evolution source: V38__portal_user_consultant_link.sql


ALTER TABLE portal_users
    ADD COLUMN IF NOT EXISTS consultant_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_portal_users_consultant'
    ) THEN
        ALTER TABLE portal_users
            ADD CONSTRAINT fk_portal_users_consultant
            FOREIGN KEY (consultant_id)
            REFERENCES consultants(id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_portal_users_consultant_id
    ON portal_users(consultant_id)
    WHERE consultant_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_portal_users_consultant_id
    ON portal_users(consultant_id);




-- Structural evolution source: V39__collaborators_and_inspection_reviewer.sql


ALTER TABLE consultants
    ADD COLUMN IF NOT EXISTS collaborator_role VARCHAR(20);

ALTER TABLE consultants
    ALTER COLUMN collaborator_role SET DEFAULT 'CONSULTANT',
    ALTER COLUMN collaborator_role SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_consultants_collaborator_role'
    ) THEN
        ALTER TABLE consultants
            ADD CONSTRAINT ck_consultants_collaborator_role
            CHECK (collaborator_role IN ('CONSULTANT', 'ANALYST'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_consultants_active_role_name
    ON consultants(active, collaborator_role, name);

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS reviewed_by_collaborator_id UUID,
    ADD COLUMN IF NOT EXISTS reviewed_by_name VARCHAR(160),
    ADD COLUMN IF NOT EXISTS reviewed_by_role VARCHAR(20);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_inspection_reviewed_by_collaborator'
    ) THEN
        ALTER TABLE inspection_requests
            ADD CONSTRAINT fk_inspection_reviewed_by_collaborator
            FOREIGN KEY (reviewed_by_collaborator_id)
            REFERENCES consultants(id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_inspection_reviewed_by_collaborator
    ON inspection_requests(reviewed_by_collaborator_id);




-- Structural evolution source: V40__consultant_whatsapp.sql


ALTER TABLE consultants
    ADD COLUMN IF NOT EXISTS whatsapp VARCHAR(30);




-- Structural evolution source: V41__coverage_rules_and_vehicle_history.sql


CREATE TABLE coverage_rules (
    id BIGSERIAL PRIMARY KEY,
    coverage_id BIGINT NOT NULL REFERENCES coverages(id) ON DELETE CASCADE,
    category_code VARCHAR(50),
    min_fipe NUMERIC(14,2) NOT NULL DEFAULT 0,
    max_fipe NUMERIC(14,2),
    normal_amount NUMERIC(14,2) NOT NULL,
    discounted_amount NUMERIC(14,2),
    sort_order INTEGER NOT NULL DEFAULT 100,
    CONSTRAINT chk_coverage_rule_min_fipe CHECK (min_fipe >= 0),
    CONSTRAINT chk_coverage_rule_max_fipe CHECK (max_fipe IS NULL OR max_fipe >= min_fipe),
    CONSTRAINT chk_coverage_rule_normal_amount CHECK (normal_amount >= 0),
    CONSTRAINT chk_coverage_rule_discounted_amount CHECK (discounted_amount IS NULL OR discounted_amount >= 0),
    CONSTRAINT chk_coverage_rule_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX idx_coverage_rules_coverage ON coverage_rules(coverage_id, sort_order, id);

CREATE INDEX idx_coverage_rules_context ON coverage_rules(category_code, min_fipe, max_fipe);

ALTER TABLE quotations
    ADD COLUMN auction_or_chassis_remarked BOOLEAN,
    ADD COLUMN indemnity_fipe_percent INTEGER NOT NULL DEFAULT 100;

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotations_indemnity_fipe_percent
    CHECK (indemnity_fipe_percent BETWEEN 1 AND 100);




-- Structural evolution source: V42__analysis_team_workflow.sql


ALTER TABLE consultants
    ADD COLUMN IF NOT EXISTS city VARCHAR(120),
    ADD COLUMN IF NOT EXISTS assigned_analyst_id UUID;

ALTER TABLE consultants DROP CONSTRAINT IF EXISTS ck_consultants_collaborator_role;

ALTER TABLE consultants
    ADD CONSTRAINT ck_consultants_collaborator_role
    CHECK (collaborator_role IN ('CONSULTANT', 'ANALYST', 'SUPERVISION_ANALYSIS'));

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_consultant_assigned_analyst') THEN
        ALTER TABLE consultants
            ADD CONSTRAINT fk_consultant_assigned_analyst
            FOREIGN KEY (assigned_analyst_id) REFERENCES consultants(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_consultants_assigned_analyst
    ON consultants(assigned_analyst_id, active);

ALTER TABLE portal_users
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE portal_users DROP CONSTRAINT IF EXISTS chk_portal_user_role;

ALTER TABLE portal_users
    ADD CONSTRAINT chk_portal_user_role
    CHECK (role IN ('CONSULTANT', 'ANALYST', 'SUPERVISION_ANALYSIS', 'ADMIN'));

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS assigned_analyst_id UUID,
    ADD COLUMN IF NOT EXISTS assigned_analyst_name VARCHAR(160),
    ADD COLUMN IF NOT EXISTS analysis_stage VARCHAR(30),
    ADD COLUMN IF NOT EXISTS registration_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS registration_completed_by_name VARCHAR(160);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_inspection_assigned_analyst') THEN
        ALTER TABLE inspection_requests
            ADD CONSTRAINT fk_inspection_assigned_analyst
            FOREIGN KEY (assigned_analyst_id) REFERENCES consultants(id) ON DELETE SET NULL;
    END IF;
END $$;

ALTER TABLE inspection_requests
    ALTER COLUMN analysis_stage SET DEFAULT 'ANALYST_QUEUE',
    ALTER COLUMN analysis_stage SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_inspection_analysis_stage') THEN
        ALTER TABLE inspection_requests
            ADD CONSTRAINT ck_inspection_analysis_stage
            CHECK (analysis_stage IN ('ANALYST_QUEUE', 'ANALYST_PENDING', 'SUPERVISION_QUEUE', 'FINISHED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_inspection_analysis_routing
    ON inspection_requests(analysis_stage, assigned_analyst_id, created_at DESC);




-- Structural evolution source: V44__inspection_webauthn_digital_acceptance.sql


alter table inspection_requests
    add column if not exists webauthn_registration_challenge varchar(160),
    add column if not exists webauthn_registration_expires_at timestamptz,
    add column if not exists webauthn_origin varchar(320),
    add column if not exists webauthn_rp_id varchar(253),
    add column if not exists webauthn_credential_id varchar(1024),
    add column if not exists webauthn_public_key bytea,
    add column if not exists webauthn_algorithm integer,
    add column if not exists webauthn_sign_count bigint,
    add column if not exists webauthn_assertion_challenge varchar(160),
    add column if not exists webauthn_assertion_expires_at timestamptz,
    add column if not exists acceptance_evidence_hash varchar(64),
    add column if not exists acceptance_selfie_sha256 varchar(64),
    add column if not exists acceptance_dossier_sha256 varchar(64),
    add column if not exists acceptance_device_metadata text,
    add column if not exists acceptance_ip varchar(80),
    add column if not exists acceptance_latitude double precision,
    add column if not exists acceptance_longitude double precision,
    add column if not exists acceptance_accuracy_meters double precision,
    add column if not exists acceptance_assertion_signature text,
    add column if not exists acceptance_authenticator_data text,
    add column if not exists acceptance_client_data_json text,
    add column if not exists acceptance_proof_hash varchar(64),
    add column if not exists acceptance_user_verified boolean,
    add column if not exists accepted_at timestamptz;

create index if not exists idx_inspection_requests_accepted_at on inspection_requests(accepted_at);




-- Structural evolution source: V45__supervision_pending_queue_and_notes.sql


ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS supervision_note VARCHAR(1200),
    ADD COLUMN IF NOT EXISTS supervision_note_updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS supervision_note_by_name VARCHAR(160);




-- Structural evolution source: V48__editable_associate_vehicle_data.sql


alter table inspection_requests
    add column if not exists vehicle_model varchar(120),
    add column if not exists model_year integer;




-- Structural evolution source: V49__quotation_inspection_updated_at.sql


ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

ALTER TABLE quotations
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_quotations_updated_at
    ON quotations(updated_at DESC);

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

ALTER TABLE inspection_requests
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_inspection_requests_updated_at
    ON inspection_requests(updated_at DESC);




-- Structural evolution source: V51__contract_reconfirmation_after_fipe_change.sql


ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_created_at TIMESTAMPTZ;

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_accepted_at TIMESTAMPTZ;

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_notified_at TIMESTAMPTZ;

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_keep_optionals BOOLEAN;

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_previous_fipe NUMERIC(14,2);

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_previous_monthly NUMERIC(14,2);

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_proposed_fipe NUMERIC(14,2);

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_proposed_base_monthly NUMERIC(14,2);

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_proposed_mandatory_monthly NUMERIC(14,2);

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_proposed_one_time_fee NUMERIC(14,2);

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_proposed_mandatory_description VARCHAR(240);

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_monthly_with_optionals NUMERIC(14,2);

ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_monthly_without_optionals NUMERIC(14,2);

CREATE INDEX IF NOT EXISTS idx_inspection_contract_reconfirmation_pending
    ON inspection_requests (contract_reconfirmation_required, analysis_stage)
    WHERE contract_reconfirmation_required = TRUE;




-- Structural evolution source: V52__contract_change_confirmation.sql


ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS pending_contract_fipe_value NUMERIC(14,2),
    ADD COLUMN IF NOT EXISTS pending_contract_monthly_value NUMERIC(14,2),
    ADD COLUMN IF NOT EXISTS contract_change_requested_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS contract_change_requested_by VARCHAR(160),
    ADD COLUMN IF NOT EXISTS contract_change_decided_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS contract_change_accepted BOOLEAN,
    ADD COLUMN IF NOT EXISTS contract_change_keep_optionals BOOLEAN;

CREATE INDEX IF NOT EXISTS idx_inspection_requests_contract_change_pending
    ON inspection_requests (contract_change_requested_at)
    WHERE pending_contract_fipe_value IS NOT NULL
      AND pending_contract_monthly_value IS NOT NULL;




-- Structural evolution source: V53__expand_inspection_video_chunk_capacity.sql


DO $$
DECLARE
    current_constraint TEXT;
BEGIN
    SELECT c.conname
      INTO current_constraint
      FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid
      JOIN pg_namespace n ON n.oid = t.relnamespace
     WHERE n.nspname = current_schema()
       AND t.relname = 'inspection_asset_blobs'
       AND c.contype = 'c'
       AND pg_get_constraintdef(c.oid) ILIKE '%total_chunks%'
     LIMIT 1;

    IF current_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inspection_asset_blobs DROP CONSTRAINT %I', current_constraint);
    END IF;
END
$$;

ALTER TABLE inspection_asset_blobs
    ADD CONSTRAINT chk_inspection_asset_blobs_total_chunks_v16
    CHECK (total_chunks BETWEEN 1 AND 4096);




-- Structural evolution source: V54__commercial_revision_expiration_and_benefits.sql


ALTER TABLE quotation_coverage_snapshots
    ADD COLUMN IF NOT EXISTS final_selected BOOLEAN;

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS pending_contract_discount_percent INTEGER,
    ADD COLUMN IF NOT EXISTS pending_contract_rear_window_branding VARCHAR(40),
    ADD COLUMN IF NOT EXISTS pending_contract_benefit_codes VARCHAR(4000);




-- Structural evolution source: V56__pending_contract_manual_price_source.sql


ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS pending_contract_manual_monthly_override BOOLEAN NOT NULL DEFAULT FALSE;




-- Structural evolution source: V57__expand_catalog_audit_item_type.sql


ALTER TABLE catalog_change_audit
    ALTER COLUMN item_type TYPE VARCHAR(80);




-- Structural evolution source: V58__nh_event_checklist_and_tow.sql


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




-- Structural evolution source: V59__tow_driver_portal.sql


ALTER TABLE portal_users DROP CONSTRAINT IF EXISTS chk_portal_user_role;

ALTER TABLE portal_users
    ADD CONSTRAINT chk_portal_user_role
    CHECK (role IN ('CONSULTANT', 'ANALYST', 'SUPERVISION_ANALYSIS', 'TOW_DRIVER', 'ADMIN'));

ALTER TABLE nh_event_records
    ADD COLUMN IF NOT EXISTS tow_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS tow_started_by_username VARCHAR(160),
    ADD COLUMN IF NOT EXISTS tow_started_by_name VARCHAR(180),
    ADD COLUMN IF NOT EXISTS tow_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS tow_completed_by_username VARCHAR(160),
    ADD COLUMN IF NOT EXISTS tow_completed_by_name VARCHAR(180);

CREATE INDEX IF NOT EXISTS idx_nh_event_tow_queue
    ON nh_event_records(tow_service, tow_completed_at, created_at DESC);




-- Structural evolution source: V60__occurrence_tow_workshop_and_purchases.sql


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




-- Structural evolution source: V61__workshop_attachment_context.sql


ALTER TABLE nh_event_attachments
    DROP CONSTRAINT IF EXISTS ck_nh_attachment_context;

ALTER TABLE nh_event_attachments
    ADD CONSTRAINT ck_nh_attachment_context CHECK (
        context IN (
            'EVENT',
            'DOCUMENT',
            'PRE_COLLISION',
            'TOW',
            'THIRD_PARTY',
            'WORKSHOP',
            'OTHER'
        )
    );




-- Structural evolution source: V62__tow_checklist_item_photos_and_seed.sql


ALTER TABLE nh_tow_record_photos
    ADD COLUMN IF NOT EXISTS checklist_item_id UUID REFERENCES nh_tow_record_items(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_nh_tow_photo_checklist_item
    ON nh_tow_record_photos(checklist_item_id, created_at);




-- Structural evolution source: V64__vehicle_profile_and_complete_workshop_checklists.sql


ALTER TABLE nh_event_records
    ADD COLUMN IF NOT EXISTS plan_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS plan_monthly_amount NUMERIC(14,2),
    ADD COLUMN IF NOT EXISTS participation_amount NUMERIC(14,2),
    ADD COLUMN IF NOT EXISTS coverage_notes TEXT,
    ADD COLUMN IF NOT EXISTS vehicle_brand VARCHAR(120),
    ADD COLUMN IF NOT EXISTS vehicle_version VARCHAR(180),
    ADD COLUMN IF NOT EXISTS vehicle_subtype VARCHAR(80),
    ADD COLUMN IF NOT EXISTS vehicle_fuel VARCHAR(60),
    ADD COLUMN IF NOT EXISTS vehicle_transmission VARCHAR(60),
    ADD COLUMN IF NOT EXISTS vehicle_odometer BIGINT,
    ADD COLUMN IF NOT EXISTS fipe_code VARCHAR(40),
    ADD COLUMN IF NOT EXISTS fipe_value NUMERIC(14,2);

ALTER TABLE nh_event_records DROP CONSTRAINT IF EXISTS ck_nh_event_plan_monthly_amount;

ALTER TABLE nh_event_records ADD CONSTRAINT ck_nh_event_plan_monthly_amount CHECK (plan_monthly_amount IS NULL OR plan_monthly_amount >= 0);

ALTER TABLE nh_event_records DROP CONSTRAINT IF EXISTS ck_nh_event_participation_amount;

ALTER TABLE nh_event_records ADD CONSTRAINT ck_nh_event_participation_amount CHECK (participation_amount IS NULL OR participation_amount >= 0);

ALTER TABLE nh_event_records DROP CONSTRAINT IF EXISTS ck_nh_event_vehicle_odometer;

ALTER TABLE nh_event_records ADD CONSTRAINT ck_nh_event_vehicle_odometer CHECK (vehicle_odometer IS NULL OR vehicle_odometer >= 0);

ALTER TABLE nh_event_records DROP CONSTRAINT IF EXISTS ck_nh_event_fipe_value;

ALTER TABLE nh_event_records ADD CONSTRAINT ck_nh_event_fipe_value CHECK (fipe_value IS NULL OR fipe_value >= 0);




-- Structural evolution source: V65__workshop_item_photos.sql


ALTER TABLE nh_event_attachments
    ADD COLUMN IF NOT EXISTS checklist_item_id UUID REFERENCES nh_event_checklist_items(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_nh_event_attachment_checklist_item
    ON nh_event_attachments(event_id, checklist_item_id, created_at);




-- Structural evolution source: V66__event_internal_dossier_purchase_lock_and_webauthn.sql


ALTER TABLE nh_event_purchase_items
    ADD COLUMN IF NOT EXISTS details_saved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS details_saved_by VARCHAR(180);

ALTER TABLE nh_event_records
    ADD COLUMN IF NOT EXISTS public_acceptance_token VARCHAR(160),
    ADD COLUMN IF NOT EXISTS webauthn_registration_challenge VARCHAR(160),
    ADD COLUMN IF NOT EXISTS webauthn_registration_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS webauthn_origin VARCHAR(320),
    ADD COLUMN IF NOT EXISTS webauthn_rp_id VARCHAR(253),
    ADD COLUMN IF NOT EXISTS webauthn_credential_id VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS webauthn_public_key BYTEA,
    ADD COLUMN IF NOT EXISTS webauthn_algorithm INTEGER,
    ADD COLUMN IF NOT EXISTS webauthn_sign_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS webauthn_assertion_challenge VARCHAR(160),
    ADD COLUMN IF NOT EXISTS webauthn_assertion_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS acceptance_evidence_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS acceptance_dossier_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS acceptance_device_metadata TEXT,
    ADD COLUMN IF NOT EXISTS acceptance_ip VARCHAR(80),
    ADD COLUMN IF NOT EXISTS acceptance_latitude DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS acceptance_longitude DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS acceptance_accuracy_meters DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS acceptance_assertion_signature TEXT,
    ADD COLUMN IF NOT EXISTS acceptance_authenticator_data TEXT,
    ADD COLUMN IF NOT EXISTS acceptance_client_data_json TEXT,
    ADD COLUMN IF NOT EXISTS acceptance_proof_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS acceptance_user_verified BOOLEAN,
    ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uq_nh_event_public_acceptance_token
    ON nh_event_records(public_acceptance_token)
    WHERE public_acceptance_token IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_nh_event_accepted_at
    ON nh_event_records(accepted_at);




-- Structural evolution source: V67__event_operator_buyer_and_purchase_finalization.sql


ALTER TABLE portal_users DROP CONSTRAINT IF EXISTS chk_portal_user_role;

ALTER TABLE portal_users
    ADD CONSTRAINT chk_portal_user_role
    CHECK (role IN (
        'CONSULTANT','ANALYST','SUPERVISION_ANALYSIS','WORKSHOP_MANAGER',
        'TOW_DRIVER','EVENT_OPERATOR','BUYER','ADMIN'
    ));

ALTER TABLE nh_event_purchase_items DROP CONSTRAINT IF EXISTS ck_nh_event_purchase_status;

ALTER TABLE nh_event_purchase_items
    ADD CONSTRAINT ck_nh_event_purchase_status
    CHECK (status IN ('REQUESTED','FINALIZED'));




-- Structural evolution source: V68__third_party_vehicle_and_color.sql


ALTER TABLE nh_event_third_parties
    ADD COLUMN IF NOT EXISTS vehicle_name VARCHAR(180),
    ADD COLUMN IF NOT EXISTS vehicle_color VARCHAR(80);




-- Structural evolution source: V69__portal_user_facial_validation.sql


ALTER TABLE portal_users
    ADD COLUMN IF NOT EXISTS face_enrolled_at TIMESTAMPTZ;




-- Structural evolution source: V70__portal_user_passkey_authentication.sql


alter table portal_users add column if not exists passkey_credential_id varchar(1024);

alter table portal_users add column if not exists passkey_public_key bytea;

alter table portal_users add column if not exists passkey_algorithm integer;

alter table portal_users add column if not exists passkey_sign_count bigint;

alter table portal_users add column if not exists passkey_registration_challenge varchar(160);

alter table portal_users add column if not exists passkey_registration_expires_at timestamptz;

alter table portal_users add column if not exists passkey_assertion_challenge varchar(160);

alter table portal_users add column if not exists passkey_assertion_expires_at timestamptz;

alter table portal_users add column if not exists passkey_origin varchar(320);

alter table portal_users add column if not exists passkey_rp_id varchar(253);

alter table portal_users add column if not exists passkey_enrolled_at timestamptz;




-- Structural evolution source: V72__admin_profile_multiple_passkeys.sql


CREATE TABLE IF NOT EXISTS portal_admin_passkeys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES portal_users(id) ON DELETE CASCADE,
    credential_id VARCHAR(1024) NOT NULL UNIQUE,
    public_key BYTEA NOT NULL,
    algorithm INTEGER NOT NULL,
    sign_count BIGINT NOT NULL DEFAULT 0,
    origin VARCHAR(320) NOT NULL,
    rp_id VARCHAR(253) NOT NULL,
    label VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_portal_admin_passkeys_user
    ON portal_admin_passkeys(user_id, created_at);




-- Structural evolution source: V77__inspection_acceptance_audit_context.sql


ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS acceptance_whatsapp_sent_to VARCHAR(30),
    ADD COLUMN IF NOT EXISTS acceptance_device_id VARCHAR(96),
    ADD COLUMN IF NOT EXISTS acceptance_location_captured_at TIMESTAMPTZ;




-- Structural evolution source: V78__admin_discount_rules.sql


ALTER TABLE quotations DROP CONSTRAINT IF EXISTS chk_quotation_discount_percent;

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_discount_percent
    CHECK (discount_percent BETWEEN 0 AND 100);

ALTER TABLE quotations DROP CONSTRAINT IF EXISTS chk_quotation_discount_branding;

ALTER TABLE quotations DROP CONSTRAINT IF EXISTS chk_quotation_rear_window_branding;

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_rear_window_branding
    CHECK (rear_window_branding IN ('NOT_APPLICABLE', 'ANY_PERFORATED', 'NH_AND_OTHER_COMPANY', 'NH_ONLY'));

CREATE TABLE discount_rules (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    percent INTEGER NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INTEGER NOT NULL DEFAULT 100,
    min_vehicle_age_years INTEGER,
    max_vehicle_age_years INTEGER,
    min_fipe NUMERIC(14,2),
    max_fipe NUMERIC(14,2),
    requires_rear_window_perforation BOOLEAN NOT NULL DEFAULT FALSE,
    apply_to_base BOOLEAN NOT NULL DEFAULT TRUE,
    apply_to_mandatory_fee BOOLEAN NOT NULL DEFAULT TRUE,
    apply_to_optionals BOOLEAN NOT NULL DEFAULT TRUE,
    notes VARCHAR(600),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_discount_rules_percent CHECK (percent BETWEEN 1 AND 100),
    CONSTRAINT chk_discount_rules_order CHECK (display_order >= 0),
    CONSTRAINT chk_discount_rules_min_age CHECK (min_vehicle_age_years IS NULL OR min_vehicle_age_years >= 0),
    CONSTRAINT chk_discount_rules_max_age CHECK (max_vehicle_age_years IS NULL OR max_vehicle_age_years >= 0),
    CONSTRAINT chk_discount_rules_age_range CHECK (
        min_vehicle_age_years IS NULL OR max_vehicle_age_years IS NULL OR max_vehicle_age_years >= min_vehicle_age_years
    ),
    CONSTRAINT chk_discount_rules_min_fipe CHECK (min_fipe IS NULL OR min_fipe >= 0),
    CONSTRAINT chk_discount_rules_max_fipe CHECK (max_fipe IS NULL OR max_fipe >= 0),
    CONSTRAINT chk_discount_rules_fipe_range CHECK (
        min_fipe IS NULL OR max_fipe IS NULL OR max_fipe >= min_fipe
    )
);

CREATE TABLE discount_rule_plans (
    discount_rule_id BIGINT NOT NULL REFERENCES discount_rules(id) ON DELETE CASCADE,
    plan_id BIGINT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    PRIMARY KEY (discount_rule_id, plan_id)
);

CREATE INDEX idx_discount_rule_plans_plan ON discount_rule_plans(plan_id, discount_rule_id);

CREATE TABLE discount_rule_benefits (
    id BIGSERIAL PRIMARY KEY,
    discount_rule_id BIGINT NOT NULL REFERENCES discount_rules(id) ON DELETE CASCADE,
    coverage_id BIGINT NOT NULL REFERENCES coverages(id) ON DELETE CASCADE,
    action VARCHAR(24) NOT NULL,
    detail_override VARCHAR(240),
    amount_override NUMERIC(14,2),
    CONSTRAINT uq_discount_rule_benefit UNIQUE(discount_rule_id, coverage_id),
    CONSTRAINT chk_discount_rule_benefit_action CHECK (action IN ('REMOVE', 'FORCE_INCLUDE')),
    CONSTRAINT chk_discount_rule_benefit_amount CHECK (amount_override IS NULL OR amount_override >= 0)
);

CREATE INDEX idx_discount_rule_benefits_rule ON discount_rule_benefits(discount_rule_id, coverage_id);




-- Structural evolution source: V80__preexisting_damage_term_and_discount_benefit_guard.sql


ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS preexisting_damage BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS preexisting_damage_details TEXT;




-- Structural evolution source: V81__benefit_additional_vehicle_rules.sql


ALTER TABLE coverages
    ADD COLUMN IF NOT EXISTS optional_vehicle_categories VARCHAR(1000) NOT NULL DEFAULT '';




-- Structural evolution source: V82__editable_coverages_rules_and_official_catalog.sql


ALTER TABLE plan_coverages
    ADD COLUMN IF NOT EXISTS damage_condition VARCHAR(32),
    ADD COLUMN IF NOT EXISTS event_condition VARCHAR(32),
    ADD COLUMN IF NOT EXISTS towing_radius_km INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_plan_coverage_damage_condition') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_damage_condition
            CHECK (damage_condition IS NULL OR damage_condition IN ('ANY_DAMAGE','TOTAL_ONLY','PARTIAL_ONLY'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_plan_coverage_event_condition') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_event_condition
            CHECK (event_condition IS NULL OR event_condition IN ('ANY_ORIGIN','COLLISION_DERIVED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_plan_coverage_towing_radius') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_towing_radius
            CHECK (towing_radius_km IS NULL OR towing_radius_km >= 0);
    END IF;
END $$;

ALTER TABLE discount_rule_benefits DROP CONSTRAINT IF EXISTS chk_discount_rule_benefit_action;

ALTER TABLE discount_rule_benefits ADD CONSTRAINT chk_discount_rule_benefit_action
    CHECK (action IN ('KEEP','REMOVE','FORCE_INCLUDE'));




-- Structural evolution source: V84__standalone_responsibility_term_acceptance.sql


CREATE TABLE IF NOT EXISTS inspection_responsibility_terms (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL REFERENCES inspection_requests(id) ON DELETE CASCADE,
    version INTEGER NOT NULL DEFAULT 1,
    public_token VARCHAR(160) NOT NULL UNIQUE,

    associate_name VARCHAR(140) NOT NULL,
    cpf VARCHAR(14) NOT NULL,
    whatsapp VARCHAR(30),
    plate VARCHAR(10),
    vehicle_model VARCHAR(120),
    model_year INTEGER,
    contracted_plan VARCHAR(160),
    inspection_created_at TIMESTAMPTZ,
    damage_details TEXT NOT NULL,

    created_by VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    whatsapp_sent_to VARCHAR(30),
    sent_at TIMESTAMPTZ,

    webauthn_registration_challenge VARCHAR(160),
    webauthn_registration_expires_at TIMESTAMPTZ,
    webauthn_origin VARCHAR(320),
    webauthn_rp_id VARCHAR(253),
    webauthn_credential_id VARCHAR(1024),
    webauthn_public_key BYTEA,
    webauthn_algorithm INTEGER,
    webauthn_sign_count BIGINT NOT NULL DEFAULT 0,
    webauthn_assertion_challenge VARCHAR(160),
    webauthn_assertion_expires_at TIMESTAMPTZ,

    acceptance_evidence_hash VARCHAR(64),
    term_pdf_sha256 VARCHAR(64),
    acceptance_device_metadata TEXT,
    acceptance_device_id VARCHAR(96),
    acceptance_ip VARCHAR(80),
    acceptance_latitude DOUBLE PRECISION,
    acceptance_longitude DOUBLE PRECISION,
    acceptance_accuracy_meters DOUBLE PRECISION,
    acceptance_location_captured_at TIMESTAMPTZ,
    acceptance_assertion_signature TEXT,
    acceptance_authenticator_data TEXT,
    acceptance_client_data_json TEXT,
    acceptance_proof_hash VARCHAR(64),
    acceptance_user_verified BOOLEAN,
    accepted_at TIMESTAMPTZ,

    CONSTRAINT uq_inspection_responsibility_term_version UNIQUE (inspection_id, version)
);

CREATE INDEX IF NOT EXISTS idx_inspection_responsibility_terms_inspection
    ON inspection_responsibility_terms(inspection_id, version DESC);

CREATE INDEX IF NOT EXISTS idx_inspection_responsibility_terms_accepted
    ON inspection_responsibility_terms(accepted_at);




-- Structural evolution source: V86__commercial_finance_asaas_consultant_accounts.sql


ALTER TABLE consultants
    ADD COLUMN IF NOT EXISTS commission_mode VARCHAR(20) NOT NULL DEFAULT 'PERCENTAGE',
    ADD COLUMN IF NOT EXISTS commission_value NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS payout_pix_key_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS payout_pix_key VARCHAR(255);

ALTER TABLE consultants DROP CONSTRAINT IF EXISTS ck_consultants_commission_mode;

ALTER TABLE consultants ADD CONSTRAINT ck_consultants_commission_mode
    CHECK (commission_mode IN ('PERCENTAGE', 'FIXED'));

ALTER TABLE consultants DROP CONSTRAINT IF EXISTS ck_consultants_commission_value;

ALTER TABLE consultants ADD CONSTRAINT ck_consultants_commission_value
    CHECK (commission_value >= 0 AND (commission_mode <> 'PERCENTAGE' OR commission_value <= 100));

ALTER TABLE consultants DROP CONSTRAINT IF EXISTS ck_consultants_payout_pix_key_type;

ALTER TABLE consultants ADD CONSTRAINT ck_consultants_payout_pix_key_type
    CHECK (payout_pix_key_type IS NULL OR payout_pix_key_type IN ('CPF','CNPJ','EMAIL','PHONE','EVP'));

CREATE TABLE IF NOT EXISTS associates (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    normalized_name VARCHAR(180) NOT NULL,
    document VARCHAR(14),
    whatsapp VARCHAR(30),
    email VARCHAR(180),
    asaas_customer_id VARCHAR(80),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_associates_document_not_null
    ON associates(document) WHERE document IS NOT NULL AND document <> '';

CREATE UNIQUE INDEX IF NOT EXISTS ux_associates_asaas_customer_not_null
    ON associates(asaas_customer_id) WHERE asaas_customer_id IS NOT NULL AND asaas_customer_id <> '';

CREATE INDEX IF NOT EXISTS idx_associates_normalized_name ON associates(normalized_name);

CREATE TABLE IF NOT EXISTS commercial_vehicles (
    id UUID PRIMARY KEY,
    associate_id UUID NOT NULL REFERENCES associates(id) ON DELETE RESTRICT,
    consultant_id UUID REFERENCES consultants(id) ON DELETE SET NULL,
    quotation_id UUID REFERENCES quotations(id) ON DELETE SET NULL,
    plate VARCHAR(10),
    model VARCHAR(140) NOT NULL,
    manufacture_year INTEGER,
    selected_plan_code VARCHAR(80),
    selected_plan_name VARCHAR(140),
    monthly_value NUMERIC(14,2) NOT NULL DEFAULT 0,
    adhesion_value NUMERIC(14,2) NOT NULL DEFAULT 0,
    billing_due_day INTEGER,
    first_billing_due_date DATE,
    adhesion_payment_method VARCHAR(20) NOT NULL DEFAULT 'BOLETO',
    recurring_billing_type VARCHAR(20) NOT NULL DEFAULT 'BOLETO',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    asaas_subscription_id VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_commercial_vehicles_quotation_not_null
    ON commercial_vehicles(quotation_id) WHERE quotation_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_commercial_vehicles_subscription_not_null
    ON commercial_vehicles(asaas_subscription_id) WHERE asaas_subscription_id IS NOT NULL AND asaas_subscription_id <> '';

CREATE INDEX IF NOT EXISTS idx_commercial_vehicles_associate ON commercial_vehicles(associate_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_commercial_vehicles_consultant ON commercial_vehicles(consultant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_commercial_vehicles_plate ON commercial_vehicles(plate);

ALTER TABLE commercial_vehicles DROP CONSTRAINT IF EXISTS ck_commercial_vehicle_adhesion_method;

ALTER TABLE commercial_vehicles ADD CONSTRAINT ck_commercial_vehicle_adhesion_method
    CHECK (adhesion_payment_method IN ('CASH_HAND','BOLETO','PIX'));

ALTER TABLE commercial_vehicles DROP CONSTRAINT IF EXISTS ck_commercial_vehicle_recurring_type;

ALTER TABLE commercial_vehicles ADD CONSTRAINT ck_commercial_vehicle_recurring_type
    CHECK (recurring_billing_type IN ('BOLETO','PIX'));

ALTER TABLE commercial_vehicles DROP CONSTRAINT IF EXISTS ck_commercial_vehicle_status;

ALTER TABLE commercial_vehicles ADD CONSTRAINT ck_commercial_vehicle_status
    CHECK (status IN ('DRAFT','ACTIVE','CANCELLED'));

CREATE TABLE IF NOT EXISTS financial_charges (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES commercial_vehicles(id) ON DELETE CASCADE,
    associate_id UUID NOT NULL REFERENCES associates(id) ON DELETE RESTRICT,
    consultant_id UUID REFERENCES consultants(id) ON DELETE SET NULL,
    provider VARCHAR(20) NOT NULL DEFAULT 'ASAAS',
    provider_payment_id VARCHAR(100),
    provider_subscription_id VARCHAR(100),
    charge_type VARCHAR(20) NOT NULL,
    billing_type VARCHAR(20) NOT NULL,
    value NUMERIC(14,2) NOT NULL,
    due_date DATE,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMPTZ,
    invoice_url VARCHAR(700),
    bank_slip_url VARCHAR(700),
    identification_field VARCHAR(120),
    bar_code VARCHAR(120),
    pix_payload TEXT,
    pix_encoded_image TEXT,
    external_reference VARCHAR(180),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_financial_charges_provider_payment
    ON financial_charges(provider_payment_id) WHERE provider_payment_id IS NOT NULL AND provider_payment_id <> '';

CREATE INDEX IF NOT EXISTS idx_financial_charges_vehicle ON financial_charges(vehicle_id, due_date DESC, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_financial_charges_consultant ON financial_charges(consultant_id, paid_at DESC);

CREATE INDEX IF NOT EXISTS idx_financial_charges_subscription ON financial_charges(provider_subscription_id);

ALTER TABLE financial_charges DROP CONSTRAINT IF EXISTS ck_financial_charges_value;

ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charges_value CHECK (value > 0);

ALTER TABLE financial_charges DROP CONSTRAINT IF EXISTS ck_financial_charges_status;

ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charges_status
    CHECK (status IN ('PENDING','CONFIRMED','RECEIVED','OVERDUE','REFUNDED','CANCELLED','ERROR'));

ALTER TABLE financial_charges DROP CONSTRAINT IF EXISTS ck_financial_charges_type;

ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charges_type
    CHECK (charge_type IN ('ADHESION','CLOSING','MONTHLY'));

ALTER TABLE financial_charges DROP CONSTRAINT IF EXISTS ck_financial_charges_billing_type;

ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charges_billing_type
    CHECK (billing_type IN ('CASH_HAND','BOLETO','PIX'));

CREATE TABLE IF NOT EXISTS consultant_withdrawals (
    id UUID PRIMARY KEY,
    consultant_id UUID NOT NULL REFERENCES consultants(id) ON DELETE RESTRICT,
    amount NUMERIC(14,2) NOT NULL,
    pix_key_type VARCHAR(20) NOT NULL,
    pix_key VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
    provider_transfer_id VARCHAR(100),
    fail_reason VARCHAR(700),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_consultant_withdrawals_provider_transfer
    ON consultant_withdrawals(provider_transfer_id) WHERE provider_transfer_id IS NOT NULL AND provider_transfer_id <> '';

CREATE INDEX IF NOT EXISTS idx_consultant_withdrawals_consultant ON consultant_withdrawals(consultant_id, created_at DESC);

ALTER TABLE consultant_withdrawals DROP CONSTRAINT IF EXISTS ck_consultant_withdrawal_amount;

ALTER TABLE consultant_withdrawals ADD CONSTRAINT ck_consultant_withdrawal_amount CHECK (amount > 0);

ALTER TABLE consultant_withdrawals DROP CONSTRAINT IF EXISTS ck_consultant_withdrawal_status;

ALTER TABLE consultant_withdrawals ADD CONSTRAINT ck_consultant_withdrawal_status
    CHECK (status IN ('REQUESTED','PROCESSING','PAID','FAILED','CANCELLED'));

CREATE TABLE IF NOT EXISTS consultant_wallet_movements (
    id UUID PRIMARY KEY,
    consultant_id UUID NOT NULL REFERENCES consultants(id) ON DELETE RESTRICT,
    vehicle_id UUID REFERENCES commercial_vehicles(id) ON DELETE SET NULL,
    charge_id UUID REFERENCES financial_charges(id) ON DELETE SET NULL,
    withdrawal_id UUID REFERENCES consultant_withdrawals(id) ON DELETE SET NULL,
    movement_type VARCHAR(20) NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    locked BOOLEAN NOT NULL DEFAULT TRUE,
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    unlocked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_wallet_consultant ON consultant_wallet_movements(consultant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_wallet_charge ON consultant_wallet_movements(charge_id);

CREATE INDEX IF NOT EXISTS idx_wallet_withdrawal ON consultant_wallet_movements(withdrawal_id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_wallet_charge_movement
    ON consultant_wallet_movements(charge_id, movement_type) WHERE charge_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_wallet_withdrawal_movement
    ON consultant_wallet_movements(withdrawal_id, movement_type) WHERE withdrawal_id IS NOT NULL;

ALTER TABLE consultant_wallet_movements DROP CONSTRAINT IF EXISTS ck_wallet_movement_type;

ALTER TABLE consultant_wallet_movements ADD CONSTRAINT ck_wallet_movement_type
    CHECK (movement_type IN ('COMMISSION','WITHDRAWAL','REVERSAL'));

CREATE TABLE IF NOT EXISTS asaas_webhook_events (
    event_id VARCHAR(120) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    processing_error VARCHAR(1000)
);




-- Structural evolution source: V89__associate_consultant_finance_office_commission_guide.sql


ALTER TABLE portal_users ADD COLUMN IF NOT EXISTS associate_id UUID REFERENCES associates(id) ON DELETE CASCADE;

CREATE UNIQUE INDEX IF NOT EXISTS ux_portal_users_associate_not_null
    ON portal_users(associate_id) WHERE associate_id IS NOT NULL;

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

ALTER TABLE associates ADD COLUMN IF NOT EXISTS birth_date DATE;

ALTER TABLE associates ADD COLUMN IF NOT EXISTS secondary_phone VARCHAR(30);

ALTER TABLE associates ADD COLUMN IF NOT EXISTS postal_code VARCHAR(12);

ALTER TABLE associates ADD COLUMN IF NOT EXISTS address_line VARCHAR(220);

ALTER TABLE associates ADD COLUMN IF NOT EXISTS address_number VARCHAR(30);

ALTER TABLE associates ADD COLUMN IF NOT EXISTS address_complement VARCHAR(120);

ALTER TABLE associates ADD COLUMN IF NOT EXISTS neighborhood VARCHAR(120);

ALTER TABLE associates ADD COLUMN IF NOT EXISTS city VARCHAR(120);

ALTER TABLE associates ADD COLUMN IF NOT EXISTS state VARCHAR(2);

ALTER TABLE consultants ADD COLUMN IF NOT EXISTS referrer_consultant_id UUID REFERENCES consultants(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_consultants_referrer ON consultants(referrer_consultant_id);

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

CREATE TABLE IF NOT EXISTS consultant_commission_rules (
    rule_key VARCHAR(80) PRIMARY KEY,
    label VARCHAR(220) NOT NULL,
    numeric_value NUMERIC(18,4) NOT NULL DEFAULT 0,
    unit VARCHAR(30) NOT NULL DEFAULT 'BRL',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by VARCHAR(160),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

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

CREATE TABLE IF NOT EXISTS consultant_career_levels (
    level_code VARCHAR(30) PRIMARY KEY,
    label VARCHAR(80) NOT NULL,
    min_active INTEGER NOT NULL,
    max_active INTEGER,
    fuel_allowance NUMERIC(14,2) NOT NULL DEFAULT 0,
    benefits TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS consultant_base_prizes (
    active_threshold INTEGER PRIMARY KEY,
    reward_description VARCHAR(300) NOT NULL,
    cash_value NUMERIC(16,2),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);




-- Structural evolution source: V90__persist_gestao_entitlements.sql


CREATE TABLE IF NOT EXISTS gestao_tenant_config_cache (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1,
    tenant_host VARCHAR(240) NOT NULL,
    config_json TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_gestao_tenant_config_singleton CHECK (singleton_id = 1)
);




-- Structural evolution source: V91__asaas_fee_passthrough.sql


ALTER TABLE financial_charges ADD COLUMN IF NOT EXISTS provider_fee NUMERIC(14,2) NOT NULL DEFAULT 0;

ALTER TABLE financial_charges ADD COLUMN IF NOT EXISTS gross_value NUMERIC(14,2);

ALTER TABLE financial_charges ALTER COLUMN gross_value SET NOT NULL;

DO $$ BEGIN
  ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charge_provider_fee CHECK (provider_fee >= 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charge_gross_value CHECK (gross_value >= value);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

ALTER TABLE consultant_withdrawals ADD COLUMN IF NOT EXISTS transaction_fee NUMERIC(14,2) NOT NULL DEFAULT 0;

ALTER TABLE consultant_withdrawals ADD COLUMN IF NOT EXISTS net_amount NUMERIC(14,2);

ALTER TABLE consultant_withdrawals ALTER COLUMN net_amount SET NOT NULL;

DO $$ BEGIN
  ALTER TABLE consultant_withdrawals ADD CONSTRAINT ck_consultant_withdrawal_fee CHECK (transaction_fee >= 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  ALTER TABLE consultant_withdrawals ADD CONSTRAINT ck_consultant_withdrawal_net CHECK (net_amount >= 0 AND net_amount <= amount);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;




-- Structural evolution source: V92__compatibility_schema_for_legacy_nh.sql


ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS preexisting_damage BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS preexisting_damage_details TEXT;

ALTER TABLE coverages
    ADD COLUMN IF NOT EXISTS optional_vehicle_categories VARCHAR(1000) NOT NULL DEFAULT '';

ALTER TABLE plan_coverages
    ADD COLUMN IF NOT EXISTS damage_condition VARCHAR(32),
    ADD COLUMN IF NOT EXISTS event_condition VARCHAR(32),
    ADD COLUMN IF NOT EXISTS towing_radius_km INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='chk_plan_coverage_damage_condition') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_damage_condition
            CHECK (damage_condition IS NULL OR damage_condition IN ('ANY_DAMAGE','TOTAL_ONLY','PARTIAL_ONLY'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='chk_plan_coverage_event_condition') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_event_condition
            CHECK (event_condition IS NULL OR event_condition IN ('ANY_ORIGIN','COLLISION_DERIVED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='chk_plan_coverage_towing_radius') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_towing_radius
            CHECK (towing_radius_km IS NULL OR towing_radius_km >= 0);
    END IF;
END $$;

ALTER TABLE discount_rule_benefits DROP CONSTRAINT IF EXISTS chk_discount_rule_benefit_action;

ALTER TABLE discount_rule_benefits ADD CONSTRAINT chk_discount_rule_benefit_action
    CHECK (action IN ('KEEP','REMOVE','FORCE_INCLUDE'));

CREATE TABLE IF NOT EXISTS inspection_responsibility_terms (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL REFERENCES inspection_requests(id) ON DELETE CASCADE,
    version INTEGER NOT NULL DEFAULT 1,
    public_token VARCHAR(160) NOT NULL UNIQUE,

    associate_name VARCHAR(140) NOT NULL,
    cpf VARCHAR(14) NOT NULL,
    whatsapp VARCHAR(30),
    plate VARCHAR(10),
    vehicle_model VARCHAR(120),
    model_year INTEGER,
    contracted_plan VARCHAR(160),
    inspection_created_at TIMESTAMPTZ,
    damage_details TEXT NOT NULL,

    created_by VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    whatsapp_sent_to VARCHAR(30),
    sent_at TIMESTAMPTZ,

    webauthn_registration_challenge VARCHAR(160),
    webauthn_registration_expires_at TIMESTAMPTZ,
    webauthn_origin VARCHAR(320),
    webauthn_rp_id VARCHAR(253),
    webauthn_credential_id VARCHAR(1024),
    webauthn_public_key BYTEA,
    webauthn_algorithm INTEGER,
    webauthn_sign_count BIGINT NOT NULL DEFAULT 0,
    webauthn_assertion_challenge VARCHAR(160),
    webauthn_assertion_expires_at TIMESTAMPTZ,

    acceptance_evidence_hash VARCHAR(64),
    term_pdf_sha256 VARCHAR(64),
    acceptance_device_metadata TEXT,
    acceptance_device_id VARCHAR(96),
    acceptance_ip VARCHAR(80),
    acceptance_latitude DOUBLE PRECISION,
    acceptance_longitude DOUBLE PRECISION,
    acceptance_accuracy_meters DOUBLE PRECISION,
    acceptance_location_captured_at TIMESTAMPTZ,
    acceptance_assertion_signature TEXT,
    acceptance_authenticator_data TEXT,
    acceptance_client_data_json TEXT,
    acceptance_proof_hash VARCHAR(64),
    acceptance_user_verified BOOLEAN,
    accepted_at TIMESTAMPTZ,

    CONSTRAINT uq_inspection_responsibility_term_version UNIQUE (inspection_id, version)
);

CREATE INDEX IF NOT EXISTS idx_inspection_responsibility_terms_inspection
    ON inspection_responsibility_terms(inspection_id, version DESC);

CREATE INDEX IF NOT EXISTS idx_inspection_responsibility_terms_accepted
    ON inspection_responsibility_terms(accepted_at);


