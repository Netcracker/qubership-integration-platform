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
	assert.NotContains(t, sql, "timestamp with time zone")
	assert.NotContains(t, sql, "timestamp without time zone")
}

func TestInitMigrationCreatesEveryObjectIdempotently(t *testing.T) {
	body, err := fs.ReadFile(migrationFiles, "migrations/00000000000100__init.tx.up.sql")
	require.NoError(t, err)
	sql := strings.ToLower(string(body))

	counts := map[string]int{
		"create table if not exists":      15,
		"create index if not exists":      8,
		"exception when duplicate_object": 4,
		"create or replace view":          3,
		"create or replace function":      2,
		"drop trigger if exists":          4,
		"create trigger":                  4,
	}
	for fragment, want := range counts {
		assert.Equal(t, want, strings.Count(sql, fragment), "occurrences of %q", fragment)
	}

	// A bare create would abort the whole transactional migration downstream.
	assert.Equal(t, strings.Count(sql, "create table"), strings.Count(sql, "create table if not exists"))
	assert.Equal(t, strings.Count(sql, "create index"), strings.Count(sql, "create index if not exists"))
	assert.Equal(t, strings.Count(sql, "create view"), 0)
	assert.Equal(t, strings.Count(sql, "create type"), strings.Count(sql, "exception when duplicate_object"))
}
