-- Permite que Admin, Analista e Supervisão corrijam dados cadastrais/veículo
-- diretamente na vistoria, mantendo a cotação vinculada sincronizada.

alter table inspection_requests
    add column if not exists vehicle_model varchar(120),
    add column if not exists model_year integer;

update inspection_requests i
   set vehicle_model = coalesce(i.vehicle_model, q.model),
       model_year = coalesce(i.model_year, q.manufacture_year)
  from quotations q
 where i.quotation_id = q.id
   and (i.vehicle_model is null or i.model_year is null);
