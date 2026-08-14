// Command testing-service runs the testing service as a standalone process. It
// owns everything the library deliberately leaves to its host: the database
// connection, the migrations, health, metrics, pprof and the API prefix.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"hash/fnv"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/adaptor"
	fiberpprof "github.com/gofiber/fiber/v2/middleware/pprof"
	fiberrecover "github.com/gofiber/fiber/v2/middleware/recover"
	fiberswagger "github.com/gofiber/swagger"
	koanfyaml "github.com/knadh/koanf/parsers/yaml"
	koanfenv "github.com/knadh/koanf/providers/env"
	koanffile "github.com/knadh/koanf/providers/file"
	"github.com/knadh/koanf/v2"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/uptrace/bun"
	"github.com/uptrace/bun/migrate"

	testingservice "github.com/Netcracker/qubership-integration-platform/testing-service"
	// Registers the generated OpenAPI spec that fiberswagger reads.
	_ "github.com/Netcracker/qubership-integration-platform/testing-service/docs"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/db"
)

const (
	serviceName = "testing-service"
	// apiPrefix is the mount point the nginx and Kubernetes routing rules expect.
	apiPrefix = "/api/v1"
	// swaggerPath serves the UI at .../swagger/index.html and the spec at
	// .../swagger/doc.json.
	swaggerPath = apiPrefix + "/swagger/*"
	// swaggerDocURL is resolved by the browser against the UI page, so the spec
	// is found under whatever public prefix the request arrived on.
	swaggerDocURL = "doc.json"
	// envPrefix selects the environment variables that override the file.
	envPrefix       = "QIP_TESTING_"
	healthTimeout   = 3 * time.Second
	shutdownTimeout = 10 * time.Second
	// migrationLockTimeout bounds the wait for another instance to finish its
	// migrations, and the retry interval paces the attempts in between.
	migrationLockTimeout       = 2 * time.Minute
	migrationLockRetryInterval = time.Second
	migrationUnlockTimeout     = 5 * time.Second
	// defaultBodyLimit caps a request body. The zip imports are the large ones,
	// and fiber's own 4 MiB default is well under what the proxies in front of
	// this service allow through.
	defaultBodyLimit = 64 << 20
)

// appConfig is the settings of the process. Most of it is handed to the library
// as testingservice.Config; the rest — the DSN, the listen addresses and the
// logging — is what the library does not own.
type appConfig struct {
	Server struct {
		Bind string `koanf:"bind"`
		// BodyLimit caps a request body in bytes; the zip imports are what needs
		// room.
		BodyLimit int `koanf:"bodylimit"`
	} `koanf:"server"`
	Postgres struct {
		DSN string `koanf:"dsn"`
		// User and Password override the credentials of the DSN. A deployment that
		// keeps them in a secret sets them here rather than splicing them into the
		// DSN, where a `#`, `/` or `?` in a password would truncate the URL.
		User     string `koanf:"user"`
		Password string `koanf:"password"`
		// Schema is created on startup and must match the search_path of the DSN.
		// Empty leaves schema creation to whoever provisioned the database.
		Schema         string `koanf:"schema"`
		MaxConnections int    `koanf:"maxconnections"`
	} `koanf:"postgres"`
	Catalog struct {
		Address string `koanf:"address"`
	} `koanf:"catalog"`
	Engine struct {
		Address string `koanf:"address"`
	} `koanf:"engine"`
	Pagination struct {
		Limit int `koanf:"limit"`
	} `koanf:"pagination"`
	Execution struct {
		Interval time.Duration `koanf:"interval"`
		Workers  int           `koanf:"workers"`
		Lease    time.Duration `koanf:"lease"`
	} `koanf:"execution"`
	Retention struct {
		Age      time.Duration `koanf:"age"`
		Interval time.Duration `koanf:"interval"`
	} `koanf:"retention"`
	Log struct {
		Level  string `koanf:"level"`
		Format string `koanf:"format"`
	} `koanf:"log"`
	Pprof struct {
		Enabled bool   `koanf:"enabled"`
		Bind    string `koanf:"bind"`
	} `koanf:"pprof"`
	Production bool `koanf:"production"`
}

