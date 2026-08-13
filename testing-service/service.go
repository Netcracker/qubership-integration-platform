// Package testingservice runs the test cases, endpoint mocks and test runs of
// the Qubership Integration Platform.
//
// It works two ways. The binary in cmd/testing-service serves it standalone; a
// host application embeds it by calling New with its own database handle, logger
// and HTTP client, mounting the routes on a router of its own and running the
// executor alongside its other background work.
package testingservice

import (
	"context"
	"errors"

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

// RunExecutor runs the test executor until ctx is canceled, then stops it and
// returns nil. A canceled context is the ordinary way to shut down, so it is not
// reported as a failure.
func (s *Service) RunExecutor(ctx context.Context) error {
	s.services.TestExecutionService.Start(dao.WithCurrentUser(ctx, s.backgroundUser(ctx)))
	<-ctx.Done()
	s.services.TestExecutionService.GracefullyStop(context.WithoutCancel(ctx))
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
