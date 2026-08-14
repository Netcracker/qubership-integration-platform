package testingservice

import (
	"io/fs"
	"regexp"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// bun derives a migration name from the numeric prefix of the file name and
// refuses anything else.
var migrationNameRE = regexp.MustCompile(`^\d{14}__[0-9a-z_\-]+\.tx\.(up|down)\.sql$`)

func migrationFileNames(t *testing.T) []string {
	t.Helper()
	entries, err := fs.ReadDir(migrationFiles, "migrations")
	require.NoError(t, err)
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	return names
}

func TestMigrationsDiscoversEveryEmbeddedFile(t *testing.T) {
	migrations, err := Migrations()
	require.NoError(t, err)

	sorted := migrations.Sorted()
	require.Len(t, sorted, len(migrationFileNames(t)))

	for _, migration := range sorted {
		assert.NotNil(t, migration.Up, "migration %s has no up function", migration.Name)
		assert.Nil(t, migration.Down, "migration %s has an unexpected down function", migration.Name)
	}

	names := make([]string, 0, len(sorted))
	for _, migration := range sorted {
		names = append(names, migration.Name)
	}
	assert.Contains(t, names, "00000000000100")
	assert.Contains(t, names, "00000000000101")
}

func TestMigrationFilesAreNamedForBun(t *testing.T) {
	for _, name := range migrationFileNames(t) {
		// A plain .up.sql suffix would run the DDL outside a transaction and
		// leave the schema half-created on failure.
		assert.Regexp(t, migrationNameRE, name)
	}
}

func TestMigrationsBuildsAFreshRegistryPerCall(t *testing.T) {
	first, err := Migrations()
	require.NoError(t, err)
	second, err := Migrations()
	require.NoError(t, err)

	assert.NotSame(t, first, second)
	assert.Len(t, second.Sorted(), len(first.Sorted()))
}

func TestInitMigrationLeavesTheSchemaToTheHost(t *testing.T) {
	body, err := fs.ReadFile(migrationFiles, "migrations/00000000000100__init.tx.up.sql")
	require.NoError(t, err)
	sql := strings.ToLower(string(body))

	// The downstream schema is provisioned under a name of the host's choosing,
	// by a role that may not hold CREATE.
	assert.NotContains(t, sql, "create schema")
	assert.NotContains(t, sql, "set search_path")

	// Platform conventions: text over varchar, timestamptz over timestamp.
	assert.NotContains(t, sql, "varchar")
	for _, declaration := range timestampTypeRE.FindAllString(sql, -1) {
		// A bare timestamp column stores a wall time with no zone, and the lease
		// deadlines are compared against the database clock.
		assert.Equal(t, "timestamptz", declaration, "every timestamp column is timestamptz")
	}
}

// timestampTypeRE matches every spelling of the timestamp type, so that the one
// the file is allowed to use can be told apart from the ones it is not.
var timestampTypeRE = regexp.MustCompile(`timestamp(?:tz|\s+with(?:out)?\s+time\s+zone)?`)

func TestInitMigrationCreatesEveryObjectIdempotently(t *testing.T) {
	body, err := fs.ReadFile(migrationFiles, "migrations/00000000000100__init.tx.up.sql")
	require.NoError(t, err)
	sql := strings.ToLower(string(body))

	// A bare create would abort the whole transactional migration downstream. How
	// many objects the file creates is the integration suite's business, since it
	// applies the file twice against real PostgreSQL.
	assert.Equal(t, strings.Count(sql, "create table"), strings.Count(sql, "create table if not exists"))
	assert.Equal(t, strings.Count(sql, "create index"), strings.Count(sql, "create index if not exists"))
	assert.Equal(t, strings.Count(sql, "create type"), strings.Count(sql, "exception when duplicate_object"))
	assert.Equal(t, strings.Count(sql, "create trigger"), strings.Count(sql, "drop trigger if exists"))
	assert.Zero(t, strings.Count(sql, "create view"), "a view is created with create or replace")
	assert.Zero(t, strings.Count(sql, "create function"), "a function is created with create or replace")
	assert.NotZero(t, strings.Count(sql, "create or replace view"))
	assert.NotZero(t, strings.Count(sql, "create or replace function"))
}

func executionMigration(t *testing.T) string {
	t.Helper()
	body, err := fs.ReadFile(migrationFiles, "migrations/00000000000101__execution.tx.up.sql")
	require.NoError(t, err)
	return strings.ToLower(string(body))
}

func TestExecutionMigrationAddsTheClaimColumnsIdempotently(t *testing.T) {
	sql := executionMigration(t)

	for _, column := range []string{"ordinal integer", "lease_until timestamptz", "lease_owner uuid"} {
		assert.Contains(t, sql, "add column if not exists "+column)
	}
	assert.Equal(t, 3, strings.Count(sql, "alter table"))
	assert.Equal(t, strings.Count(sql, "add column"), strings.Count(sql, "add column if not exists"))
	assert.Equal(t, 2, strings.Count(sql, "create index if not exists"))
	assert.Equal(t, strings.Count(sql, "create index"), strings.Count(sql, "create index if not exists"))

	// The same rules migration 100 follows, for the same reason.
	assert.NotContains(t, sql, "create schema")
	assert.NotContains(t, sql, "set search_path")
	assert.NotContains(t, sql, "varchar")
}

func TestExecutionMigrationBackfillsTheOrdinalOfExistingRows(t *testing.T) {
	sql := executionMigration(t)

	// Without the backfill every row created before this migration keeps a null
	// ordinal and sorts last in arbitrary order.
	assert.Contains(t, sql, "row_number() over (partition by tests_run_id order by start nulls last, id)")
	// Re-applying the migration must not renumber rows that already have one.
	assert.Contains(t, sql, "ordinal is null")
}

func TestExecutionMigrationReturnsTheRunningCasesOfTheSourceToTheQueue(t *testing.T) {
	sql := executionMigration(t)

	// A case the source left running holds no lease, and nothing reports on it.
	// The guard on lease_owner is what keeps a re-apply off the cases the workers
	// of this module hold once the column is theirs.
	assert.Contains(t, sql, "where status = 'running' and lease_owner is null")
	// validation_errors carries unique (test_case_run_id, matcher_id), so the rows
	// of the interrupted attempt would fail the next one on its first repeated
	// matcher.
	assert.Contains(t, sql, "delete from validation_errors")
}

func TestExecutionMigrationRecreatesTheTestCaseRunsView(t *testing.T) {
	sql := executionMigration(t)

	// The view selects test_case_run.*, which PostgreSQL expands at creation
	// time; create or replace can only append columns at the end, and the new
	// ones belong ahead of the joined ones.
	assert.Contains(t, sql, "drop view if exists test_case_runs_view")
	assert.Contains(t, sql, "create view test_case_runs_view as")
	assert.NotContains(t, sql, "create or replace view")
}