// defaultAppConfig covers only the settings this binary owns. The rest stays
// zero and picks up its default from testingservice.Config.WithDefaults, so no
// value is spelled out in two places.
func defaultAppConfig() appConfig {
	var cfg appConfig
	cfg.Server.Bind = ":8080"
	cfg.Server.BodyLimit = defaultBodyLimit
	cfg.Postgres.Schema = "testing_service"
	cfg.Postgres.MaxConnections = db.DefaultMaxOpenConns
	cfg.Log.Level = "info"
	cfg.Log.Format = "json"
	cfg.Pprof.Bind = ":6060"
	return cfg
}

// serviceSettings maps the file onto what the library reads. Every number left
// at zero is filled in by WithDefaults.
func (cfg appConfig) serviceSettings() testingservice.Config {
	return testingservice.Config{
		CatalogAddress:    cfg.Catalog.Address,
		EngineAddress:     cfg.Engine.Address,
		PollInterval:      cfg.Execution.Interval,
		WorkerCount:       cfg.Execution.Workers,
		LeaseDuration:     cfg.Execution.Lease,
		PaginationLimit:   cfg.Pagination.Limit,
		RetentionAge:      cfg.Retention.Age,
		RetentionInterval: cfg.Retention.Interval,
		Production:        cfg.Production,
	}
}

// loadConfig reads path and lets the environment override it. A missing file is
// not an error: an installation may configure the service entirely through
// QIP_TESTING_* variables.
func loadConfig(path string) (appConfig, error) {
	k := koanf.New(".")
	if err := k.Load(koanffile.Provider(path), koanfyaml.Parser()); err != nil {
		if !errors.Is(err, fs.ErrNotExist) {
			return appConfig{}, fmt.Errorf("read configuration file %s: %w", path, err)
		}
	}
	if err := k.Load(koanfenv.Provider(envPrefix, ".", envKey), nil); err != nil {
		return appConfig{}, fmt.Errorf("read configuration from the environment: %w", err)
	}

	cfg := defaultAppConfig()
	if err := k.Unmarshal("", &cfg); err != nil {
		return appConfig{}, fmt.Errorf("apply configuration: %w", err)
	}
	return cfg, nil
}

// envKey turns QIP_TESTING_POSTGRES_DSN into postgres.dsn.
func envKey(name string) string {
	return strings.ReplaceAll(strings.ToLower(strings.TrimPrefix(name, envPrefix)), "_", ".")
}

// newLogger writes to out; anything but "text" is JSON.
func newLogger(out io.Writer, level, format string) *slog.Logger {
	opts := &slog.HandlerOptions{Level: parseLevel(level)}
	if strings.EqualFold(format, "text") {
		return slog.New(slog.NewTextHandler(out, opts))
	}
	return slog.New(slog.NewJSONHandler(out, opts))
}

func parseLevel(name string) slog.Level {
	var level slog.Level
	if err := level.UnmarshalText([]byte(name)); err != nil {
		return slog.LevelInfo
	}
	return level
}

// newHealthHandler answers the container healthcheck. It reports UP only after a
// database round-trip, because an instance that cannot reach PostgreSQL serves
// nothing.
func newHealthHandler(ping func(context.Context) error) fiber.Handler {
	return func(c *fiber.Ctx) error {
		ctx, cancel := context.WithTimeout(c.UserContext(), healthTimeout)
		defer cancel()
		if err := ping(ctx); err != nil {
			return c.Status(fiber.StatusServiceUnavailable).
				JSON(fiber.Map{"status": "DOWN", "error": err.Error()})
		}
		return c.JSON(fiber.Map{"status": "UP"})
	}
}

// migrationLockKey identifies the advisory lock the migrations are applied
// under. It is derived from the schema, so two services sharing one database do
// not wait on each other.
func migrationLockKey(schema string) int64 {
	hash := fnv.New64a()
	// Hash writes never fail.
	_, _ = hash.Write([]byte(serviceName + " migrations " + schema))
	// Seven bytes of the digest, so the key is a positive bigint without a
	// conversion that could wrap into a negative one.
	var key int64
	for _, b := range hash.Sum(nil)[:7] {
		key = key<<8 | int64(b)
	}
	return key
}

