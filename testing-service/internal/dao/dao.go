// Package dao holds the bun models, the repositories over them, and the query
// helpers the repositories share.
package dao

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"

	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
)

// dbContextKey addresses the bun handle a repository picks up from the context.
type dbContextKey struct{}

type Dao struct {
	db     config.DB
	logger *slog.Logger

	TestsRunsRepository          TestsRunsRepository
	TestCasesRepository          TestCasesRepository
	TestCaseRunsRepository       TestCaseRunsRepository
	TestCaseRunErrorsRepository  TestCaseRunErrorsRepository
	TriggerReferencesRepository  TriggerReferencesRepository
	RequestSettingsRepository    RequestSettingsRepository
	MessagesRepository           MessagesRepository
	HeadersRepository            HeadersRepository
	QueryParametersRepository    QueryParametersRepository
	PathParametersRepository     PathParametersRepository
	MatchersRepository           MatchersRepository
	MatcherParametersRepository  MatcherParametersRepository
	EndpointMocksRepository      EndpointMocksRepository
	EndpointReferencesRepository EndpointReferencesRepository
	ResponseSettingsRepository   ResponseSettingsRepository
}

// NewDao wires the repositories over the database the host supplied. The
// pagination limit comes from cfg, so nothing below reads configuration itself.
func NewDao(cfg config.Config, deps config.Deps) *Dao {
	cfg = cfg.WithDefaults()
	logger := deps.Logger
	if logger == nil {
		logger = slog.Default()
	}
	limit := cfg.PaginationLimit
	return &Dao{
		db:                           deps.DB,
		logger:                       logger,
		TestsRunsRepository:          NewTestsRunsRepository(logger, limit),
		TestCaseRunsRepository:       NewTestCaseRunsRepository(logger, limit),
		TestCasesRepository:          NewTestCasesRepository(logger, limit),
		TestCaseRunErrorsRepository:  NewTestCaseRunErrorsRepository(),
		TriggerReferencesRepository:  NewTriggerReferencesRepository(),
		RequestSettingsRepository:    NewRequestSettingsRepository(),
		MessagesRepository:           NewMessagesRepository(),
		HeadersRepository:            NewHeadersRepository(),
		QueryParametersRepository:    NewQueryParametersRepository(),
		PathParametersRepository:     NewPathParametersRepository(),
		MatchersRepository:           NewMatchersRepository(),
		MatcherParametersRepository:  NewMatcherParametersRepository(),
		EndpointMocksRepository:      NewEndpointMocksRepository(logger, limit),
		EndpointReferencesRepository: NewEndpointReferencesRepository(),
		ResponseSettingsRepository:   NewResponseSettingsRepository(),
	}
}

func (dao *Dao) Run(
	ctx context.Context,
	handler func(ctx context.Context, db bun.IDB) (any, error),
) (any, error) {
	return dao.run(ctx, func(ctx context.Context, conn bun.Conn) (any, error) {
		dbCtx, err := createDbContext(ctx, &conn)
		if err != nil {
			return nil, err
		}
		return handler(dbCtx, conn)
	})
}

func (dao *Dao) RunInTx(
	ctx context.Context,
	opts *sql.TxOptions,
	handler func(ctx context.Context, db bun.IDB) (any, error),
) (any, error) {
	return dao.run(ctx, func(ctx context.Context, conn bun.Conn) (any, error) {
		tx, err := conn.BeginTx(ctx, opts)
		if err != nil {
			return nil, err
		}

		var done bool

		defer func() {
			if !done {
				_ = tx.Rollback()
			}
		}()

		dbCtx, err := createDbContext(ctx, &conn)
		if err != nil {
			return nil, err
		}

		result, err := handler(dbCtx, tx)
		if err != nil {
			return nil, err
		}

		done = true
		return result, tx.Commit()
	})
}

// GetDb returns the handle Run or RunInTx put into ctx.
func GetDb(ctx context.Context) (bun.IDB, error) {
	db, ok := ctx.Value(dbContextKey{}).(bun.IDB)
	if !ok {
		return nil, errors.New("no database handle in the context; call dao.Run or dao.RunInTx first")
	}
	return db, nil
}

func createDbContext(ctx context.Context, db bun.IDB) (context.Context, error) {
	if ctx.Value(dbContextKey{}) != nil {
		return ctx, nil
	}
	return context.WithValue(ctx, dbContextKey{}, db), nil
}

func (dao *Dao) run(ctx context.Context, handler func(ctx context.Context, db bun.Conn) (any, error)) (any, error) {
	db, err := dao.db.GetBunDb(ctx)
	if err != nil {
		return nil, err
	}
	conn, err := db.Conn(ctx)
	if err != nil {
		return nil, err
	}
	defer dao.closeConn(ctx, conn)
	return handler(ctx, conn)
}

func (dao *Dao) closeConn(ctx context.Context, conn bun.Conn) {
	if err := conn.Close(); err != nil {
		dao.logger.ErrorContext(ctx, "Cannot return the connection to the pool", "error", err)
	}
}
