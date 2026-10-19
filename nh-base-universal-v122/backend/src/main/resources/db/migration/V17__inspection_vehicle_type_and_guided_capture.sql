ALTER TABLE inspection_requests
    ADD COLUMN vehicle_type VARCHAR(30) NOT NULL DEFAULT 'FOUR_WHEELS_OR_MORE';

UPDATE inspection_requests ir
SET vehicle_type = 'MOTORCYCLE'
FROM quotations q
WHERE ir.quotation_id = q.id
  AND UPPER(q.category_code) LIKE 'MOTORCYCLE%';

ALTER TABLE inspection_requests
    ALTER COLUMN vehicle_type DROP DEFAULT;
