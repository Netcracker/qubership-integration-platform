// Package config declares the settings and the infrastructure that a host
// application supplies to the testing service.
package config

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/uptrace/bun"
)

// Values that WithDefaults substitutes for fields left unset.
const (
	DefaultCatalogAddress  = "http://qip-runtime-catalog:8080"
	DefaultEngineAddress   = "http://qip-engine:8080"
	DefaultPollInterval    = 30 * time.Second
	DefaultWorkerCount     = 4
	DefaultLeaseDuration   = time.Minute
	DefaultPaginationLimit = 20
	// DefaultRetentionInterval only paces the sweep. Retention stays off until
	// RetentionAge is set.
	DefaultRetentionInterval = time.Hour
)

// Config carries the settings of the testing service. It holds no DSN: the host
// application owns the database connection and hands it over through Deps.DB.
type Config struct {
	// CatalogAddress is the base URL of the runtime catalog.
	CatalogAddress string
	// EngineAddress is the base URL of the engine that serves chain triggers.
	EngineAddress string
	// PollInterval is how long a worker waits before looking for work again.
	PollInterval time.Duration
	// WorkerCount is the number of test case runs executed at the same time.
	WorkerCount int
	// LeaseDuration is how long a claimed test case run stays fenced to its
	// worker before the sweeper may return it to the queue.
	LeaseDuration time.Duration
	// PaginationLimit caps the page size the list endpoints accept.
	PaginationLimit int
	// RetentionAge is how long a test run is kept before retention deletes it
	// along with its case runs and validation errors. Zero keeps every run: this
	// is the one setting WithDefaults does not fill in, because a host that names
	// no age has not asked for anything to be deleted.
	RetentionAge time.Duration
	// RetentionInterval is how long retention waits between sweeps.
	RetentionInterval time.Duration
	// Production disables the operations that are unsafe on a live installation.
	Production bool
}

// WithDefaults returns a copy of c with every unset field filled in. A
// non-positive number counts as unset, so a caller that reads its configuration
// from a file it does not fully populate gets working values.
func (c Config) WithDefaults() Config {
	if c.CatalogAddress == "" {
		c.CatalogAddress = DefaultCatalogAddress
	}
	if c.EngineAddress == "" {
		c.EngineAddress = DefaultEngineAddress
	}
	if c.PollInterval <= 0 {
		c.PollInterval = DefaultPollInterval
	}
	if c.WorkerCount <= 0 {
		c.WorkerCount = DefaultWorkerCount
	}
	if c.LeaseDuration <= 0 {
		c.LeaseDuration = DefaultLeaseDuration
	}
	if c.PaginationLimit <= 0 {
		c.PaginationLimit = DefaultPaginationLimit
	}
	if c.RetentionInterval <= 0 {
		c.RetentionInterval = DefaultRetentionInterval
	}
	return c
}

// RetentionEnabled reports whether test runs are deleted once they reach
// RetentionAge.
func (c Config) RetentionEnabled() bool {
	return c.RetentionAge > 0
}

// DB hands out the bun handle that the repositories run their queries on. The
// method name matches the database client the downstream host already has, so
// that client satisfies this interface without an adapter.
type DB interface {
	GetBunDb(ctx context.Context) (*bun.DB, error)
}

// CurrentUserFunc resolves the name recorded on audited writes. It takes the
// request context, so a host that authenticates its callers can return the
// caller rather than a fixed name.
type CurrentUserFunc func(ctx context.Context) string

// Deps carries the infrastructure that a host application supplies. Authorization
// belongs on HTTPClient, as an http.RoundTripper.
type Deps struct {
	DB          DB
	Logger      *slog.Logger
	HTTPClient  *http.Client
	CurrentUser CurrentUserFunc
}
