// Package db implements config.DB over PostgreSQL for the standalone binary. A
// host that embeds the testing service as a library supplies its own client
// instead, so nothing here runs migrations as a side effect of connecting.
package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/uptrace/bun"
	"github.com/uptrace/bun/dialect/pgdialect"
	"github.com/uptrace/bun/driver/pgdriver"
)

// DefaultMaxOpenConns caps the pool when Options leaves it unset. database/sql
// otherwise allows unlimited connections, which turns a burst of executor
// workers into a burst of PostgreSQL backends.
const DefaultMaxOpenConns = 16

// Options describes how to reach PostgreSQL.
type Options struct {
	// DSN is a postgres:// or unix:// URL. Query parameters the driver does not
	// recognize are applied to the session, so search_path belongs here.
	DSN string
	// ApplicationName labels the connection in pg_stat_activity.
	ApplicationName string
	// MaxOpenConns caps the pool. Non-positive means DefaultMaxOpenConns.
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
	// pgdriver panics on a malformed DSN; a bad address in a config file should
	// surface as an error the caller can report.
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("postgres: parse DSN: %v", r)
		}
	}()

	connector := pgdriver.NewConnector(
		pgdriver.WithDSN(opts.DSN),
		pgdriver.WithApplicationName(opts.ApplicationName),
	)

	maxOpenConns := opts.MaxOpenConns
	if maxOpenConns <= 0 {
		maxOpenConns = DefaultMaxOpenConns
	}
	sqlDB := sql.OpenDB(connector)
	sqlDB.SetMaxOpenConns(maxOpenConns)
	sqlDB.SetMaxIdleConns(maxOpenConns)

	return &DB{sqlDB: sqlDB, bunDB: bun.NewDB(sqlDB, pgdialect.New())}, nil
}

// GetBunDb satisfies config.DB. The context is unused: the pool is already open.
func (d *DB) GetBunDb(_ context.Context) (*bun.DB, error) {
	return d.bunDB, nil
}

// Close releases the pool.
func (d *DB) Close() error {
	return d.bunDB.Close()
}
