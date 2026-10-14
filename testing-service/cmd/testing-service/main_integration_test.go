//go:build integration

package main

import (
	"context"
	"io"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/testsupport"
)

func TestMain(m *testing.M) {
	os.Exit(testsupport.RunMain(m))
}

func quietLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func tableExists(t *testing.T, database *testsupport.Database, name string) bool {
	t.Helper()
	exists, err := database.Bun.NewSelect().
		Table("information_schema.tables").
		Where("table_schema = ?", database.Schema).
		Where("table_name = ?", name).
		Exists(context.Background())
	require.NoError(t, err)
	return exists
}

// The migrations are what the standalone binary applies before it serves, and
// the library mode never runs this path.
func TestPrepareDatabaseAppliesEveryMigration(t *testing.T) {
	database := testsupport.New(t)

	// The schema already exists, so this call is only about the migrations.
	require.NoError(t, prepareDatabase(context.Background(), database.Bun, "", quietLogger()))

	assert.True(t, tableExists(t, database, "test_cases"))
	assert.True(t, tableExists(t, database, "test_case_runs"))
	assert.True(t, tableExists(t, database, "endpoint_mocks"))
	assert.True(t, tableExists(t, database, "bun_migrations"), "the migrator records what it applied")
}

func TestPrepareDatabaseIsIdempotent(t *testing.T) {
	database := testsupport.New(t)
	ctx := context.Background()

	require.NoError(t, prepareDatabase(ctx, database.Bun, "", quietLogger()))
	require.NoError(t, prepareDatabase(ctx, database.Bun, "", quietLogger()),
		"a restart must not re-apply what is already there")

	assert.True(t, tableExists(t, database, "test_cases"))
}

// The migrator writes its bookkeeping table before anything else, and that write
// fails while search_path names a schema that does not exist yet.
func TestPrepareDatabaseCreatesTheSchemaItIsGiven(t *testing.T) {
	database := testsupport.New(t)
	ctx := context.Background()
	schema := database.Schema + "_created"

	require.NoError(t, prepareDatabase(ctx, database.Bun, schema, quietLogger()))

	exists, err := database.Bun.NewSelect().
		Table("information_schema.schemata").
		Where("schema_name = ?", schema).
		Exists(ctx)
	require.NoError(t, err)
	assert.True(t, exists)

	_, err = database.Bun.ExecContext(ctx, "drop schema if exists "+schema+" cascade")
	require.NoError(t, err)
}

func TestPrepareDatabaseReportsAnUnusableSchemaName(t *testing.T) {
	database := testsupport.New(t)

	err := prepareDatabase(context.Background(), database.Bun, "pg_catalog", quietLogger())

	require.Error(t, err)
	assert.Contains(t, err.Error(), "create schema")
}

// A rolling update starts the next replica before this one is done, so the
// second instance has to wait for the migrations rather than exit and crash-loop
// on the way up.
func TestPrepareDatabaseWaitsForTheInstanceHoldingTheLock(t *testing.T) {
	database := testsupport.New(t)
	ctx := context.Background()
	key := migrationLockKey("")

	holder, err := database.Bun.Conn(ctx)
	require.NoError(t, err)
	_, err = holder.ExecContext(ctx, "select pg_advisory_lock(?)", key)
	require.NoError(t, err)

	done := make(chan error, 1)
	go func() { done <- prepareDatabase(ctx, database.Bun, "", quietLogger()) }()

	select {
	case err := <-done:
		t.Fatalf("the migrations ran while another instance held the lock: %v", err)
	case <-time.After(500 * time.Millisecond):
	}

	_, err = holder.ExecContext(ctx, "select pg_advisory_unlock(?)", key)
	require.NoError(t, err)
	require.NoError(t, holder.Close())

	select {
	case err := <-done:
		require.NoError(t, err)
	case <-time.After(migrationLockTimeout):
		t.Fatal("the migrations never ran after the lock was released")
	}
	assert.True(t, tableExists(t, database, "test_cases"))
}

// Both replicas of a rolling update come up, and only one of them applies the
// migrations.
func TestPrepareDatabaseSurvivesTwoInstancesStartingAtOnce(t *testing.T) {
	database := testsupport.New(t)
	ctx := context.Background()

	results := make(chan error, 2)
	for range 2 {
		go func() { results <- prepareDatabase(ctx, database.Bun, "", quietLogger()) }()
	}
	for range 2 {
		require.NoError(t, <-results)
	}

	assert.True(t, tableExists(t, database, "test_cases"))
}

// The lock is released when the migrations are done, not held for the life of
// the process: the next start has to get it without waiting.
func TestPrepareDatabaseReleasesTheLock(t *testing.T) {
	database := testsupport.New(t)
	ctx := context.Background()
	require.NoError(t, prepareDatabase(ctx, database.Bun, "", quietLogger()))

	var locked bool
	require.NoError(t, database.Bun.
		QueryRowContext(ctx, "select pg_try_advisory_lock(?)", migrationLockKey("")).Scan(&locked))
	assert.True(t, locked)

	_, err := database.Bun.ExecContext(ctx, "select pg_advisory_unlock(?)", migrationLockKey(""))
	require.NoError(t, err)
}
