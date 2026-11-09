-- Corrige somente a projeção de status dos veículos historicamente ativados
-- por aceite. Histórico contratual, cobranças e pagamentos permanecem intactos.
-- ACTIVE exige ACEITE FINAL e ADESÃO RECEBIDA.
UPDATE commercial_vehicles v
   SET status='PENDING_PAYMENT',updated_at=NOW()
 WHERE v.status='ACTIVE'
   AND (
       NOT EXISTS (
           SELECT 1 FROM inspection_requests i
            WHERE i.quotation_id=v.quotation_id AND i.status='APPROVED'
              AND i.request_type='NEW_INSPECTION' AND i.accepted_at IS NOT NULL
       )
       OR NOT EXISTS (
           SELECT 1 FROM financial_charges f
            WHERE f.vehicle_id=v.id AND f.charge_type='ADHESION' AND f.status='RECEIVED'
       )
   );
