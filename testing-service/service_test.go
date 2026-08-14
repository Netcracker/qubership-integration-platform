package testingservice

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
)

var errNoConnection = errors.New("no connection")

// unreachableDB stands in for a host database that is configured but down. Every
// handler that touches storage then answers 500, which is enough to tell a
// registered route from a missing one.
type unreachableDB struct{}

func (unreachableDB) GetBunDb(context.Context) (*bun.DB, error) { return nil, errNoConnection }

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestNewRejectsMissingDatabase(t *testing.T) {
	service, err := New(Config{}, Deps{})
	assert.Nil(t, service)
	assert.ErrorIs(t, err, ErrNoDatabase)
}

func TestNewAcceptsTheAliasedTypes(t *testing.T) {
	var db DB = unreachableDB{}
	var currentUser CurrentUserFunc = func(context.Context) string { return "alice" }

	service, err := New(Config{PaginationLimit: 50}, Deps{DB: db, Logger: discardLogger(), CurrentUser: currentUser})
	require.NoError(t, err)
	require.NotNil(t, service)
}

func TestMountServesTheApiUnderTheGivenPrefix(t *testing.T) {
	service, err := New(Config{}, Deps{DB: unreachableDB{}, Logger: discardLogger()})
	require.NoError(t, err)

	app := fiber.New()
	service.Mount(app.Group("/api/v1"))

	tests := []struct {
		name   string
		target string
		status int
	}{
		{"mounted route", "/api/v1/mode", http.StatusOK},
		{"unmounted prefix", "/mode", http.StatusNotFound},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response, err := app.Test(httptest.NewRequest(http.MethodGet, test.target, nil), 2000)
			require.NoError(t, err)
			defer func() { _ = response.Body.Close() }()
			assert.Equal(t, test.status, response.StatusCode)
		})
	}
}

func TestMountedHandlersReachTheDatabase(t *testing.T) {
	service, err := New(Config{}, Deps{DB: unreachableDB{}, Logger: discardLogger()})
	require.NoError(t, err)

	app := fiber.New()
	service.Mount(app)

	target := "/test-cases/11111111-2222-3333-4444-555555555555"
	response, err := app.Test(httptest.NewRequest(http.MethodGet, target, nil), 2000)
	require.NoError(t, err)
	defer func() { _ = response.Body.Close() }()
	assert.Equal(t, http.StatusInternalServerError, response.StatusCode)
}

func TestRunExecutorStopsWithTheContext(t *testing.T) {
	service, err := New(Config{}, Deps{DB: unreachableDB{}, Logger: discardLogger()})
	require.NoError(t, err)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- service.RunExecutor(ctx) }()

	cancel()
	select {
	case err := <-done:
		assert.NoError(t, err, "a canceled context is an ordinary shutdown")
	case <-time.After(5 * time.Second):
		t.Fatal("RunExecutor did not return after the context was canceled")
	}
}

// A host that names nobody leaves the name empty here; dao.CurrentUser is the
// one place the platform default is substituted, and RunExecutor puts the name
// through it.
func TestBackgroundUser(t *testing.T) {
	tests := []struct {
		name        string
		currentUser CurrentUserFunc
		want        string
		recorded    string
	}{
		{name: "no resolver", want: "", recorded: dao.DefaultUser},
		{
			name:        "resolver returns a name",
			currentUser: func(context.Context) string { return "robot" },
			want:        "robot",
			recorded:    "robot",
		},
		{
			name:        "resolver returns nothing",
			currentUser: func(context.Context) string { return "" },
			want:        "",
			recorded:    dao.DefaultUser,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			service, err := New(Config{}, Deps{
				DB:          unreachableDB{},
				Logger:      discardLogger(),
				CurrentUser: test.currentUser,
			})
			require.NoError(t, err)
			user := service.backgroundUser(context.Background())
			assert.Equal(t, test.want, user)
			assert.Equal(t, test.recorded, dao.CurrentUser(dao.WithCurrentUser(context.Background(), user)))
		})
	}
}
