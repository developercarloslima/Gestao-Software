-- Arquivo fonte do Site Principal, privado, nunca publicado automaticamente.
ALTER TABLE platform_company_files DROP CONSTRAINT IF EXISTS ck_platform_company_file_purpose;
ALTER TABLE platform_company_files ADD CONSTRAINT ck_platform_company_file_purpose
 CHECK (purpose IN ('LOGO','VISUAL_IDENTITY_PDF','SITE_SOURCE_ZIP'));
