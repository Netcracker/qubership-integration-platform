package dao

import (
	"context"
	"errors"
	"fmt"

	"github.com/uptrace/bun"
)

// dbContextKey addresses the bun handle a repository picks up from the context.
type dbContextKey struct{}

// Runner acquires a database handle and passes it to a handler through the
// context. Services take a Runner together with the repository interfaces:
// faking the runner alone still leaves the repositories issuing real queries.
type Runner interface {
	Run(ctx context.Context, handler func(ctx context.Context) (any, error)) (any, error)
	RunInTx(ctx context.Context, handler func(ctx context.Context) (any, error)) (any, error)
}

var _ Runner = (*Dao)(nil)

// Run puts a connection into the context and returns the handler's result. Go
// forbids type parameters on methods, so the typed entry point is a function
// over Runner.
func Run[T any](ctx context.Context, runner Runner, handler func(ctx context.Context) (T, error)) (T, error) {
	result, err := runner.Run(ctx, eraseHandler(handler))
	return typedResult[T](result, err)
}

// RunInTx is Run inside a transaction, committed once handler returns no error.
func RunInTx[T any](ctx context.Context, runner Runner, handler func(ctx context.Context) (T, error)) (T, error) {
	result, err := runner.RunInTx(ctx, eraseHandler(handler))
	return typedResult[T](result, err)
}

func eraseHandler[T any](handler func(ctx context.Context) (T, error)) func(context.Context) (any, error) {
	return func(ctx context.Context) (any, error) {
		return handler(ctx)
	}
}

// typedResult restores T. The comma-ok form is the point: a runner that fails
// before the handler runs returns an untyped nil, which a bare assertion panics on.
func typedResult[T any](result any, err error) (T, error) {
	var zero T
	if err != nil {
		return zero, err
	}
	value, ok := result.(T)
	if !ok {
		return zero, fmt.Errorf("runner returned %T, want %T", result, zero)
	}
	return value, nil
}

func (dao *Dao) Run(ctx context.Context, handler func(ctx context.Context) (any, error)) (any, error) {
	return dao.run(ctx, func(ctx context.Context, conn bun.Conn) (any, error) {
		return handler(withDb(ctx, conn))
	})
}

func (dao *Dao) RunInTx(ctx context.Context, handler func(ctx context.Context) (any, error)) (any, error) {
	return dao.run(ctx, func(ctx context.Context, conn bun.Conn) (any, error) {
		// The database default isolation level, read-write: the zero TxOptions,
		// which BeginTx treats exactly as a nil one.
		tx, err := conn.BeginTx(ctx, nil)
		if err != nil {
			return nil, err
		}

		var done bool

		defer func() {
			if !done {
				_ = tx.Rollback()
			}
		}()

		result, err := handler(replaceDb(ctx, tx))
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

// withDb carries db in ctx, keeping the handle an outer Run already put there so
// that a read nested in a transaction joins it instead of leaving it.
func withDb(ctx context.Context, db bun.IDB) context.Context {
	if ctx.Value(dbContextKey{}) != nil {
		return ctx
	}
	return replaceDb(ctx, db)
}

// replaceDb carries db in ctx whatever was already there. A transaction takes
// this route rather than withDb: the repositories read the handle from the
// context, so keeping an outer one would issue their statements outside the
// transaction the call is about to commit, and the commit would apply nothing.
func replaceDb(ctx context.Context, db bun.IDB) context.Context {
	return context.WithValue(ctx, dbContextKey{}, db)
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
