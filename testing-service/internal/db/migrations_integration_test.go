//go:build integration

package db_test

import (
	"context"
	"os"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/testsupport"
)

func TestMain(m *testing.M) {
	os.Exit(testsupport.RunMain(m))
}

// schemaDigestQuery describes the schema the connection points at, one line per
// object, in a stable order. Counting objects instead would let a second apply
// that dropped and recreated everything, taking the rows with it, look like no
// change at all. The digest therefore names each column with its type, each
// constraint with its definition and each index with the statement that would
// recreate it.
const schemaDigestQuery = `
select line from (
	select format('column %s.%s %s %s %s',
			c.table_name, c.column_name, c.data_type, c.is_nullable,
			coalesce(c.column_default, '-')) as line
		from information_schema.columns c
		where c.table_schema = current_schema()
	union all
	select format('constraint %s %s', conrelid::regclass::text, pg_get_constraintdef(oid))
		from pg_constraint
		where connamespace = current_schema()::regnamespace
	union all
	select format('index %s', indexdef)
		from pg_indexes
		where schemaname = current_schema()
	union all
	select format('enum %s %s', t.typname, e.enumlabel)
		from pg_type t
		join pg_enum e on e.enumtypid = t.oid
		where t.typnamespace = current_schema()::regnamespace
	union all
	select format('view %s %s', table_name, view_definition)
		from information_schema.views
		where table_schema = current_schema()
	union all
	select format('routine %s %s', p.proname, pg_get_functiondef(p.oid))
		from pg_proc p
		where p.pronamespace = current_schema()::regnamespace
	union all
	select format('trigger %s %s', c.relname, pg_get_triggerdef(g.oid))
		from pg_trigger g
		join pg_class c on c.oid = g.tgrelid
		where c.relnamespace = current_schema()::regnamespace and not g.tgisinternal
) described
order by line`

// schemaDigest returns the description of every object the migrations left in
// the schema.
func schemaDigest(t *testing.T, database *testsupport.Database) []string {
	t.Helper()
	var lines []string
	require.NoError(t, database.Bun.NewRaw(schemaDigestQuery).Scan(context.Background(), &lines))
	require.NotEmpty(t, lines, "the migration created nothing")
	return lines
}

func TestInitMigrationAppliesTwiceToAnEmptyDatabase(t *testing.T) {
	database := testsupport.New(t)
	initMigration := testsupport.Migrations(t)[0]

	database.Apply(t, initMigration)
	before := schemaDigest(t, database)

	// A downstream installation already owns these objects, so the second apply
	// is the case that matters.
	database.Apply(t, initMigration)

	assert.Equal(t, before, schemaDigest(t, database), "the second apply changed the schema")
}

// The init migration also has to survive being applied to a database a later
// migration already touched, which is the case create or replace view is
// suspected of failing: it can neither drop nor retype a column, and migration
// 101 puts three of them in the middle of the view. It holds because the view
// selects test_case_run.*, and PostgreSQL expands that against the table as it
// stands when the statement runs, by which point the table already carries 101's
// columns in the same positions. A view spelled out column by column would not
// survive this.
func TestInitMigrationAppliesOnTopOfTheLaterMigrations(t *testing.T) {
	database := testsupport.NewMigrated(t)
	before := schemaDigest(t, database)

	database.Apply(t, testsupport.Migrations(t)[0])

	assert.Equal(t, before, schemaDigest(t, database), "the init migration undid a later one")
}

func TestEveryMigrationAppliesTwiceInARow(t *testing.T) {
	database := testsupport.New(t)
	migrations := testsupport.Migrations(t)

	for _, migration := range migrations {
		database.Apply(t, migration)
	}
	before := schemaDigest(t, database)

	for _, migration := range migrations {
		database.Apply(t, migration)
	}

	assert.Equal(t, before, schemaDigest(t, database), "re-applying the migrations changed the schema")
}

func TestExecutionMigrationBackfillsTheOrdinalOfExistingRows(t *testing.T) {
	database := testsupport.New(t)
	migrations := testsupport.Migrations(t)
	require.Len(t, migrations, 2)
	database.Apply(t, migrations[0])

	ctx := context.Background()
	firstRun, secondRun := uuid.New(), uuid.New()
	for _, id := range []uuid.UUID{firstRun, secondRun} {
		_, err := database.Bun.NewRaw("insert into tests_runs (id, created_at) values (?, now())", id).Exec(ctx)
		require.NoError(t, err)
	}

	// Rows of the first run in the order the backfill has to produce: the two
	// that started, oldest first, and then the one that never did.
	started := uuid.New()
	startedLater := uuid.New()
	neverStarted := uuid.New()
	insertCase := func(id, testsRunID uuid.UUID, start any) {
		_, err := database.Bun.NewRaw(
			"insert into test_case_runs (id, tests_run_id, start) values (?, ?, ?)", id, testsRunID, start).Exec(ctx)
		require.NoError(t, err)
	}
	insertCase(neverStarted, firstRun, nil)
	insertCase(startedLater, firstRun, "2026-01-02T00:00:00Z")
	insertCase(started, firstRun, "2026-01-01T00:00:00Z")
	// The backfill numbers each run on its own, so the second run starts at one.
	otherRunCase := uuid.New()
	insertCase(otherRunCase, secondRun, nil)

	database.Apply(t, migrations[1])

	assert.Equal(t, 1, ordinalOf(t, database, started))
	assert.Equal(t, 2, ordinalOf(t, database, startedLater))
	assert.Equal(t, 3, ordinalOf(t, database, neverStarted))
	assert.Equal(t, 1, ordinalOf(t, database, otherRunCase))

	// Applying it again must leave the numbering alone: the backfill is guarded
	// on a null ordinal, and a downstream database applies this on top of rows it
	// has already numbered.
	_, err := database.Bun.NewRaw("update test_case_runs set ordinal = 99 where id = ?", started).Exec(ctx)
	require.NoError(t, err)
	database.Apply(t, migrations[1])
	assert.Equal(t, 99, ordinalOf(t, database, started))
}

