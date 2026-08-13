package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	fiberswagger "github.com/gofiber/swagger"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// YAML forbids tabs in indentation, so this literal keeps the spaces that the
// rest of the file replaces with tabs.
// editorconfig-checker-disable
const sampleConfig = `
server:
  bind: ":9090"
postgres:
  dsn: "postgres://user:secret@db:5432/testing?sslmode=disable"
  schema: "from_file"
  maxconnections: 7
catalog:
  address: "http://catalog-from-file:8080"
engine:
  address: "http://engine-from-file:8080"
pagination:
  limit: 55
execution:
  interval: 15s
  workers: 3
  lease: 90s
retention:
  age: 48h
  interval: 30m
log:
  level: debug
  format: text
pprof:
  enabled: true
  bind: ":7070"
production: true
`

// editorconfig-checker-enable

func writeConfig(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "application.yaml")
	require.NoError(t, os.WriteFile(path, []byte(body), 0o600))
	return path
}

func TestLoadConfigReadsEveryKeyFromTheFile(t *testing.T) {
	cfg, err := loadConfig(writeConfig(t, sampleConfig))
	require.NoError(t, err)

	assert.Equal(t, ":9090", cfg.Server.Bind)
	assert.Equal(t, "postgres://user:secret@db:5432/testing?sslmode=disable", cfg.Postgres.DSN)
	assert.Equal(t, "from_file", cfg.Postgres.Schema)
	assert.Equal(t, 7, cfg.Postgres.MaxConnections)
	assert.Equal(t, "http://catalog-from-file:8080", cfg.Catalog.Address)
	assert.Equal(t, "http://engine-from-file:8080", cfg.Engine.Address)
	assert.Equal(t, 55, cfg.Pagination.Limit)
	assert.Equal(t, 15*time.Second, cfg.Execution.Interval)
	assert.Equal(t, 3, cfg.Execution.Workers)
	assert.Equal(t, 90*time.Second, cfg.Execution.Lease)
	assert.Equal(t, 48*time.Hour, cfg.Retention.Age)
	assert.Equal(t, 30*time.Minute, cfg.Retention.Interval)
	assert.Equal(t, "debug", cfg.Log.Level)
	assert.Equal(t, "text", cfg.Log.Format)
	assert.True(t, cfg.Pprof.Enabled)
	assert.Equal(t, ":7070", cfg.Pprof.Bind)
	assert.True(t, cfg.Production)
}

func TestLoadConfigKeepsDefaultsForKeysTheFileOmits(t *testing.T) {
	cfg, err := loadConfig(writeConfig(t, "postgres:\n  dsn: \"postgres://db/testing\"\n"))
	require.NoError(t, err)

	defaults := defaultAppConfig()
	assert.Equal(t, defaults.Server.Bind, cfg.Server.Bind)
	assert.Equal(t, defaults.Postgres.Schema, cfg.Postgres.Schema)
	assert.Equal(t, defaults.Postgres.MaxConnections, cfg.Postgres.MaxConnections)
	assert.Equal(t, defaults.Log.Level, cfg.Log.Level)
	assert.Equal(t, defaults.Pprof.Bind, cfg.Pprof.Bind)
	assert.False(t, cfg.Pprof.Enabled)
	// The library owns these; they stay zero here and WithDefaults fills them in.
	assert.Zero(t, cfg.Execution.Workers)
	assert.Zero(t, cfg.Retention.Age)
}

func TestLoadConfigFallsBackToDefaultsWhenTheFileIsMissing(t *testing.T) {
	cfg, err := loadConfig(filepath.Join(t.TempDir(), "absent.yaml"))
	require.NoError(t, err)
	assert.Equal(t, defaultAppConfig(), cfg)
}

func TestLoadConfigReportsAMalformedFile(t *testing.T) {
	_, err := loadConfig(writeConfig(t, "server: [::\n"))
	require.Error(t, err)
	assert.Contains(t, err.Error(), "read configuration file")
}

