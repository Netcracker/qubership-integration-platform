// Package db implements config.DB over PostgreSQL for the standalone binary. A
// host that embeds the testing service as a library supplies its own client
// instead, so nothing here runs migrations as a side effect of connecting.
package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/uptrace/bun"
	"github.com/uptrace/bun/dialect/pgdialect"
	"github.com/uptrace/bun/driver/pgdriver"
)

// DefaultMaxOpenConns caps the pool when Options leaves it unset. database/sql
// otherwise allows unlimited connections, which turns a burst of executor
// workers into a burst of PostgreSQL backends.
const DefaultMaxOpenConns = 16

// MinMaxOpenConns is the smallest pool the service can make progress on. The
// migrations hold a connection for the advisory lock while they run their
// statements on a second one, so a pool of one deadlocks the startup with no
// error to report.
const MinMaxOpenConns = 2

// DefaultReadTimeout and DefaultWriteTimeout replace the socket deadlines the
// driver applies out of the box: 10 seconds for a read, 5 for a write. The read
// deadline covers the whole response to one statement, and the driver takes the
// minimum of it and the context deadline, so the driver default is a per-statement
// ceiling no caller can raise. A migration that rewrites a populated table, a
// retention sweep with its cascading deletes and a large export all run past 10
// seconds on an installation of any size, and the startup migrations fail the
// process with an i/o timeout that names nothing. These leave the context deadline
// as the real bound, while still freeing a connection whose peer went away.
//
// An installation that needs other values sets read_timeout and write_timeout on
// the DSN, which win over these.
const (
	DefaultReadTimeout  = 5 * time.Minute
	DefaultWriteTimeout = 1 * time.Minute
)

// Options describes how to reach PostgreSQL.
type Options struct {
	// DSN is a postgres:// or unix:// URL. Query parameters the driver does not
	// recognize are applied to the session, so search_path belongs here.
	DSN string
	// User and Password override the credentials of the DSN, and each is left
	// alone when empty. The DSN is parsed as a URL, so a password holding `#`,
	// `/` or `?` cuts it short and the driver reads a truncated address instead;
	// credentials given here reach the driver verbatim and need no encoding.
	User     string
	Password string
	// ApplicationName labels the connection in pg_stat_activity.
	ApplicationName string
	// MaxOpenConns caps the pool. Non-positive means DefaultMaxOpenConns, and
	// anything below MinMaxOpenConns is refused.
	MaxOpenConns int
}

// DB hands the same bun handle to every caller.
type DB struct {
	sqlDB *sql.DB
	bunDB *bun.DB
}

// New opens a lazy connection pool. It contacts PostgreSQL only when the first
// query runs, so a database that is still starting up does not fail the process.
func New(opts Options) (_ *DB, err error) {
	if opts.DSN == "" {
		return nil, errors.New("postgres: DSN is empty")
	}
	if opts.MaxOpenConns > 0 && opts.MaxOpenConns < MinMaxOpenConns {
		return nil, fmt.Errorf("postgres: a pool of %d connections is too small; %d is the minimum",
			opts.MaxOpenConns, MinMaxOpenConns)
	}
	// pgdriver panics on a malformed DSN; a bad address in a config file should
	// surface as an error the caller can report.
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("postgres: parse DSN: %v", r)
		}
	}()

	connector := pgdriver.NewConnector(driverOptions(opts)...)

	maxOpenConns := opts.MaxOpenConns
	if maxOpenConns <= 0 {
		maxOpenConns = DefaultMaxOpenConns
	}
	sqlDB := sql.OpenDB(connector)
	sqlDB.SetMaxOpenConns(maxOpenConns)
	sqlDB.SetMaxIdleConns(maxOpenConns)

	return &DB{sqlDB: sqlDB, bunDB: bun.NewDB(sqlDB, pgdialect.New())}, nil
}

// driverOptions puts the credentials after the DSN, so they win over whatever it
// carries. Applying them as options keeps them out of the URL the driver parses,
// which is the only way a credential holding a URI-reserved character reaches
// PostgreSQL unmangled. The socket timeouts go before it, so a DSN carrying
// read_timeout or write_timeout still overrides them.
func driverOptions(opts Options) []pgdriver.Option {
	driverOpts := []pgdriver.Option{
		pgdriver.WithReadTimeout(DefaultReadTimeout),
		pgdriver.WithWriteTimeout(DefaultWriteTimeout),
		pgdriver.WithDSN(opts.DSN),
	}
	if opts.User != "" {
		driverOpts = append(driverOpts, pgdriver.WithUser(opts.User))
	}
	if opts.Password != "" {
		driverOpts = append(driverOpts, pgdriver.WithPassword(opts.Password))
	}
	return append(driverOpts, pgdriver.WithApplicationName(opts.ApplicationName))
}

// GetBunDb satisfies config.DB. The context is unused: the pool is already open.
func (d *DB) GetBunDb(_ context.Context) (*bun.DB, error) {
	return d.bunDB, nil
}

// Close releases the pool.
func (d *DB) Close() error {
	return d.bunDB.Close()
}
