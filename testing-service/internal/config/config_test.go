package config

import (
	"context"
	"log/slog"
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/uptrace/bun"
)

func TestWithDefaultsFillsAnEmptyConfig(t *testing.T) {
	got := Config{}.WithDefaults()

	assert.Equal(t, DefaultCatalogAddress, got.CatalogAddress)
	assert.Equal(t, DefaultEngineAddress, got.EngineAddress)
	assert.Equal(t, DefaultPollInterval, got.PollInterval)
	assert.Equal(t, DefaultWorkerCount, got.WorkerCount)
	assert.Equal(t, DefaultLeaseDuration, got.LeaseDuration)
	assert.Equal(t, DefaultPaginationLimit, got.PaginationLimit)
	assert.Equal(t, DefaultRetentionInterval, got.RetentionInterval)
	assert.Zero(t, got.RetentionAge, "a host that named no age has not asked for anything to be deleted")
	assert.False(t, got.Production)
}

func TestRetentionIsOffUntilAnAgeIsConfigured(t *testing.T) {
	assert.False(t, Config{}.WithDefaults().RetentionEnabled())
	assert.False(t, Config{RetentionAge: -time.Hour}.WithDefaults().RetentionEnabled())
	assert.True(t, Config{RetentionAge: time.Hour}.WithDefaults().RetentionEnabled())
}

func TestWithDefaultsKeepsSuppliedValues(t *testing.T) {
	cfg := Config{
		CatalogAddress:    "http://catalog.test:9000",
		EngineAddress:     "http://engine.test:9001",
		PollInterval:      time.Second,
		WorkerCount:       16,
		LeaseDuration:     5 * time.Minute,
		PaginationLimit:   500,
		RetentionAge:      30 * 24 * time.Hour,
		RetentionInterval: 15 * time.Minute,
		Production:        true,
	}

	assert.Equal(t, cfg, cfg.WithDefaults())
}

func TestWithDefaultsTreatsNonPositiveNumbersAsUnset(t *testing.T) {
	cfg := Config{
		PollInterval:      -time.Second,
		WorkerCount:       -1,
		LeaseDuration:     -time.Minute,
		PaginationLimit:   -20,
		RetentionInterval: -time.Hour,
	}

	got := cfg.WithDefaults()

	assert.Equal(t, DefaultPollInterval, got.PollInterval)
	assert.Equal(t, DefaultWorkerCount, got.WorkerCount)
	assert.Equal(t, DefaultLeaseDuration, got.LeaseDuration)
	assert.Equal(t, DefaultPaginationLimit, got.PaginationLimit)
	assert.Equal(t, DefaultRetentionInterval, got.RetentionInterval)
}

func TestWithDefaultsLeavesTheReceiverAlone(t *testing.T) {
	cfg := Config{}

	filled := cfg.WithDefaults()

	assert.Equal(t, Config{}, cfg)
	assert.NotEqual(t, cfg, filled)
}

type stubDB struct{}

func (stubDB) GetBunDb(context.Context) (*bun.DB, error) { return nil, nil }

func TestDepsAcceptTheDeclaredImplementations(t *testing.T) {
	deps := Deps{
		DB:          stubDB{},
		Logger:      slog.Default(),
		HTTPClient:  http.DefaultClient,
		CurrentUser: func(context.Context) string { return "developer" },
	}

	assert.Equal(t, "developer", deps.CurrentUser(context.Background()))
}
