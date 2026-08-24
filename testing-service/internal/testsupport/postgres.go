//go:build integration

package testsupport

import (
	"context"
	"fmt"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
	"github.com/uptrace/bun"
	"github.com/uptrace/bun/migrate"

	testingservice "github.com/Netcracker/qubership-integration-platform/testing-service"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/db"
)

// The container the suite runs against. The version matches the one the compose
// stack and the Helm chart deploy, so a statement that works here works there.
const (
	postgresImage    = "postgres:14"
	postgresUser     = "postgres"
	postgresPassword = "postgres"
	postgresDatabase = "postgres"
	postgresPort     = "5432/tcp"
)

// startupTimeout bounds the wait for the server to accept connections. Pulling
// the image happens before it and is not counted.
const startupTimeout = 2 * time.Minute

var (
	startPostgresOnce sync.Once
	postgresContainer testcontainers.Container
	postgresDSN       string
	postgresErr       error
	schemaSequence    atomic.Uint64
)

// Database is one empty schema on a PostgreSQL container shared by the test
// binary. Every call to New takes a schema of its own, so tests in the same
// package never see each other's rows.
type Database struct {
	// Schema is what search_path points at on every connection of this handle.
	Schema string
	// DB satisfies config.DB, so it can be handed to testingservice.New.
	DB *db.DB
	// Bun is the same handle, for the seeding and the assertions a test writes.
	Bun *bun.DB
}

// New starts PostgreSQL unless the test binary already did, and returns an empty
// schema on it. The schema is dropped when the test ends.
func New(t *testing.T) *Database {
	t.Helper()

	startPostgresOnce.Do(func() {
		postgresContainer, postgresDSN, postgresErr = startPostgres()
	})
	require.NoError(t, postgresErr, "cannot start PostgreSQL")

	schema := fmt.Sprintf("test_%d", schemaSequence.Add(1))
	handle, err := db.New(db.Options{
		DSN:             postgresDSN + "&search_path=" + schema,
		ApplicationName: "testing-service-integration",
	})
	require.NoError(t, err)

	bunDB, err := handle.GetBunDb(context.Background())
	require.NoError(t, err)

	_, err = bunDB.NewRaw("create schema ?", bun.Ident(schema)).Exec(context.Background())
	require.NoError(t, err)

	t.Cleanup(func() {
		if _, err := bunDB.NewRaw("drop schema ? cascade", bun.Ident(schema)).Exec(context.Background()); err != nil {
			t.Errorf("cannot drop schema %s: %v", schema, err)
		}
		if err := handle.Close(); err != nil {
			t.Errorf("cannot close the connection pool of schema %s: %v", schema, err)
		}
	})

	return &Database{Schema: schema, DB: handle, Bun: bunDB}
}

// NewMigrated returns an empty schema with every migration already applied.
func NewMigrated(t *testing.T) *Database {
	t.Helper()
	database := New(t)
	database.Migrate(t)
	return database
}

// Migrations returns the migrations the module ships, in the order they apply.
func Migrations(t *testing.T) migrate.MigrationSlice {
	t.Helper()
	migrations, err := testingservice.Migrations()
	require.NoError(t, err)
	return migrations.Sorted()
}

// Apply runs one migration against the schema. It records nothing about what
// ran, which is what lets a test apply the same file twice and watch it stay
// idempotent.
func (d *Database) Apply(t *testing.T, migration migrate.Migration) {
	t.Helper()
	require.NoErrorf(t, migration.Up(context.Background(), d.Bun), "migration %s", migration.Name)
}

// Migrate applies every migration the module ships.
func (d *Database) Migrate(t *testing.T) {
	t.Helper()
	for _, migration := range Migrations(t) {
		d.Apply(t, migration)
	}
}

// RunMain runs the tests of an integration package and stops the shared
// container afterwards. A package that calls New has to call this from its
// TestMain, or the container outlives the test binary.
func RunMain(m *testing.M) int {
	code := m.Run()
	if postgresContainer != nil {
		if err := postgresContainer.Terminate(context.Background()); err != nil {
			fmt.Fprintf(os.Stderr, "cannot stop the PostgreSQL container: %v\n", err)
		}
	}
	return code
}

func startPostgres() (testcontainers.Container, string, error) {
	ctx := context.Background()
	container, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: testcontainers.ContainerRequest{
			Image:        postgresImage,
			ExposedPorts: []string{postgresPort},
			Env: map[string]string{
				"POSTGRES_USER":     postgresUser,
				"POSTGRES_PASSWORD": postgresPassword,
				"POSTGRES_DB":       postgresDatabase,
			},
			// The entrypoint starts the server once to initialize the data
			// directory and again to serve it, so the readiness line has to be
			// seen twice.
			WaitingFor: wait.ForAll(
				wait.ForLog("database system is ready to accept connections").WithOccurrence(2),
				wait.ForListeningPort(postgresPort),
			).WithDeadline(startupTimeout),
		},
		Started: true,
	})
	if err != nil {
		return nil, "", err
	}

	host, err := container.Host(ctx)
	if err != nil {
		return container, "", err
	}
	port, err := container.MappedPort(ctx, postgresPort)
	if err != nil {
		return container, "", err
	}

	dsn := fmt.Sprintf("postgres://%s:%s@%s/%s?sslmode=disable",
		postgresUser, postgresPassword, net.JoinHostPort(host, port.Port()), postgresDatabase)
	return container, dsn, nil
}
