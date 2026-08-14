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

-- REQUIRED BEFORE THIS MIGRATION RUNS: stop the executor that ran the cases
-- until now. The cutover needs a single writer, and a rolling upgrade does not
-- give one. The statement below rewrites the very rows that executor owns.
--
-- Cases left running by it hold no lease, and a case with no lease is a case no
-- worker reports on. They are returned to the queue here, together with what
-- their interrupted attempt recorded: validation_errors carries unique
-- (test_case_run_id, matcher_id), so the rows of that attempt would fail the next
-- one on its first repeated matcher.
--
-- The guard on lease_owner proves no worker of this module owns such a case, and
-- it is what keeps a re-apply off the cases the workers hold once lease_owner is
-- in use. It says nothing about the previous executor, which is the only thing
-- that produces these rows. With that executor still serving, an attempt in
-- flight loses its recorded validation errors, its case runs a second time
-- against the live chain, and the old worker, which carries no fencing token,
-- later writes its own terminal status over the new attempt.
with returned as (
    update test_case_runs set status = 'pending', start = null
    where status = 'running' and lease_owner is null
    returning id
)
delete from validation_errors where test_case_run_id in (select id from returned);

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
