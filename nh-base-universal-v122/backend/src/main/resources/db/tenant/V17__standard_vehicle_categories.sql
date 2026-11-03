-- Cada tenant define seus próprios preços e planos; estes códigos são apenas o catálogo estrutural.
-- A cotação e o painel ADM dependem destes códigos para exibir/ativar as categorias.
-- Não sobrescreve nomes nem o estado ativo de registros já configurados.
INSERT INTO vehicle_categories (code, name, active) VALUES
  ('MOTORCYCLE_PROMO_2026', 'Tabela promocional - motocicletas', TRUE),
  ('MOTORCYCLE_UP_TO_300', 'Motocicletas até 300cc', TRUE),
  ('MOTORCYCLE_OVER_300', 'Motocicletas acima de 300cc', TRUE),
  ('SCOOTER_ELECTRIC', 'Scooters e elétricas', TRUE),
  ('CAR_NATIONAL', 'Carros nacionais', TRUE),
  ('CAR_IMPORTED', 'Carros importados', TRUE),
  ('UTILITY', 'Utilitários', TRUE),
  ('TRUCK', 'Caminhões', TRUE)
ON CONFLICT (code) DO NOTHING;
