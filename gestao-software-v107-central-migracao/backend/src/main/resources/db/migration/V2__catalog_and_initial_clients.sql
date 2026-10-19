INSERT INTO platform_modules(code, name, description, sort_order) VALUES
    ('SITE_PRINCIPAL', 'Site Principal', 'Site institucional white-label da empresa-cliente.', 5),
    ('COTA', 'Cota', 'Cotações, planos, FIPE, adicionais, descontos e aceite comercial.', 10),
    ('VISTORIA_EVENTOS', 'Vistoria / Eventos', 'Vistorias, eventos, checklist, guincho, oficina, compras e dossiês.', 20),
    ('APONTAMENTO_DIGITAL', 'Apontamento Digital', 'Jornada, escalas, ponto, selfies, WebAuthn e banco de horas.', 30),
    ('GESTAO_FINANCEIRA_COMERCIAL', 'Gestão Financeira e Comercial', 'Associados, veículos, cobranças, comissões e saques.', 40)
ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, sort_order=EXCLUDED.sort_order, active=TRUE;

INSERT INTO platform_partners(code, name, profit_share_percent, active) VALUES
    ('PEDRO_HENRIQUE', 'Pedro Henrique', 50.00, TRUE),
    ('CARLOS_LIMA', 'Carlos Lima', 50.00, TRUE)
ON CONFLICT (code) DO NOTHING;

DO $$
DECLARE nh UUID; smg UUID;
BEGIN
  SELECT id INTO nh FROM platform_companies WHERE LOWER(trade_name)='novo horizonte' LIMIT 1;
  IF nh IS NULL THEN
    nh := gen_random_uuid();
    INSERT INTO platform_companies(id,group_name,legal_name,trade_name,status,plan_name,monthly_fee,billing_due_day,contract_start_date,
      platform_display_name,platform_legal_name,logo_url,favicon_url,primary_color,secondary_color,accent_color,site_title,company_tagline,login_subtitle,default_module_landing,notes)
    VALUES(nh,'Grupo Sampaio','Novo Horizonte Proteção Veicular','Novo Horizonte','ACTIVE','Personalizado',0,10,CURRENT_DATE,
      'Novo Horizonte','Gestão Software LTDA','/assets/logo-nh-oficial.png','/assets/favicon-nh.png','#0b5cff','#050b23','#e5f0ff','Novo Horizonte','Proteção veicular, tecnologia e atendimento.','Tecnologia fornecida pela Gestão Software LTDA','/','Cliente inicial administrado pelo Control Plane da Gestão Software.');
  END IF;

  INSERT INTO platform_company_modules(company_id,module_code,enabled,monthly_price) VALUES
    (nh,'SITE_PRINCIPAL',TRUE,0),(nh,'COTA',TRUE,0),(nh,'VISTORIA_EVENTOS',TRUE,0),(nh,'APONTAMENTO_DIGITAL',TRUE,0),(nh,'GESTAO_FINANCEIRA_COMERCIAL',TRUE,0)
  ON CONFLICT(company_id,module_code) DO UPDATE SET enabled=TRUE,updated_at=NOW();

  INSERT INTO platform_company_hosting(company_id,environment_name,domain,subdomain,dns_mode,app_url,api_url,deployment_method,hosting_notes)
  VALUES(nh,'producao-nh','nh.gestaosoftware.com.br','nh.gestaosoftware.com.br','SUBDOMAIN','https://nh.gestaosoftware.com.br','https://nh.gestaosoftware.com.br/api','Manual / PowerShell','VPS operacional própria da Novo Horizonte.')
  ON CONFLICT(company_id) DO UPDATE SET domain=EXCLUDED.domain,subdomain=EXCLUDED.subdomain,dns_mode=EXCLUDED.dns_mode,app_url=EXCLUDED.app_url,api_url=EXCLUDED.api_url,updated_at=NOW();

  SELECT id INTO smg FROM platform_companies WHERE LOWER(trade_name)='smg' LIMIT 1;
  IF smg IS NULL THEN
    smg := gen_random_uuid();
    INSERT INTO platform_companies(id,group_name,legal_name,trade_name,status,plan_name,monthly_fee,billing_due_day,contract_start_date,
      platform_display_name,platform_legal_name,primary_color,secondary_color,accent_color,site_title,company_tagline,login_subtitle,default_module_landing,notes)
    VALUES(smg,'Grupo Sampaio','SMG','SMG','ACTIVE','Personalizado',0,10,CURRENT_DATE,
      'SMG','Gestão Software LTDA','#1264f4','#06164a','#dcecff','SMG','Apontamento digital da empresa.','Tecnologia fornecida pela Gestão Software LTDA','/ponto/','SMG possui apenas o módulo Apontamento Digital.');
  END IF;

  DELETE FROM platform_company_modules WHERE company_id=smg;
  INSERT INTO platform_company_modules(company_id,module_code,enabled,monthly_price) VALUES (smg,'APONTAMENTO_DIGITAL',TRUE,0);

  INSERT INTO platform_company_hosting(company_id,environment_name,domain,subdomain,dns_mode,app_url,api_url,deployment_method,hosting_notes)
  VALUES(smg,'producao-smg','smg.gestaosoftware.com.br','smg.gestaosoftware.com.br','SUBDOMAIN','https://smg.gestaosoftware.com.br','https://smg.gestaosoftware.com.br/api','Manual / PowerShell','VPS operacional própria da SMG.')
  ON CONFLICT(company_id) DO UPDATE SET domain=EXCLUDED.domain,subdomain=EXCLUDED.subdomain,dns_mode=EXCLUDED.dns_mode,app_url=EXCLUDED.app_url,api_url=EXCLUDED.api_url,updated_at=NOW();
END $$;
