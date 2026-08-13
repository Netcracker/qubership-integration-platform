-- Execution columns of the work queue: the order cases run in, and the lease a
-- worker holds over the case it claimed. Every statement is idempotent, because
-- a downstream installation applies this on top of a schema it already owns.
-- Nothing is schema-qualified.

alter table test_case_runs add column if not exists ordinal integer;
alter table test_case_runs add column if not exists lease_until timestamptz;

-- lease_owner is a fencing token, not a flag: every write a worker makes about
-- its case is guarded by it, so a stalled worker whose lease was swept cannot
-- overwrite the attempt another worker now owns.
alter table test_case_runs add column if not exists lease_owner uuid;

-- Existing rows predate the column. Without the backfill they keep a null
-- ordinal and sort last in arbitrary order.
update test_case_runs
set ordinal = numbered.position
from (
    select
        id,
        row_number() over (partition by tests_run_id order by start nulls last, id) as position
    from
        test_case_runs
) numbered
where test_case_runs.id = numbered.id and test_case_runs.ordinal is null;

-- What the claim filters and orders on. The only index the table had is on
-- test_case_id, and a plain index on a five-value enum would not help.
create index if not exists idx_test_case_runs_tests_run_id_status_ordinal
    on test_case_runs (tests_run_id, status, ordinal);

-- What the lease sweeper filters on.
create index if not exists idx_test_case_runs_lease_until
    on test_case_runs (lease_until) where status = 'running';

-- The view expands test_case_run.* at creation time, so it does not gain the new
-- columns on its own. create or replace can only append columns at the end, and
-- these belong ahead of the joined ones, so the view is dropped and recreated.
drop view if exists test_case_runs_view;

create view test_case_runs_view as
    select
        test_case_run.*,
        test_case.name as test_case_name,
        test_case.description as test_case_description,
        trigger_reference.chain_id as chain_id,
        count(validation_error.id) as errors
    from
        test_case_runs test_case_run
    left join test_cases test_case on test_case_run.test_case_id = test_case.id
    left join trigger_references trigger_reference on trigger_reference.test_case_id = test_case_run.test_case_id
    left join validation_errors validation_error on test_case_run.id = validation_error.test_case_run_id
    group by test_case_run.id, test_case.name, test_case.description, trigger_reference.chain_id;
