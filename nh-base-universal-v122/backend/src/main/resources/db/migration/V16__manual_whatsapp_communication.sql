ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS completion_message_sent_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS decision_message_sent_at TIMESTAMPTZ;

COMMENT ON COLUMN inspection_requests.completion_message_sent_at IS
    'Data em que o consultor acionou manualmente a mensagem de conclusão da vistoria para o associado.';

COMMENT ON COLUMN inspection_requests.decision_message_sent_at IS
    'Data em que a equipe de análise acionou manualmente a mensagem de aprovação ou recusa para o associado.';
