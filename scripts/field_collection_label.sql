-- Sprint 2 / #5a — labellisation manuelle d'un run.
--
-- Usage:
--   docker cp scripts/field_collection_label.sql delivery_postgres:/tmp/
--   docker exec delivery_postgres psql -U admin -d delivery_db ^
--     -v delivery_id='<uuid>' -v label='HONEST' -v admin_id='<uuid-admin>' ^
--     -f /tmp/field_collection_label.sql
--
-- Labels acceptés: HONEST, TELEPORT, SPRINT, DRIFT_THEN_VANISH, LOOP, EXCLUDE.
-- Ne jamais labelliser un run SIMULATED ou TEST comme s'il était terrain.

\set ON_ERROR_STOP on

update field_runs
set label = :'label',
    label_source = 'manual',
    labeled_at = now(),
    labeled_by = :'admin_id'
where delivery_id = :'delivery_id';

select delivery_id, source, label, labeled_at
from field_runs
where delivery_id = :'delivery_id';
