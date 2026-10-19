-- Permite anexos produzidos pela Gerência da Oficina durante o checklist técnico.
-- Mantém os contextos existentes e acrescenta WORKSHOP.

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