// lockMigrations serializes the migrations across replicas and returns the
// release. A rolling update starts the next replica before this one is done, and
// two instances applying the same migration at once would race on the view it
// recreates.
//
// The lock is a PostgreSQL advisory lock on a connection of its own rather than
// bun's migration lock table: the database drops it when the session ends, so a
// replica that is killed mid-migration leaves nothing behind for the next start
// to clear by hand. Waiting is bounded, since a lock nobody releases is a fault
// to report rather than to hang on.
func lockMigrations(ctx context.Context, bunDB *bun.DB, schema string, logger *slog.Logger) (func(), error) {
	key := migrationLockKey(schema)
	conn, err := bunDB.Conn(ctx)
	if err != nil {
		return nil, fmt.Errorf("open a connection for the migration lock: %w", err)
	}
	release := func() {
		// The caller's context may already be canceled by a shutdown signal, and
		// the lock still has to go back.
		ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), migrationUnlockTimeout)
		defer cancel()
		if _, err := conn.ExecContext(ctx, "select pg_advisory_unlock(?)", key); err != nil {
			logger.Error("Cannot release the migration lock", "error", err)
		}
		if err := conn.Close(); err != nil {
			logger.Error("Cannot close the migration lock connection", "error", err)
		}
	}

	waitCtx, cancel := context.WithTimeout(ctx, migrationLockTimeout)
	defer cancel()
	for attempt := 0; ; attempt++ {
		var locked bool
		if err := conn.QueryRowContext(waitCtx, "select pg_try_advisory_lock(?)", key).Scan(&locked); err != nil {
			_ = conn.Close()
			return nil, fmt.Errorf("lock the migrations: %w", err)
		}
		if locked {
			return release, nil
		}
		if attempt == 0 {
			logger.Info("Waiting for another instance to finish the migrations")
		}
		select {
		case <-waitCtx.Done():
			_ = conn.Close()
			return nil, fmt.Errorf("lock the migrations: %w", waitCtx.Err())
		case <-time.After(migrationLockRetryInterval):
		}
	}
}

// prepareDatabase creates the schema and then applies the migrations. The order
// matters: the migrator writes its bookkeeping table before anything else, and
// that write fails while search_path names a schema that does not exist yet.
//
// Everything here runs under the migration lock, including the schema and the
// bookkeeping table: "create if not exists" is not atomic against a second
// instance running it at the same moment, and both raise a duplicate key on the
// PostgreSQL catalog rather than one of them yielding.
func prepareDatabase(ctx context.Context, bunDB *bun.DB, schema string, logger *slog.Logger) error {
	migrations, err := testingservice.Migrations()
	if err != nil {
		return err
	}
	unlock, err := lockMigrations(ctx, bunDB, schema, logger)
	if err != nil {
		return err
	}
	defer unlock()

	if schema != "" {
		if _, err := bunDB.ExecContext(ctx, "create schema if not exists ?", bun.Ident(schema)); err != nil {
			return fmt.Errorf("create schema %s: %w", schema, err)
		}
	}
	migrator := migrate.NewMigrator(bunDB, migrations)
	if err := migrator.Init(ctx); err != nil {
		return fmt.Errorf("initialize the migrator: %w", err)
	}
	group, err := migrator.Migrate(ctx)
	if err != nil {
		return fmt.Errorf("apply migrations: %w", err)
	}
	if group.IsZero() {
		logger.Info("The database schema is up to date")
	} else {
		logger.Info("Applied migrations", "group", group.String())
	}
	return nil
}

func main() {
	if err := run(); err != nil {
		slog.Error("The testing service stopped", "error", err)
		os.Exit(1)
	}
}

