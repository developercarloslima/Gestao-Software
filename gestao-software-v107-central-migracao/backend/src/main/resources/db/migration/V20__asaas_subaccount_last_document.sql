-- CNPJ associado à última tentativa: evita novos POST para o documento duplicado
-- sem bloquear uma futura correção do CNPJ na empresa-cliente.
ALTER TABLE platform_asaas_subaccounts
    ADD COLUMN IF NOT EXISTS last_cnpj VARCHAR(14);

-- Compatibiliza as tentativas anteriores com o cadastro atual da empresa.
UPDATE platform_asaas_subaccounts sub
   SET last_cnpj = regexp_replace(COALESCE(company.document, ''), '[^0-9]', '', 'g')
  FROM platform_companies company
 WHERE sub.company_id = company.id AND sub.last_cnpj IS NULL;
