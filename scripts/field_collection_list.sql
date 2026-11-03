-- Sprint 2 / #5a — liste des runs à labelliser.
-- Usage: docker exec -i delivery_postgres psql -U admin -d delivery_db < scripts/field_collection_list.sql

select
  delivery_id,
  courier_id,
  source,
  started_at,
  last_fix_at,
  point_count,
  label,
  retention_until
from field_runs
order by started_at desc
limit 50;
