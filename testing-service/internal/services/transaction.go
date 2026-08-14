package services

import (
	"context"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
)

// runInTx runs handler in a transaction and reports only whether it succeeded.
// The empty struct is the result type on purpose: a handler declared to return
// `any` and a nil result is indistinguishable from a runner that never ran it.
func runInTx(ctx context.Context, runner dao.Runner, handler func(ctx context.Context) error) error {
	_, err := dao.RunInTx(ctx, runner, func(ctx context.Context) (struct{}, error) {
		return struct{}{}, handler(ctx)
	})
	return err
}

// runQuery is runInTx without a transaction, for read-only work.
func runQuery(ctx context.Context, runner dao.Runner, handler func(ctx context.Context) error) error {
	_, err := dao.Run(ctx, runner, func(ctx context.Context) (struct{}, error) {
		return struct{}{}, handler(ctx)
	})
	return err
}
