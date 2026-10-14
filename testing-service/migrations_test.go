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

func TestInitMigrationCarriesTheExecutionColumnsAndTheirIndexes(t *testing.T) {
	body, err := fs.ReadFile(migrationFiles, "migrations/00000000000100__init.tx.up.sql")
	require.NoError(t, err)
	sql := strings.ToLower(string(body))

	// The work queue claims and leases cases off these three. They are declared
	// with the table rather than added afterwards, so nothing has to reconcile a
	// table that predates them.
	for _, column := range []string{"ordinal        integer", "lease_until    timestamptz", "lease_owner    uuid"} {
		assert.Contains(t, sql, column)
	}

	// What the claim filters and orders on, and what the lease sweeper filters on.
	assert.Contains(t, sql, "on test_case_runs (tests_run_id, status, ordinal)")
	assert.Contains(t, sql, "on test_case_runs (lease_until) where status = 'running'")
}
