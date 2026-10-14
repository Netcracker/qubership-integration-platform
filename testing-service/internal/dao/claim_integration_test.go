//go:build integration

package dao_test

import (
	"context"
	"io"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/testsupport"
)

func TestMain(m *testing.M) {
	os.Exit(testsupport.RunMain(m))
}

// queue is one migrated schema with the repositories wired over it.
type queue struct {
	database   *testsupport.Database
	dao        *dao.Dao
	leaseUntil time.Duration
}

func newQueue(t *testing.T) *queue {
	t.Helper()
	database := testsupport.NewMigrated(t)
	cfg := config.Config{}.WithDefaults()
	deps := config.Deps{DB: database.DB, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}.WithDefaults()
	return &queue{database: database, dao: dao.NewDao(cfg, deps), leaseUntil: cfg.LeaseDuration}
}

func (q *queue) exec(t *testing.T, query string, args ...any) {
	t.Helper()
	_, err := q.database.Bun.NewRaw(query, args...).Exec(context.Background())
	require.NoError(t, err)
}

// seedRun queues a test run with the given number of pending cases and returns
// its id. The cases carry no test case, which nothing on the claim path reads.
func (q *queue) seedRun(t *testing.T, cases int) uuid.UUID {
	t.Helper()
	testsRunID := uuid.New()
	q.exec(t, "insert into tests_runs (id, created_at) values (?, now())", testsRunID)
	for ordinal := 1; ordinal <= cases; ordinal++ {
		q.exec(t, "insert into test_case_runs (id, tests_run_id, ordinal, status) values (?, ?, ?, ?)",
			uuid.New(), testsRunID, ordinal, dao.RunStatusPending)
	}
	return testsRunID
}

func (q *queue) claim(t *testing.T, owner uuid.UUID, sessionID string) *dao.TestCaseRun {
	t.Helper()
	claimed, err := dao.RunInTx(context.Background(), q.dao, func(ctx context.Context) (*dao.TestCaseRun, error) {
		return q.dao.Repositories.TestCaseRuns.Claim(ctx, owner, sessionID, q.leaseUntil)
	})
	require.NoError(t, err)
	return claimed
}

// A test run runs its cases one at a time, and that has to hold even when the
// first statement of the claim passes a run it should not.
//
// The statement reads its guards under READ COMMITTED against the snapshot it
// started with, and the run row it locks is never updated, so PostgreSQL runs no
// EvalPlanQual recheck of them behind the lock. A worker whose statement started
// before another worker committed a claim on the same run therefore sees no
// running case, and locks the run once the commit releases it. The cursor below
// is that worker: it fixes the snapshot at declare time and locks its rows only
// when it is fetched.
func TestASecondWorkerCannotStartACaseOfARunThatIsAlreadyRunningOne(t *testing.T) {
	q := newQueue(t)
	ctx := context.Background()
	testsRunID := q.seedRun(t, 2)

	statement, err := dao.ClaimTestsRunStatement(q.database.Bun)
	require.NoError(t, err)

	connection, err := q.database.Bun.Conn(ctx)
	require.NoError(t, err)
	defer func() { assert.NoError(t, connection.Close()) }()
	stale, err := connection.BeginTx(ctx, nil)
	require.NoError(t, err)
	defer func() { _ = stale.Rollback() }()
	_, err = stale.NewRaw("declare claim cursor for " + statement).Exec(ctx)
	require.NoError(t, err, "the first statement of the claim has to run through a cursor")

	// The other worker takes the first case and commits, after the snapshot the
	// cursor holds and before the cursor is fetched.
	first := q.claim(t, uuid.New(), "first")
	require.NotNil(t, first)
	require.Equal(t, 1, *first.Ordinal)

	var testsRunIds []uuid.UUID
	require.NoError(t, stale.NewRaw("fetch all from claim").Scan(ctx, &testsRunIds))
	require.Equal(t, []uuid.UUID{testsRunID}, testsRunIds,
		"the stale snapshot is the premise of this test: the first statement has to hand out the run")

	taken, err := dao.ClaimTestCaseRun(ctx, stale, testsRunID, uuid.New(), "second", time.Minute)
	require.NoError(t, err)
	assert.Nil(t, taken, "a run may not have two cases running at once")
	assert.Equal(t, 1, q.runningCases(t, testsRunID))
}

func (q *queue) runningCases(t *testing.T, testsRunID uuid.UUID) int {
	t.Helper()
	var counts []int
	require.NoError(t, q.database.Bun.NewRaw(
		"select count(*) from test_case_runs where tests_run_id = ? and status = ?",
		testsRunID, dao.RunStatusRunning,
	).Scan(context.Background(), &counts))
	require.Len(t, counts, 1)
	return counts[0]
}

// The guard may not cost a run its own next case: once the case in flight is
// over, the claim hands out the one after it.
func TestTheNextCaseIsClaimedOnceTheRunningOneIsOver(t *testing.T) {
	q := newQueue(t)
	testsRunID := q.seedRun(t, 2)

	owner := uuid.New()
	first := q.claim(t, owner, "first")
	require.NotNil(t, first)
	assert.Equal(t, 1, *first.Ordinal)

	q.exec(t, "update test_case_runs set status = ? where id = ?", dao.RunStatusFinished, first.ID)

	second := q.claim(t, uuid.New(), "second")
	require.NotNil(t, second)
	assert.Equal(t, testsRunID, *second.TestsRunID)
	assert.Equal(t, 2, *second.Ordinal)
}
