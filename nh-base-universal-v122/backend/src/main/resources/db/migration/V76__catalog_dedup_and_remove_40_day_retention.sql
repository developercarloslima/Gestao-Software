-- V76: limpeza segura do catálogo e remoção da retenção física/operacional de 40 dias.
--
-- 1) Consolida somente coberturas que têm o MESMO nome normalizado. Nomes semanticamente
--    diferentes (ex.: "Roubo" e "Roubo e Furto") não são fundidos automaticamente.
-- 2) Mantém todas as associações de planos na cobertura canônica escolhida.
-- 3) Arquivos de vistoria preservados deixam de expirar por idade.
--
-- A exclusão automática passa a ser responsabilidade da aplicação e atinge somente
-- cotações cujo prazo comercial venceu e que não possuem qualquer arquivo de vistoria.

CREATE TEMP TABLE coverage_dedupe_map ON COMMIT DROP AS
WITH ranked AS (
    SELECT
        c.id,
        first_value(c.id) OVER (
            PARTITION BY lower(regexp_replace(btrim(c.name), '[[:space:]]+', ' ', 'g'))
            ORDER BY CASE WHEN c.code LIKE 'MULTI_%' THEN 1 ELSE 0 END, c.id
        ) AS keep_id,
        row_number() OVER (
            PARTITION BY lower(regexp_replace(btrim(c.name), '[[:space:]]+', ' ', 'g'))
            ORDER BY CASE WHEN c.code LIKE 'MULTI_%' THEN 1 ELSE 0 END, c.id
        ) AS rn
    FROM coverages c
)
SELECT id AS duplicate_id, keep_id
FROM ranked
WHERE rn > 1;

-- Copia para a cobertura canônica vínculos de planos que só existiam na duplicata.
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT pc.plan_id, m.keep_id, pc.status, pc.detail, pc.monthly_price, pc.sort_order
FROM plan_coverages pc
JOIN coverage_dedupe_map m ON m.duplicate_id = pc.coverage_id
WHERE NOT EXISTS (
    SELECT 1
    FROM plan_coverages existing
    WHERE existing.plan_id = pc.plan_id
      AND existing.coverage_id = m.keep_id
)
ON CONFLICT (plan_id, coverage_id) DO NOTHING;

-- Se a canônica ainda não tinha regras de limite, reaproveita as regras da duplicata.
UPDATE coverage_rules cr
SET coverage_id = m.keep_id
FROM coverage_dedupe_map m
WHERE cr.coverage_id = m.duplicate_id
  AND NOT EXISTS (
      SELECT 1 FROM coverage_rules canonical_rule WHERE canonical_rule.coverage_id = m.keep_id
  );

-- Remove regras/vínculos que ainda ficaram presos às duplicatas.
DELETE FROM coverage_rules cr
USING coverage_dedupe_map m
WHERE cr.coverage_id = m.duplicate_id;

DELETE FROM plan_coverages pc
USING coverage_dedupe_map m
WHERE pc.coverage_id = m.duplicate_id;

DELETE FROM coverages c
USING coverage_dedupe_map m
WHERE c.id = m.duplicate_id;

-- Arquivos que ainda existem no banco deixam de ter expiração temporal.
UPDATE inspection_assets
SET expires_at = NULL
WHERE purged_at IS NULL;

COMMENT ON COLUMN inspection_assets.expires_at IS
    'Campo legado. Arquivos preservados não expiram mais por idade; exclusão automática só ocorre para cotação vencida sem arquivo de vistoria.';
