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
	// DefaultProduction is what an installation that names no mode is treated
	// as. The flag hides operations rather than enabling them, so the safe
	// answer for an unconfigured installation is the restrictive one.
	DefaultProduction = true
)

// Config carries the settings of the testing service. It holds no DSN: the host
// application owns the database connection and hands it over through Deps.DB.
type Config struct {
	// CatalogAddress is the base URL of the runtime catalog.
	CatalogAddress string
	// EngineAddress is the base URL of the engine that serves chain triggers. It
	// is a fallback rather than the address every test case uses: a chain is
	// activated on the engine the catalog reports it deployed to, since a micro
	// domain gets a service of its own. Only its scheme and port are reused for
	// a resolved engine, so an installation that moves the engines to another
	// port names it here.
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
	// Production is reported through GET /mode so the front end can hide the
	// operations that are unsafe on a live installation. Nothing in the module
	// refuses a request because of it.
	//
	// It is a pointer because nil has to mean something other than false: an
	// installation that names no mode is a production one, and only a pointer
	// tells "unset" from "explicitly false" the way a non-positive number does
	// for the settings above. Read it through ProductionMode rather than
	// dereferencing it.
	Production *bool
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
	if c.Production == nil {
		production := DefaultProduction
		c.Production = &production
	}
	return c
}

// RetentionEnabled reports whether test runs are deleted once they reach
// RetentionAge.
func (c Config) RetentionEnabled() bool {
	return c.RetentionAge > 0
}

// ProductionMode reports what GET /mode answers. It reads an unset flag as
// production, so it holds whether or not WithDefaults has run.
func (c Config) ProductionMode() bool {
	return c.Production == nil || *c.Production
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

// WithDefaults returns a copy of d with every optional field filled in, so that
// nothing below the entry point has to guard against a nil. DB has no default:
// the entry point refuses a Deps without one.
func (d Deps) WithDefaults() Deps {
	if d.Logger == nil {
		d.Logger = slog.Default()
	}
	if d.HTTPClient == nil {
		d.HTTPClient = http.DefaultClient
	}
	return d
}