func TestEnvironmentOverridesTheFile(t *testing.T) {
	t.Setenv("QIP_TESTING_POSTGRES_DSN", "postgres://env-host:5432/testing")
	t.Setenv("QIP_TESTING_POSTGRES_MAXCONNECTIONS", "32")
	t.Setenv("QIP_TESTING_EXECUTION_INTERVAL", "45s")
	t.Setenv("QIP_TESTING_EXECUTION_WORKERS", "9")
	t.Setenv("QIP_TESTING_PPROF_ENABLED", "false")
	t.Setenv("QIP_TESTING_PRODUCTION", "false")

	cfg, err := loadConfig(writeConfig(t, sampleConfig))
	require.NoError(t, err)

	assert.Equal(t, "postgres://env-host:5432/testing", cfg.Postgres.DSN)
	assert.Equal(t, 32, cfg.Postgres.MaxConnections)
	assert.Equal(t, 45*time.Second, cfg.Execution.Interval)
	assert.Equal(t, 9, cfg.Execution.Workers)
	assert.False(t, cfg.Pprof.Enabled)
	assert.False(t, cfg.Production)
	// Keys the environment left alone keep the value from the file.
	assert.Equal(t, ":9090", cfg.Server.Bind)
	assert.Equal(t, "from_file", cfg.Postgres.Schema)
}

func TestEnvironmentConfiguresTheServiceWithoutAFile(t *testing.T) {
	t.Setenv("QIP_TESTING_POSTGRES_DSN", "postgres://env-only:5432/testing")
	t.Setenv("QIP_TESTING_SERVER_BIND", ":8181")

	cfg, err := loadConfig(filepath.Join(t.TempDir(), "absent.yaml"))
	require.NoError(t, err)

	assert.Equal(t, "postgres://env-only:5432/testing", cfg.Postgres.DSN)
	assert.Equal(t, ":8181", cfg.Server.Bind)
	assert.Equal(t, defaultAppConfig().Postgres.Schema, cfg.Postgres.Schema)
}

func TestVariablesWithoutThePrefixAreIgnored(t *testing.T) {
	t.Setenv("POSTGRES_DSN", "postgres://unprefixed:5432/testing")

	cfg, err := loadConfig(filepath.Join(t.TempDir(), "absent.yaml"))
	require.NoError(t, err)
	assert.Empty(t, cfg.Postgres.DSN)
}

func TestEnvKey(t *testing.T) {
	assert.Equal(t, "postgres.dsn", envKey("QIP_TESTING_POSTGRES_DSN"))
	assert.Equal(t, "execution.workers", envKey("QIP_TESTING_EXECUTION_WORKERS"))
	assert.Equal(t, "production", envKey("QIP_TESTING_PRODUCTION"))
}

func TestServiceSettingsCarryTheFileOverAndDefaultTheRest(t *testing.T) {
	cfg, err := loadConfig(writeConfig(t, sampleConfig))
	require.NoError(t, err)

	settings := cfg.serviceSettings()
	assert.Equal(t, "http://catalog-from-file:8080", settings.CatalogAddress)
	assert.Equal(t, "http://engine-from-file:8080", settings.EngineAddress)
	assert.Equal(t, 15*time.Second, settings.PollInterval)
	assert.Equal(t, 3, settings.WorkerCount)
	assert.Equal(t, 90*time.Second, settings.LeaseDuration)
	assert.Equal(t, 55, settings.PaginationLimit)
	assert.Equal(t, 48*time.Hour, settings.RetentionAge)
	assert.Equal(t, 30*time.Minute, settings.RetentionInterval)
	assert.True(t, settings.Production)
	assert.True(t, settings.RetentionEnabled())
}

func TestUnsetSettingsFallBackToTheLibraryDefaults(t *testing.T) {
	cfg, err := loadConfig(filepath.Join(t.TempDir(), "absent.yaml"))
	require.NoError(t, err)

	settings := cfg.serviceSettings().WithDefaults()
	assert.NotEmpty(t, settings.CatalogAddress)
	assert.NotEmpty(t, settings.EngineAddress)
	assert.Positive(t, settings.PollInterval)
	assert.Positive(t, settings.WorkerCount)
	assert.Positive(t, settings.LeaseDuration)
	assert.Positive(t, settings.PaginationLimit)
	// Retention stays off until an age is named.
	assert.False(t, settings.RetentionEnabled())
}

func TestTheShippedConfigurationIsUsable(t *testing.T) {
	cfg, err := loadConfig(filepath.Join("..", "..", "application.yaml"))
	require.NoError(t, err)

	assert.NotEmpty(t, cfg.Postgres.DSN)
	assert.NotEmpty(t, cfg.Postgres.Schema)
	assert.Contains(t, cfg.Postgres.DSN, "search_path="+cfg.Postgres.Schema,
		"the DSN must point search_path at the schema the binary creates")
	assert.Equal(t, ":8080", cfg.Server.Bind, "the container serves on 8080")
	assert.False(t, cfg.Pprof.Enabled, "pprof stays off unless it is turned on")
}

