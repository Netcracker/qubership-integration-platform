package dao

import (
	"context"
	"database/sql"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
)

// fakeRunner stands in for the connection machinery: acquireErr models a database
// that cannot hand out a connection, in which case the handler never runs.
type fakeRunner struct {
	acquireErr  error
	result      any
	silent      bool
	handlerRuns int
	opts        *sql.TxOptions
}

func (r *fakeRunner) Run(ctx context.Context, handler func(ctx context.Context, db bun.IDB) (any, error)) (any, error) {
	if r.acquireErr != nil {
		return nil, r.acquireErr
	}
	if r.silent {
		return nil, nil
	}
	r.handlerRuns++
	if r.result != nil {
		return r.result, nil
	}
	return handler(ctx, nil)
}

func (r *fakeRunner) RunInTx(
	ctx context.Context,
	opts *sql.TxOptions,
	handler func(ctx context.Context, db bun.IDB) (any, error),
) (any, error) {
	r.opts = opts
	return r.Run(ctx, handler)
}

type failingDB struct{ err error }

func (d failingDB) GetBunDb(context.Context) (*bun.DB, error) { return nil, d.err }

func TestRunReturnsTheHandlerResult(t *testing.T) {
	runner := &fakeRunner{}
	rows := &[]TestCaseView{{}}

	result, err := Run(context.Background(), runner, func(context.Context, bun.IDB) (*[]TestCaseView, error) {
		return rows, nil
	})

	require.NoError(t, err)
	assert.Same(t, rows, result)
	assert.Equal(t, 1, runner.handlerRuns)
}

func TestRunPropagatesTheHandlerError(t *testing.T) {
	failure := errors.New("query failed")

	result, err := Run(context.Background(), &fakeRunner{}, func(context.Context, bun.IDB) (*[]TestCaseView, error) {
		return nil, failure
	})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, result)
}

func TestRunReportsAConnectionFailureWithoutRunningTheHandler(t *testing.T) {
	failure := errors.New("no connection")
	runner := &fakeRunner{acquireErr: failure}

	result, err := Run(context.Background(), runner, func(context.Context, bun.IDB) (*[]TestCaseView, error) {
		return &[]TestCaseView{}, nil
	})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, result)
	assert.Equal(t, 0, runner.handlerRuns)
}

// A runner that reports neither a result nor an error used to panic the caller,
// which asserted the type without the comma-ok form.
func TestRunRejectsAResultOfTheWrongType(t *testing.T) {
	runner := &fakeRunner{result: "not a view"}

	result, err := Run(context.Background(), runner, func(context.Context, bun.IDB) (*[]TestCaseView, error) {
		return nil, nil
	})

	require.Error(t, err)
	assert.Nil(t, result)
	assert.ErrorContains(t, err, "want *[]dao.TestCaseView")
}

// An untyped nil with no error is what the source asserted on before checking
// the error, and it panicked.
func TestRunRejectsAnUntypedNilResult(t *testing.T) {
	result, err := Run(context.Background(), &fakeRunner{silent: true}, func(context.Context, bun.IDB) (*[]TestCaseView, error) {
		return &[]TestCaseView{}, nil
	})

	require.Error(t, err)
	assert.Nil(t, result)
	assert.ErrorContains(t, err, "runner returned <nil>")
}

func TestRunKeepsATypedNilResult(t *testing.T) {
	result, err := Run(context.Background(), &fakeRunner{}, func(context.Context, bun.IDB) (*[]TestCaseView, error) {
		return nil, nil
	})

	require.NoError(t, err)
	assert.Nil(t, result)
}

func TestRunInTxForwardsTheTransactionOptions(t *testing.T) {
	runner := &fakeRunner{}
	opts := &sql.TxOptions{Isolation: sql.LevelSerializable, ReadOnly: true}

	count, err := RunInTx(context.Background(), runner, opts, func(context.Context, bun.IDB) (int, error) {
		return 7, nil
	})

	require.NoError(t, err)
	assert.Equal(t, 7, count)
	assert.Same(t, opts, runner.opts)
}

func TestRunInTxPropagatesTheHandlerError(t *testing.T) {
	failure := errors.New("rolled back")

	count, err := RunInTx(context.Background(), &fakeRunner{}, nil, func(context.Context, bun.IDB) (int, error) {
		return 7, failure
	})

	require.ErrorIs(t, err, failure)
	assert.Zero(t, count)
}

func TestDaoRunReportsAnUnavailableDatabase(t *testing.T) {
	failure := errors.New("pool exhausted")
	dao := NewDao(config.Config{}, config.Deps{DB: failingDB{err: failure}})

	result, err := Run(context.Background(), dao, func(context.Context, bun.IDB) (*[]TestCaseView, error) {
		return &[]TestCaseView{}, nil
	})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, result)
}

func TestDaoRunInTxReportsAnUnavailableDatabase(t *testing.T) {
	failure := errors.New("pool exhausted")
	dao := NewDao(config.Config{}, config.Deps{DB: failingDB{err: failure}})

	result, err := RunInTx(context.Background(), dao, nil, func(context.Context, bun.IDB) (*[]TestCaseView, error) {
		return &[]TestCaseView{}, nil
	})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, result)
}

func TestGetDbRejectsAContextThatNeverWentThroughRun(t *testing.T) {
	db, err := GetDb(context.Background())

	require.Error(t, err)
	assert.Nil(t, db)
	assert.ErrorContains(t, err, "dao.Run")
}

func TestWithDbPublishesTheHandleToTheRepositories(t *testing.T) {
	handle := &bun.DB{}

	db, err := GetDb(withDb(context.Background(), handle))

	require.NoError(t, err)
	assert.Same(t, handle, db)
}

func TestWithDbKeepsTheHandleAlreadyInTheContext(t *testing.T) {
	first := &bun.DB{}
	ctx := withDb(withDb(context.Background(), first), &bun.DB{})

	handle, err := GetDb(ctx)

	require.NoError(t, err)
	assert.Same(t, first, handle)
}
