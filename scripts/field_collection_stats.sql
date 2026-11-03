-- Sprint 2 / #5a — progression de la collecte terrain.
-- Usage: docker exec -i delivery_postgres psql -U admin -d delivery_db < scripts/field_collection_stats.sql

select
  (select count(*) from field_runs) as total_runs,
  (select count(*) from field_runs where source = 'FIELD') as field_runs,
  (select count(*) from field_runs
    where source = 'FIELD' and label is not null and label <> 'EXCLUDE') as labeled_field_runs,
  (select count(*) from field_trace_points) as total_points;

select source, count(*) as runs
from field_runs
group by source
order by source;

select label, count(*) as runs
from field_runs
group by label
order by label;