func healthResponse(t *testing.T, ping func(context.Context) error) (int, map[string]string) {
	t.Helper()
	app := fiber.New(fiber.Config{DisableStartupMessage: true})
	app.Get("/health", newHealthHandler(ping))

	resp, err := app.Test(httptest.NewRequest(http.MethodGet, "/health", nil))
	require.NoError(t, err)
	defer func() { _ = resp.Body.Close() }()

	body, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	var decoded map[string]string
	require.NoError(t, json.Unmarshal(body, &decoded))
	return resp.StatusCode, decoded
}

func TestHealthReportsUpWhenTheDatabaseAnswers(t *testing.T) {
	status, body := healthResponse(t, func(context.Context) error { return nil })
	assert.Equal(t, http.StatusOK, status)
	assert.Equal(t, "UP", body["status"])
}

func TestHealthReportsDownWhenTheDatabaseDoesNot(t *testing.T) {
	status, body := healthResponse(t, func(context.Context) error {
		return errors.New("connection refused")
	})
	assert.Equal(t, http.StatusServiceUnavailable, status)
	assert.Equal(t, "DOWN", body["status"])
	assert.Contains(t, body["error"], "connection refused")
}

func TestHealthBoundsTheDatabaseRoundTrip(t *testing.T) {
	var deadlineSet bool
	status, _ := healthResponse(t, func(ctx context.Context) error {
		_, deadlineSet = ctx.Deadline()
		return nil
	})
	assert.Equal(t, http.StatusOK, status)
	assert.True(t, deadlineSet, "the ping must not outlive the healthcheck")
}

// swaggerRequest mounts the swagger route the way run does and asks for one path
// under it.
func swaggerRequest(t *testing.T, path string) (*http.Response, string) {
	t.Helper()
	app := fiber.New(fiber.Config{DisableStartupMessage: true})
	app.Get(swaggerPath, fiberswagger.HandlerDefault)

	resp, err := app.Test(httptest.NewRequest(http.MethodGet, path, nil))
	require.NoError(t, err)
	defer func() { _ = resp.Body.Close() }()

	body, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	return resp, string(body)
}

func TestSwaggerServesTheGeneratedSpec(t *testing.T) {
	resp, body := swaggerRequest(t, "/api/v1/swagger/doc.json")
	require.Equal(t, http.StatusOK, resp.StatusCode)

	var spec struct {
		Info struct {
			Title       string `json:"title"`
			Description string `json:"description"`
		} `json:"info"`
		Paths       map[string]any `json:"paths"`
		Definitions map[string]any `json:"definitions"`
	}
	require.NoError(t, json.Unmarshal([]byte(body), &spec))

	assert.Equal(t, "Testing Service API", spec.Info.Title)
	assert.Equal(t, "API of the testing service of the Qubership Integration Platform.", spec.Info.Description)
	// The paths are the public ones, so they carry the prefix the binary mounts under.
	assert.Contains(t, spec.Paths, "/api/v1/test-cases")
	assert.Contains(t, spec.Paths, "/api/v1/endpoint-mocks/call")
	assert.Contains(t, spec.Paths, "/api/v1/tests-runs/create")
	assert.Contains(t, spec.Definitions, "TestCase")
	assert.Contains(t, spec.Definitions, "ErrorMessage")
}

func TestSwaggerServesTheUI(t *testing.T) {
	resp, body := swaggerRequest(t, "/api/v1/swagger/index.html")

	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Contains(t, resp.Header.Get(fiber.HeaderContentType), fiber.MIMETextHTML)
	// The page must fetch the spec from the same prefix, or nginx never sees it.
	assert.Contains(t, body, "/api/v1/swagger/doc.json")
}

func TestSwaggerRedirectsToTheIndex(t *testing.T) {
	resp, _ := swaggerRequest(t, "/api/v1/swagger/")

	assert.Equal(t, http.StatusMovedPermanently, resp.StatusCode)
	assert.Equal(t, "/api/v1/swagger/index.html", resp.Header.Get(fiber.HeaderLocation))
}

func TestParseLevel(t *testing.T) {
	assert.Equal(t, slog.LevelDebug, parseLevel("debug"))
	assert.Equal(t, slog.LevelWarn, parseLevel("WARN"))
	// An unreadable level must not silence the service.
	assert.Equal(t, slog.LevelInfo, parseLevel("chatty"))
	assert.Equal(t, slog.LevelInfo, parseLevel(""))
}

func TestNewLoggerPicksTheHandlerFromTheFormat(t *testing.T) {
	assert.NotNil(t, newLogger("info", "text"))
	assert.NotNil(t, newLogger("info", "json"))
	assert.NotNil(t, newLogger("info", ""))
}