func run() error {
	configPath := flag.String("config", "application.yaml", "path to the configuration file")
	flag.Parse()

	cfg, err := loadConfig(*configPath)
	if err != nil {
		return err
	}
	logger := newLogger(os.Stdout, cfg.Log.Level, cfg.Log.Format)
	slog.SetDefault(logger)

	if cfg.Postgres.DSN == "" {
		return errors.New("postgres.dsn is not set")
	}
	database, err := db.New(db.Options{
		DSN:             cfg.Postgres.DSN,
		User:            cfg.Postgres.User,
		Password:        cfg.Postgres.Password,
		ApplicationName: serviceName,
		MaxOpenConns:    cfg.Postgres.MaxConnections,
	})
	if err != nil {
		return err
	}
	defer func() {
		if err := database.Close(); err != nil {
			logger.Error("Cannot close the database", "error", err)
		}
	}()

	// SIGINT and SIGTERM cancel this context, which unwinds both the executor and
	// the servers below.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	bunDB, err := database.GetBunDb(ctx)
	if err != nil {
		return err
	}
	if err := prepareDatabase(ctx, bunDB, cfg.Postgres.Schema, logger); err != nil {
		return err
	}

	// No CurrentUser: this binary does not authenticate its callers, so every
	// audited write is recorded under dao.DefaultUser. A host that authenticates
	// supplies its own.
	svc, err := testingservice.New(cfg.serviceSettings(), testingservice.Deps{
		DB:         database,
		Logger:     logger,
		HTTPClient: &http.Client{},
	})
	if err != nil {
		return err
	}

	// CaseSensitive matches what the routing rules in front of this service
	// assume: their guards are regular expressions over the path, so a router
	// that answered /Endpoint-Mocks/call as well would slip past them.
	app := fiber.New(fiber.Config{
		Network:               fiber.NetworkTCP,
		DisableStartupMessage: true,
		CaseSensitive:         true,
		BodyLimit:             cfg.Server.BodyLimit,
	})
	app.Use(fiberrecover.New())
	app.Get("/health", newHealthHandler(bunDB.PingContext))
	app.Get("/prometheus", adaptor.HTTPHandler(promhttp.Handler()))
	svc.Mount(app.Group(apiPrefix))
	// The UI and the spec sit under the API prefix, since that is the only path
	// the nginx and Kubernetes rules expose. The spec URL stays relative to the
	// UI page: the proxy inserts its own segments in the middle of the public
	// path, so an absolute URL built from the internal route misses the spec.
	app.Get(swaggerPath, fiberswagger.New(fiberswagger.Config{URL: swaggerDocURL}))

	return serve(ctx, logger, cfg, app, svc.RunExecutor)
}

// serve runs the API, the executor and the optional pprof listener until ctx is
// canceled or one of them fails, then shuts the listeners down and reports the
// first failure.
func serve(
	ctx context.Context,
	logger *slog.Logger,
	cfg appConfig,
	app *fiber.App,
	runExecutor func(context.Context) error,
) error {
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	var (
		running sync.WaitGroup
		once    sync.Once
		failure error
	)
	// The first failure decides the exit status; the rest is the shutdown it set
	// off, so reporting it too would only bury the cause.
	report := func(err error) {
		if err == nil {
			return
		}
		once.Do(func() { failure = err })
		cancel()
	}

	running.Add(2)
	apiListener := listen(&running, logger, app, "the API", cfg.Server.Bind, report,
		"prefix", apiPrefix)
	go func() {
		defer running.Done()
		report(runExecutor(runCtx))
	}()

	var pprofListener *listener
	if cfg.Pprof.Enabled {
		pprofApp := fiber.New(fiber.Config{DisableStartupMessage: true})
		pprofApp.Use(fiberpprof.New())
		running.Add(1)
		pprofListener = listen(&running, logger, pprofApp, "pprof", cfg.Pprof.Bind, report)
	}

	<-runCtx.Done()
	logger.Info("Shutting down")
	apiListener.shutdown(logger)
	pprofListener.shutdown(logger)
	running.Wait()
	return failure
}

// listener is one running fiber app together with the two signals shutdown needs
// to tell "not serving yet" from "gave up already".
type listener struct {
	name    string
	app     *fiber.App
	serving chan struct{}
	stopped chan struct{}
}

// listen starts app on bind in a goroutine of its own and returns the handle
// shutdown works from.
func listen(
	running *sync.WaitGroup,
	logger *slog.Logger,
	app *fiber.App,
	name string,
	bind string,
	report func(error),
	attributes ...any,
) *listener {
	l := &listener{
		name:    name,
		app:     app,
		serving: make(chan struct{}),
		stopped: make(chan struct{}),
	}
	app.Hooks().OnListen(func(fiber.ListenData) error {
		close(l.serving)
		return nil
	})
	go func() {
		defer running.Done()
		defer close(l.stopped)
		logger.Info("Serving", append([]any{"listener", name, "bind", bind}, attributes...)...)
		report(app.Listen(bind))
	}()
	return l
}

// shutdown waits until the listener is serving before shutting it down, and
// returns at once when it gave up instead. Shutting down a server that has not
// bound yet does nothing, after which Listen binds and never returns.
func (l *listener) shutdown(logger *slog.Logger) {
	if l == nil {
		return
	}
	select {
	case <-l.serving:
	case <-l.stopped:
		return
	}
	if err := l.app.ShutdownWithTimeout(shutdownTimeout); err != nil {
		logger.Error("Cannot shut the listener down", "listener", l.name, "error", err)
	}
}
