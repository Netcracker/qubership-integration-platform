// Command testing-service runs the testing service as a standalone process. It
// owns everything the library deliberately leaves to its host: the database
// connection, the migrations, health, metrics, pprof and the API prefix.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
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
	// defaultUser is recorded on every audited write. This binary does not
	// authenticate its callers; a host that does supplies its own CurrentUser.
	defaultUser = "developer"
	// envPrefix selects the environment variables that override the file.
	envPrefix       = "QIP_TESTING_"
	healthTimeout   = 3 * time.Second
	shutdownTimeout = 10 * time.Second
)

// appConfig is the settings of the process. Most of it is handed to the library
// as testingservice.Config; the rest — the DSN, the listen addresses and the
// logging — is what the library does not own.
type appConfig struct {
	Server struct {
		Bind string `koanf:"bind"`
	} `koanf:"server"`
	Postgres struct {
		DSN string `koanf:"dsn"`
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

func newLogger(level, format string) *slog.Logger {
	opts := &slog.HandlerOptions{Level: parseLevel(level)}
	if strings.EqualFold(format, "text") {
		return slog.New(slog.NewTextHandler(os.Stdout, opts))
	}
	return slog.New(slog.NewJSONHandler(os.Stdout, opts))
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

// prepareDatabase creates the schema and then applies the migrations. The order
// matters: the migrator writes its bookkeeping table before anything else, and
// that write fails while search_path names a schema that does not exist yet.
func prepareDatabase(ctx context.Context, bunDB *bun.DB, schema string, logger *slog.Logger) error {
	if schema != "" {
		if _, err := bunDB.ExecContext(ctx, "create schema if not exists ?", bun.Ident(schema)); err != nil {
			return fmt.Errorf("create schema %s: %w", schema, err)
		}
	}
	migrations, err := testingservice.Migrations()
	if err != nil {
		return err
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
	logger := newLogger(cfg.Log.Level, cfg.Log.Format)
	slog.SetDefault(logger)

	if cfg.Postgres.DSN == "" {
		return errors.New("postgres.dsn is not set")
	}
	database, err := db.New(db.Options{
		DSN:             cfg.Postgres.DSN,
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

	svc, err := testingservice.New(cfg.serviceSettings(), testingservice.Deps{
		DB:          database,
		Logger:      logger,
		HTTPClient:  &http.Client{},
		CurrentUser: func(context.Context) string { return defaultUser },
	})
	if err != nil {
		return err
	}

	app := fiber.New(fiber.Config{Network: fiber.NetworkTCP, DisableStartupMessage: true})
	app.Use(fiberrecover.New())
	app.Get("/health", newHealthHandler(bunDB.PingContext))
	app.Get("/prometheus", adaptor.HTTPHandler(promhttp.Handler()))
	svc.Mount(app.Group(apiPrefix))
	// The UI and the spec sit under the API prefix, since that is the only path
	// the nginx and Kubernetes rules expose.
	app.Get(swaggerPath, fiberswagger.HandlerDefault)

	return serve(ctx, logger, cfg, app, svc)
}

// serve runs the API, the executor and the optional pprof listener until ctx is
// canceled or one of them fails, then shuts the listeners down and reports the
// first failure.
func serve(ctx context.Context, logger *slog.Logger, cfg appConfig, app *fiber.App, svc *testingservice.Service) error {
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
	go func() {
		defer running.Done()
		logger.Info("Serving the API", "bind", cfg.Server.Bind, "prefix", apiPrefix)
		report(app.Listen(cfg.Server.Bind))
	}()
	go func() {
		defer running.Done()
		report(svc.RunExecutor(runCtx))
	}()

	var pprofApp *fiber.App
	if cfg.Pprof.Enabled {
		pprofApp = fiber.New(fiber.Config{DisableStartupMessage: true})
		pprofApp.Use(fiberpprof.New())
		running.Add(1)
		go func() {
			defer running.Done()
			logger.Info("Serving pprof", "bind", cfg.Pprof.Bind)
			report(pprofApp.Listen(cfg.Pprof.Bind))
		}()
	}

	<-runCtx.Done()
	logger.Info("Shutting down")
	if err := app.ShutdownWithTimeout(shutdownTimeout); err != nil {
		logger.Error("Cannot shut the API down", "error", err)
	}
	if pprofApp != nil {
		if err := pprofApp.ShutdownWithTimeout(shutdownTimeout); err != nil {
			logger.Error("Cannot shut pprof down", "error", err)
		}
	}
	running.Wait()
	return failure
}
