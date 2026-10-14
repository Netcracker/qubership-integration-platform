//go:build integration

package db_test

import (
	"context"
	"os"
	"testing"

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

// The init migration also has to survive being applied to a database the whole
// set already migrated, which is the case create or replace view is suspected of
// failing: it can neither drop nor retype a column. It holds because the view
// selects test_case_run.*, and PostgreSQL expands that against the table as it
// stands when the statement runs. A view spelled out column by column would not
// survive a column added to the table later.
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

// The claim reads ordinal and the lease off the view as well as the table, so
// the expansion of test_case_run.* has to carry all three.
func TestTheTestCaseRunsViewExposesTheExecutionColumns(t *testing.T) {
	database := testsupport.NewMigrated(t)

	var columns []string
	require.NoError(t, database.Bun.NewRaw(
		`select column_name from information_schema.columns
		where table_schema = current_schema() and table_name = 'test_case_runs_view'`,
	).Scan(context.Background(), &columns))

	assert.Contains(t, columns, "ordinal")
	assert.Contains(t, columns, "lease_until")
	assert.Contains(t, columns, "lease_owner")
	assert.Contains(t, columns, "errors", "the aggregate the list API reports is still there")
}