// A case the source left running holds no lease once this migration adds the
// column, and a case with no lease is a case no worker reports on. The migration
// returns it to the queue, together with what its interrupted attempt recorded.
func TestExecutionMigrationReturnsTheRunningCasesToTheQueue(t *testing.T) {
	database := testsupport.New(t)
	migrations := testsupport.Migrations(t)
	require.Len(t, migrations, 2)
	database.Apply(t, migrations[0])

	ctx := context.Background()
	testsRunID := uuid.New()
	_, err := database.Bun.NewRaw(
		"insert into tests_runs (id, created_at) values (?, now())", testsRunID).Exec(ctx)
	require.NoError(t, err)

	running, pending, finished := uuid.New(), uuid.New(), uuid.New()
	insertCase := func(id uuid.UUID, status string) {
		_, err := database.Bun.NewRaw(
			"insert into test_case_runs (id, tests_run_id, start, status) values (?, ?, now(), ?)",
			id, testsRunID, status).Exec(ctx)
		require.NoError(t, err)
	}
	insertCase(running, "running")
	insertCase(pending, "pending")
	insertCase(finished, "finished")
	_, err = database.Bun.NewRaw(
		"insert into validation_errors (test_case_run_id, message) values (?, ?)",
		running, "the interrupted attempt failed").Exec(ctx)
	require.NoError(t, err)

	database.Apply(t, migrations[1])

	assert.Equal(t, "pending", statusOf(t, database, running))
	assert.Equal(t, 1, countOf(t, database, "select count(*) from test_case_runs where id = ? and start is null", running),
		"a case back in the queue looks like one that never started")
	assert.Equal(t, 0, countOf(t,
		database, "select count(*) from validation_errors where test_case_run_id = ?", running))
	assert.Equal(t, "pending", statusOf(t, database, pending))
	assert.Equal(t, "finished", statusOf(t, database, finished), "a case that ran to the end is left alone")

	// Once the workers of this module own the column, a re-apply may not take the
	// cases they hold.
	held := uuid.New()
	_, err = database.Bun.NewRaw(
		`insert into test_case_runs (id, tests_run_id, start, status, lease_until, lease_owner)
		values (?, ?, now(), 'running', now() + interval '1 minute', ?)`,
		held, testsRunID, uuid.New()).Exec(ctx)
	require.NoError(t, err)

	database.Apply(t, migrations[1])

	assert.Equal(t, "running", statusOf(t, database, held))
}

func TestExecutionMigrationRecreatesTheViewWithTheNewColumns(t *testing.T) {
	database := testsupport.NewMigrated(t)

	var columns []string
	require.NoError(t, database.Bun.NewRaw(
		`select column_name from information_schema.columns
		where table_schema = current_schema() and table_name = 'test_case_runs_view'`,
	).Scan(context.Background(), &columns))

	// create or replace view cannot add these, because they belong ahead of the
	// joined columns; the migration drops the view and creates it again.
	assert.Contains(t, columns, "ordinal")
	assert.Contains(t, columns, "lease_until")
	assert.Contains(t, columns, "lease_owner")
	assert.Contains(t, columns, "errors", "the aggregate the list API reports is still there")
}

func ordinalOf(t *testing.T, database *testsupport.Database, id uuid.UUID) int {
	t.Helper()
	var ordinals []int
	require.NoError(t, database.Bun.NewRaw(
		"select ordinal from test_case_runs where id = ?", id).Scan(context.Background(), &ordinals))
	require.Len(t, ordinals, 1)
	return ordinals[0]
}

func statusOf(t *testing.T, database *testsupport.Database, id uuid.UUID) string {
	t.Helper()
	var statuses []string
	require.NoError(t, database.Bun.NewRaw(
		"select status from test_case_runs where id = ?", id).Scan(context.Background(), &statuses))
	require.Len(t, statuses, 1)
	return statuses[0]
}

func countOf(t *testing.T, database *testsupport.Database, query string, args ...any) int {
	t.Helper()
	var counts []int
	require.NoError(t, database.Bun.NewRaw(query, args...).Scan(context.Background(), &counts))
	require.Len(t, counts, 1)
	return counts[0]
}
