//go:build integration

package db_test

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/testsupport"
)

// The driver sets one read deadline for the whole response to a statement, and
// takes the minimum of it and the context deadline. Its 10-second default is
// therefore a ceiling no caller can raise: the startup migration over a populated
// test_case_runs, the retention sweep and a large export all run past it, and the
// migration failure crash-loops the pod with an i/o timeout that names nothing.
func TestAStatementOutLastingTheDriverDefaultCompletes(t *testing.T) {
	database := testsupport.New(t)
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()

	_, err := database.Bun.NewRaw("select pg_sleep(11)").Exec(ctx)

	require.NoError(t, err, "the socket deadline cut off a statement the caller was still waiting for")
}

// The socket deadline is the backstop for a peer that went away, not the bound on
// a statement. That one stays with the caller.
func TestTheContextDeadlineStillBoundsAStatement(t *testing.T) {
	database := testsupport.New(t)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	started := time.Now()
	_, err := database.Bun.NewRaw("select pg_sleep(30)").Exec(ctx)

	require.Error(t, err)
	assert.Less(t, time.Since(started), 10*time.Second, "the caller waited past its own deadline")
}
