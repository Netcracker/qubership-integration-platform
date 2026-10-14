package dao

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"io"
	"log/slog"
	"sync"
	"testing"

	"github.com/stretchr/testify/require"
	"github.com/uptrace/bun"
	"github.com/uptrace/bun/dialect/pgdialect"
)

// The harness the write tests share. A write is assembled and executed inside
// the repository method, so there is nothing to render from the outside;
// recordingConn stands in for PostgreSQL and keeps what bun sent.

type recordedStatements struct {
	mu         sync.Mutex
	statements []string
}

func (s *recordedStatements) record(query string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.statements = append(s.statements, query)
}

func (s *recordedStatements) only(t *testing.T) string {
	t.Helper()
	s.mu.Lock()
	defer s.mu.Unlock()
	require.Len(t, s.statements, 1)
	return s.statements[0]
}

// recordingConn answers every statement with one affected row and no rows. Only
// the SQL matters here, so nothing is scanned back.
type recordingConn struct{ recorded *recordedStatements }

func (c *recordingConn) Prepare(string) (driver.Stmt, error) { return nil, driver.ErrSkip }
func (c *recordingConn) Close() error                        { return nil }
func (c *recordingConn) Begin() (driver.Tx, error)           { return nil, driver.ErrSkip }

func (c *recordingConn) ExecContext(_ context.Context, query string, _ []driver.NamedValue) (driver.Result, error) {
	c.recorded.record(query)
	return driver.RowsAffected(1), nil
}

func (c *recordingConn) QueryContext(_ context.Context, query string, _ []driver.NamedValue) (driver.Rows, error) {
	c.recorded.record(query)
	return emptyRows{}, nil
}

type emptyRows struct{}

func (emptyRows) Columns() []string         { return nil }
func (emptyRows) Close() error              { return nil }
func (emptyRows) Next([]driver.Value) error { return io.EOF }

type recordingConnector struct{ recorded *recordedStatements }

func (c recordingConnector) Connect(context.Context) (driver.Conn, error) {
	return &recordingConn{c.recorded}, nil
}

func (c recordingConnector) Driver() driver.Driver { return nil }

// recordingContext returns a context the repositories can run against, and the
// statements they sent.
func recordingContext(t *testing.T) (context.Context, *recordedStatements) {
	t.Helper()
	recorded := &recordedStatements{}
	sqlDB := sql.OpenDB(recordingConnector{recorded})
	t.Cleanup(func() { require.NoError(t, sqlDB.Close()) })
	return withDb(context.Background(), bun.NewDB(sqlDB, pgdialect.New())), recorded
}

// discardLogger keeps the repositories' debug logging out of the test output.
func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, &slog.HandlerOptions{Level: slog.LevelError}))
}
