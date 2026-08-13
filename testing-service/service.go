// Package testingservice runs the test cases, endpoint mocks and test runs of
// the Qubership Integration Platform.
//
// It works two ways. The binary in cmd/testing-service serves it standalone; a
// host application embeds it by calling New with its own database handle, logger
// and HTTP client, mounting the routes on a router of its own and running the
// executor alongside its other background work.
package testingservice

// The OpenAPI spec is generated from the annotations on (*Controllers).Mount and
// on the handlers around it. --parseInternal reaches the controllers under
// internal/, --parseDependency resolves the embedded bun.BaseModel.
//
//go:generate go run github.com/swaggo/swag/cmd/swag@v1.16.4 init --generalInfo internal/controllers/controllers.go --dir . --parseInternal --parseDependency --output docs --outputTypes go,json,yaml

import (
	"context"
	"errors"
	"sync"

	"github.com/gofiber/fiber/v2"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/controllers"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services"
)

// The public settings and infrastructure types. They are declared in
// internal/config, because this package imports internal/... and Go forbids the
// reverse; the aliases let a caller name them without reaching into internal.
type (
	// Config carries the settings of the service. See config.Config.
	Config = config.Config
	// Deps carries the infrastructure a host supplies. See config.Deps.
	Deps = config.Deps
	// DB hands out the bun handle the repositories run on. See config.DB.
	DB = config.DB
	// CurrentUserFunc resolves the name recorded on audited writes.
	CurrentUserFunc = config.CurrentUserFunc
)

// ErrNoDatabase reports a Deps with no DB. Every operation needs one, so New
// refuses rather than failing on the first request.
var ErrNoDatabase = errors.New("testing service: Deps.DB is required")

// Service is the mounted testing service.
type Service struct {
	deps        config.Deps
	services    *services.Services
	controllers *controllers.Controllers
}

// New wires the repositories, services and controllers over the given settings
// and infrastructure. Unset settings fall back to the defaults in Config.
func New(cfg Config, deps Deps) (*Service, error) {
	if deps.DB == nil {
		return nil, ErrNoDatabase
	}
	cfg = cfg.WithDefaults()
	d := dao.NewDao(cfg, deps)
	svcs := services.NewServices(cfg, deps, d)
	return &Service{
		deps:        deps,
		services:    svcs,
		controllers: controllers.New(cfg, deps, svcs),
	}, nil
}

// Mount registers the routes on router, together with the middleware that puts
// the caller into the request context. The paths are relative to router, so the
// host owns the API prefix.
func (s *Service) Mount(router fiber.Router) {
	s.controllers.Mount(router)
}

// RunExecutor runs the test executor, its lease sweeper and the retention of aged
// test runs until ctx is canceled, then returns nil once they have stopped. A
// canceled context is the ordinary way to shut down, so it is not reported as a
// failure. Shutdown does not wait for the queue to drain: the case a worker was on
// keeps its lease until it expires, and the sweeper hands it out again.
func (s *Service) RunExecutor(ctx context.Context) error {
	ctx = dao.WithCurrentUser(ctx, s.backgroundUser(ctx))

	var running sync.WaitGroup
	running.Add(2)
	go func() {
		defer running.Done()
		s.services.TestExecutionService.Run(ctx)
	}()
	go func() {
		defer running.Done()
		s.services.TestsRunsService.RunRetention(ctx)
	}()
	running.Wait()
	return nil
}

// backgroundUser names the writer of the rows the executor produces. There is no
// request behind them, so a host that supplied no CurrentUser gets the platform
// default.
func (s *Service) backgroundUser(ctx context.Context) string {
	if s.deps.CurrentUser == nil {
		return dao.DefaultUser
	}
	if user := s.deps.CurrentUser(ctx); user != "" {
		return user
	}
	return dao.DefaultUser
}
