# V83.53 — Logo do espelho de ponto + evidências do aceite da vistoria/cotação

## 1. Espelho de ponto
- Mantida a correção visual aprovada da V83.52.
- Para a Novo Horizonte, quando não houver logo customizada no tenant, o PDF/preview usa `/logo-nh-simbolo.png`.
- Outras empresas continuam usando a própria logo cadastrada ou iniciais como fallback.

## 2. Aceite digital da vistoria/cotação
Após o associado concluir o aceite, Administrador, Análise e Supervisão passam a visualizar:
- data/hora do aceite;
- WhatsApp usado pelo sistema para envio do link;
- ID técnico do aparelho/navegador do aceite;
- latitude e longitude do GPS;
- precisão aproximada do GPS;
- horário de captura da localização;
- verificação WebAuthn e hash da prova digital.

## 3. Captura obrigatória de GPS no aceite
- O navegador solicita localização antes de iniciar a cerimônia de aceite.
- Sem latitude/longitude, o backend não libera o registro WebAuthn.
- A captura solicita alta precisão e não reutiliza posição antiga (`maximumAge: 0`).
- O GPS é evidência de auditoria e não implementa geocerca.

## 4. ID do aparelho
O navegador não fornece IMEI, serial físico ou identificador de hardware. O sistema cria um identificador próprio (`NHDEV-...`) e o persiste no navegador para fins de auditoria. Esse ID integra os metadados vinculados criptograficamente à evidência do aceite.

## 5. WhatsApp do envio
- Ao usar os botões de envio pelo WhatsApp no Admin/Supervisão/Análise, o número normalizado do associado é preservado no registro.
- Para registros históricos sem esse novo campo, a tela usa o WhatsApp atual da vistoria como fallback informativo.

## 6. PDF final da vistoria
A seção de aceite digital do dossiê final também passa a exibir:
- WhatsApp que recebeu o link;
- ID do aparelho do aceite;
- GPS e precisão;
- horário de captura do GPS;
- IP e demais hashes já existentes.

## 7. Banco de dados
Nova migração Flyway `V77__inspection_acceptance_audit_context.sql` adiciona:
- `acceptance_whatsapp_sent_to`;
- `acceptance_device_id`;
- `acceptance_location_captured_at`.

A migração preserva os dados existentes e não exige reset do PostgreSQL.
