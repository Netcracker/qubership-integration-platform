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

// The object counts migration 100 is expected to leave behind.
const (
	expectedTables    = 15
	expectedIndexes   = 8
	expectedEnums     = 4
	expectedViews     = 3
	expectedFunctions = 2
	expectedTriggers  = 4
)

type objectCounts struct {
	Tables    int `bun:"tables"`
	Indexes   int `bun:"indexes"`
	Enums     int `bun:"enums"`
	Views     int `bun:"views"`
	Functions int `bun:"functions"`
	Triggers  int `bun:"triggers"`
}

// countObjectsQuery counts what the migration created in the schema the
// connection points at. Only the named indexes are counted, because the ones
// backing a primary key come with the table rather than from a create index.
const countObjectsQuery = `
select
	(select count(*) from information_schema.tables
		where table_schema = current_schema() and table_type = 'BASE TABLE') as tables,
	(select count(*) from pg_indexes
		where schemaname = current_schema() and indexname like 'idx\_%') as indexes,
	(select count(*) from pg_type t join pg_namespace n on n.oid = t.typnamespace
		where n.nspname = current_schema() and t.typtype = 'e') as enums,
	(select count(*) from information_schema.views
		where table_schema = current_schema()) as views,
	(select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
		where n.nspname = current_schema()) as functions,
	(select count(*) from pg_trigger g
		join pg_class c on c.oid = g.tgrelid
		join pg_namespace n on n.oid = c.relnamespace
		where n.nspname = current_schema() and not g.tgisinternal) as triggers`

func countObjects(t *testing.T, database *testsupport.Database) objectCounts {
	t.Helper()
	var counts []objectCounts
	require.NoError(t, database.Bun.NewRaw(countObjectsQuery).Scan(context.Background(), &counts))
	require.Len(t, counts, 1)
	return counts[0]
}

func TestInitMigrationAppliesTwiceToAnEmptyDatabase(t *testing.T) {
	database := testsupport.New(t)
	initMigration := testsupport.Migrations(t)[0]

	database.Apply(t, initMigration)
	after := countObjects(t, database)
	assert.Equal(t, expectedTables, after.Tables)
	assert.Equal(t, expectedIndexes, after.Indexes)
	assert.Equal(t, expectedEnums, after.Enums)
	assert.Equal(t, expectedViews, after.Views)
	assert.Equal(t, expectedFunctions, after.Functions)
	assert.Equal(t, expectedTriggers, after.Triggers)

	// A downstream installation already owns these objects, so the second apply
	// is the case that matters.
	database.Apply(t, initMigration)
	assert.Equal(t, after, countObjects(t, database), "the second apply changed the schema")
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
