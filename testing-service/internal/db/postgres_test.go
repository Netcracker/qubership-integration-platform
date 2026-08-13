package db

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const testDSN = "postgres://postgres:postgres@localhost:5432/postgres?sslmode=disable&search_path=testing_service"

func TestNewReturnsTheSameHandleToEveryCaller(t *testing.T) {
	database, err := New(Options{DSN: testDSN, ApplicationName: "testing-service"})
	require.NoError(t, err)
	t.Cleanup(func() { assert.NoError(t, database.Close()) })

	first, err := database.GetBunDb(context.Background())
	require.NoError(t, err)
	require.NotNil(t, first)

	second, err := database.GetBunDb(context.Background())
	require.NoError(t, err)
	assert.Same(t, first, second)
}

func TestNewCapsThePool(t *testing.T) {
	database, err := New(Options{DSN: testDSN, MaxOpenConns: 3})
	require.NoError(t, err)
	t.Cleanup(func() { assert.NoError(t, database.Close()) })
	assert.Equal(t, 3, database.sqlDB.Stats().MaxOpenConnections)

	defaulted, err := New(Options{DSN: testDSN})
	require.NoError(t, err)
	t.Cleanup(func() { assert.NoError(t, defaulted.Close()) })
	assert.Equal(t, DefaultMaxOpenConns, defaulted.sqlDB.Stats().MaxOpenConnections)
}

func TestNewRejectsBadOptions(t *testing.T) {
	tests := map[string]string{
		"empty DSN":       "",
		"unknown scheme":  "mysql://localhost:3306/db",
		"unparsable URL":  "postgres://user:pass@%%%/db",
		"bad sslmode":     "postgres://localhost:5432/db?sslmode=nonsense",
		"unknown timeout": "postgres://localhost:5432/db?timeout=soon",
	}
	for name, dsn := range tests {
		t.Run(name, func(t *testing.T) {
			database, err := New(Options{DSN: dsn})
			require.Error(t, err)
			assert.Nil(t, database)
		})
	}
}
