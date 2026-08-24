package db

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/uptrace/bun/driver/pgdriver"
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

// The migrations hold one connection for their advisory lock and run their
// statements on another. A pool of one would let the process start and then hang
// on the first of those statements, with no error and no crash loop, so the
// setting is refused instead.
func TestNewRejectsAPoolTooSmallToMigrateOn(t *testing.T) {
	database, err := New(Options{DSN: testDSN, MaxOpenConns: 1})

	require.Error(t, err)
	assert.ErrorContains(t, err, "too small")
	assert.Nil(t, database)
}

// A credential holding a character the URL grammar spends elsewhere cannot go
// into the DSN: `#` starts a fragment, `/` ends the authority and `?` starts the
// query, so the driver reads a truncated address or refuses the DSN outright.
// Passed on their own, the credentials need no encoding.
func TestNewTakesCredentialsThatWouldBreakTheDsn(t *testing.T) {
	const dsn = "postgres://localhost:5432/postgres?sslmode=disable&search_path=testing_service"
	for _, password := range []string{"pa#ss", "pa/ss", "pa?ss", "pa@ss", "pa:ss"} {
		t.Run(password, func(t *testing.T) {
			database, err := New(Options{DSN: dsn, User: "us@r", Password: password})
			require.NoError(t, err)
			t.Cleanup(func() { assert.NoError(t, database.Close()) })

			config := configOf(Options{DSN: dsn, User: "us@r", Password: password})
			assert.Equal(t, "us@r", config.User)
			assert.Equal(t, password, config.Password)
			assert.Equal(t, "localhost:5432", config.Addr)
			assert.Equal(t, "postgres", config.Database)
		})
	}
}

// The driver defaults to a 10-second read deadline that covers the response to a
// whole statement, and takes the minimum of it and the context deadline. Left
// alone, it caps every statement at 10 seconds no caller can raise: the startup
// migration over a populated table fails and the process crash-loops.
func TestSocketTimeoutsLeaveRoomForALongStatement(t *testing.T) {
	config := configOf(Options{DSN: testDSN})

	assert.Equal(t, DefaultReadTimeout, config.ReadTimeout)
	assert.Equal(t, DefaultWriteTimeout, config.WriteTimeout)
	assert.Greater(t, config.ReadTimeout, 10*time.Second, "the driver default is what this replaces")
}

// The DSN is the escape hatch an installation reaches for, so it has to win.
func TestTheDsnOverridesTheSocketTimeouts(t *testing.T) {
	config := configOf(Options{DSN: testDSN + "&read_timeout=42s&write_timeout=17s"})

	assert.Equal(t, 42*time.Second, config.ReadTimeout)
	assert.Equal(t, 17*time.Second, config.WriteTimeout)
}

// configOf is what the driver reads out of the options New builds.
func configOf(opts Options) *pgdriver.Config {
	return pgdriver.NewConnector(driverOptions(opts)...).Config()
}

// The same password spliced into the DSN is what the credentials above avoid.
func TestADsnCarryingAReservedCharacterIsRefused(t *testing.T) {
	database, err := New(Options{DSN: "postgres://user:pa/ss@localhost:5432/postgres"})

	require.Error(t, err)
	assert.ErrorContains(t, err, "parse DSN")
	assert.Nil(t, database)
}

// The DSN still carries credentials for an installation that has no reason to
// split them out, and the explicit ones win when both are given.
func TestCredentialsOverrideTheOnesInTheDsn(t *testing.T) {
	database, err := New(Options{DSN: testDSN, User: "reader", Password: "secret"})
	require.NoError(t, err)
	t.Cleanup(func() { assert.NoError(t, database.Close()) })

	config := configOf(Options{DSN: testDSN, User: "reader", Password: "secret"})
	assert.Equal(t, "reader", config.User)
	assert.Equal(t, "secret", config.Password)

	carried := configOf(Options{DSN: testDSN})
	assert.Equal(t, "postgres", carried.User)
	assert.Equal(t, "postgres", carried.Password)
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
