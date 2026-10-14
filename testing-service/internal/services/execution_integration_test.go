//go:build integration

package services_test

import (
	"context"
	"io"
	"log/slog"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/services"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/testsupport"
)

func TestMain(m *testing.M) {
	os.Exit(testsupport.RunMain(m))
}

// stack is the queue side of the service, wired over a real schema.
type stack struct {
	database     *testsupport.Database
	dao          *dao.Dao
	repositories dao.Repositories
	testsRuns    services.TestsRunsService
	testCaseRuns services.TestCaseRunsService
	runErrors    services.TestCaseRunErrorsService
}

func newStack(t *testing.T) *stack {
	t.Helper()
	database := testsupport.NewMigrated(t)
	// testingservice.New is what normalizes these in production; the stack below
	// wires the same services by hand, so it has to do the same.
	cfg := config.Config{}.WithDefaults()
	deps := config.Deps{DB: database.DB, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}.WithDefaults()
	d := dao.NewDao(cfg, deps)
	repositories := d.Repositories
	testCaseRuns := services.NewTestCaseRunsService(cfg, d, repositories)
	return &stack{
		database:     database,
		dao:          d,
		repositories: repositories,
		testsRuns:    services.NewTestsRunsService(cfg, deps.Logger, d, repositories, testCaseRuns, nil),
		testCaseRuns: testCaseRuns,
		runErrors:    services.NewTestCaseRunErrorsService(d, repositories),
	}
}

// seedTestCases inserts count test cases and returns their ids. A test run may
// only be started over test cases that exist.
func (s *stack) seedTestCases(t *testing.T, count int) []uuid.UUID {
	t.Helper()
	ids := make([]uuid.UUID, 0, count)
	for i := 0; i < count; i++ {
		id := uuid.New()
		_, err := s.database.Bun.NewRaw(
			"insert into test_cases (id, name, enabled) values (?, ?, true)", id, "case").Exec(context.Background())
		require.NoError(t, err)
		ids = append(ids, id)
	}
	return ids
}

func (s *stack) seedMatcher(t *testing.T, ownerID uuid.UUID) uuid.UUID {
	t.Helper()
	id := uuid.New()
	_, err := s.database.Bun.NewRaw(
		"insert into matchers (id, owner_id, name, enabled) values (?, ?, ?, true)", id, ownerID, "rule",
	).Exec(context.Background())
	require.NoError(t, err)
	return id
}

func (s *stack) startRun(t *testing.T, testCaseIds []uuid.UUID) uuid.UUID {
	t.Helper()
	id, err := s.testsRuns.StartNewFromEntitiesWithType(context.Background(), &testCaseIds, services.RunSourceTestCases)
	require.NoError(t, err)
	require.NotNil(t, id)
	return *id
}

func (s *stack) exec(t *testing.T, query string, args ...any) {
	t.Helper()
	_, err := s.database.Bun.NewRaw(query, args...).Exec(context.Background())
	require.NoError(t, err)
}

func TestClaimNeverHandsTheSameCaseToTwoWorkers(t *testing.T) {
	s := newStack(t)
	ctx := context.Background()

	const runs, casesPerRun, workers = 4, 5, 8
	for i := 0; i < runs; i++ {
		s.startRun(t, s.seedTestCases(t, casesPerRun))
	}

	var mutex sync.Mutex
	claims := map[uuid.UUID]int{}
	var failures []error
	var timedOut atomic.Bool

	// A wall-clock bound so a stuck claim fails the test instead of hanging it.
	// A worker that runs it out reports so, or the failure below reads as a
	// claim that went missing.
	deadline := time.Now().Add(time.Minute)
	var running sync.WaitGroup
	for i := 0; i < workers; i++ {
		running.Add(1)
		go func() {
			defer running.Done()
			owner := uuid.New()
			for {
				if !time.Now().Before(deadline) {
					timedOut.Store(true)
					return
				}
				mutex.Lock()
				done := len(claims) >= runs*casesPerRun
				mutex.Unlock()
				if done {
					return
				}

				testCaseRun, err := s.testCaseRuns.ClaimNext(ctx, owner, "session")
				if err != nil {
					mutex.Lock()
					failures = append(failures, err)
					mutex.Unlock()
					return
				}
				if testCaseRun == nil {
					// Fewer runs than workers, so a worker with nothing to take
					// waits for one of the busy runs to move on.
					time.Sleep(5 * time.Millisecond)
					continue
				}

				mutex.Lock()
				claims[testCaseRun.ID]++
				mutex.Unlock()

				if err := s.testCaseRuns.Finish(ctx, testCaseRun.ID, owner); err != nil {
					mutex.Lock()
					failures = append(failures, err)
					mutex.Unlock()
					return
				}
			}
		}()
	}
	running.Wait()

	require.Empty(t, failures)
	require.False(t, timedOut.Load(), "the workers ran out of time before every case was claimed")
	require.Len(t, claims, runs*casesPerRun, "every queued case has to be claimed exactly once")
	for id, count := range claims {
		assert.Equalf(t, 1, count, "test case run %s was claimed more than once", id)
	}
}

func TestRunsProgressInParallelWhileTheirCasesStayOrdered(t *testing.T) {
	s := newStack(t)
	ctx := context.Background()
	s.startRun(t, s.seedTestCases(t, 3))
	s.startRun(t, s.seedTestCases(t, 3))

	firstOwner, secondOwner, thirdOwner := uuid.New(), uuid.New(), uuid.New()

	first, err := s.testCaseRuns.ClaimNext(ctx, firstOwner, "first")
	require.NoError(t, err)
	require.NotNil(t, first)
	second, err := s.testCaseRuns.ClaimNext(ctx, secondOwner, "second")
	require.NoError(t, err)
	require.NotNil(t, second)

	assert.NotEqual(t, *first.TestsRunID, *second.TestsRunID, "the two runs have to progress at the same time")
	assert.Equal(t, 1, *first.Ordinal)
	assert.Equal(t, 1, *second.Ordinal)

	// Both runs have a case in flight, so there is nothing left to take even
	// though four cases are still pending.
	third, err := s.testCaseRuns.ClaimNext(ctx, thirdOwner, "third")
	require.NoError(t, err)
	assert.Nil(t, third, "a run may not have two cases running at once")

	require.NoError(t, s.testCaseRuns.Finish(ctx, first.ID, firstOwner))
	next, err := s.testCaseRuns.ClaimNext(ctx, thirdOwner, "third")
	require.NoError(t, err)
	require.NotNil(t, next)
	assert.Equal(t, *first.TestsRunID, *next.TestsRunID)
	assert.Equal(t, 2, *next.Ordinal, "the cases of one run run in ordinal order")
}

func TestALiveLeaseSurvivesTheSweep(t *testing.T) {
	s := newStack(t)
	ctx := context.Background()
	s.startRun(t, s.seedTestCases(t, 1))

	owner := uuid.New()
	claimed, err := s.testCaseRuns.ClaimNext(ctx, owner, "session")
	require.NoError(t, err)
	require.NotNil(t, claimed)

	reclaimed, err := s.testCaseRuns.ReclaimExpired(ctx)
	require.NoError(t, err)
	assert.Equal(t, 0, reclaimed)
	assert.Equal(t, dao.RunStatusRunning, s.statusOf(t, claimed.ID))
	require.NoError(t, s.testCaseRuns.RenewLease(ctx, claimed.ID, owner))
}

func TestAnExpiredLeaseReturnsTheCaseAndClearsItsErrors(t *testing.T) {
	s := newStack(t)
	ctx := context.Background()
	testCaseIds := s.seedTestCases(t, 1)
	matcherID := s.seedMatcher(t, testCaseIds[0])
	s.startRun(t, testCaseIds)

	stalled := uuid.New()
	claimed, err := s.testCaseRuns.ClaimNext(ctx, stalled, "stalled")
	require.NoError(t, err)
	require.NotNil(t, claimed)
	_, err = s.runErrors.AddError(ctx, claimed.ID, stalled, &dao.Matcher{ID: matcherID}, "the first attempt failed")
	require.NoError(t, err)

	// What a worker that stopped reporting leaves behind.
	s.exec(t, "update test_case_runs set lease_until = now() - interval '1 second' where id = ?", claimed.ID)

	count, err := s.testCaseRuns.ReclaimExpired(ctx)
	require.NoError(t, err)
	assert.Equal(t, 1, count)

	reclaimed := s.rowOf(t, claimed.ID)
	assert.Equal(t, dao.RunStatusPending, *reclaimed.Status)
	assert.Nil(t, reclaimed.LeaseOwner)
	assert.Nil(t, reclaimed.LeaseUntil)
	assert.Nil(t, reclaimed.Start, "a reclaimed case has to look like one that was never claimed")

	errorsOfAttempt, err := s.runErrors.FindByTestCaseRunId(ctx, claimed.ID, false)
	require.NoError(t, err)
	assert.Empty(t, *errorsOfAttempt, "the reclaim drops what the abandoned attempt recorded")

	// The stalled worker is fenced out of every write it might still make.
	assert.ErrorIs(t, s.testCaseRuns.RenewLease(ctx, claimed.ID, stalled), dao.ErrLeaseLost)
	assert.ErrorIs(t, s.testCaseRuns.Finish(ctx, claimed.ID, stalled), dao.ErrLeaseLost)
	_, err = s.runErrors.AddError(ctx, claimed.ID, stalled, &dao.Matcher{ID: matcherID}, "too late")
	assert.ErrorIs(t, err, dao.ErrLeaseLost)

	// The second attempt records the same matcher, which is what the unique
	// constraint on (test_case_run_id, matcher_id) would have rejected had the
	// reclaim left the first attempt's errors behind.
	owner := uuid.New()
	retried, err := s.testCaseRuns.ClaimNext(ctx, owner, "retry")
	require.NoError(t, err)
	require.NotNil(t, retried)
	assert.Equal(t, claimed.ID, retried.ID)
	_, err = s.runErrors.AddError(ctx, retried.ID, owner, &dao.Matcher{ID: matcherID}, "the second attempt failed")
	require.NoError(t, err)
	require.NoError(t, s.testCaseRuns.Finish(ctx, retried.ID, owner))
}

// A running case that carries no lease holds `lease_until` null, so
// `lease_until < now()` is null rather than true for it. A sweeper that
// overlooked it would strand it for good: the claim guard keeps its whole test
// run out of the queue, and retention refuses to delete a run with a case still
// in flight.
func TestARunningCaseWithNoLeaseIsSweptRatherThanStranded(t *testing.T) {
	s := newStack(t)
	ctx := context.Background()
	testCaseIds := s.seedTestCases(t, 2)
	matcherID := s.seedMatcher(t, testCaseIds[0])
	testsRunID := s.startRun(t, testCaseIds)

	// What the case of an interrupted predecessor looks like, down to the errors
	// its attempt recorded.
	s.exec(t, `update test_case_runs set status = ?, start = now(), lease_until = null, lease_owner = null
		where tests_run_id = ? and ordinal = 1`, dao.RunStatusRunning, testsRunID)
	var caseIds []uuid.UUID
	require.NoError(t, s.database.Bun.NewRaw(
		"select id from test_case_runs where tests_run_id = ? and ordinal = 1", testsRunID).Scan(ctx, &caseIds))
	require.Len(t, caseIds, 1)
	s.exec(t, "insert into validation_errors (test_case_run_id, matcher_id, message) values (?, ?, ?)",
		caseIds[0], matcherID, "the interrupted attempt failed")

	blocked, err := s.testCaseRuns.ClaimNext(ctx, uuid.New(), "blocked")
	require.NoError(t, err)
	require.Nil(t, blocked, "the claim guard keeps the run out of the queue while that case sits there")

	count, err := s.testCaseRuns.ReclaimExpired(ctx)
	require.NoError(t, err)
	assert.Equal(t, 1, count)

	swept := s.rowOf(t, caseIds[0])
	assert.Equal(t, dao.RunStatusPending, *swept.Status)
	assert.Nil(t, swept.Start)
	errorsOfAttempt, err := s.runErrors.FindByTestCaseRunId(ctx, caseIds[0], false)
	require.NoError(t, err)
	assert.Empty(t, *errorsOfAttempt)

	// The run is back in the queue, and its cases still run in order.
	owner := uuid.New()
	claimed, err := s.testCaseRuns.ClaimNext(ctx, owner, "session")
	require.NoError(t, err)
	require.NotNil(t, claimed)
	assert.Equal(t, caseIds[0], claimed.ID)
	assert.Equal(t, 1, *claimed.Ordinal)
	_, err = s.runErrors.AddError(ctx, claimed.ID, owner, &dao.Matcher{ID: matcherID}, "the second attempt failed")
	require.NoError(t, err)
	require.NoError(t, s.testCaseRuns.Finish(ctx, claimed.ID, owner))
}

func TestRetentionLeavesTheRunsThatStillHaveWorkAlone(t *testing.T) {
	s := newStack(t)
	ctx := context.Background()

	agedAndFinished := s.startRun(t, s.seedTestCases(t, 2))
	agedWithPendingCase := s.startRun(t, s.seedTestCases(t, 1))
	agedWithRunningCase := s.startRun(t, s.seedTestCases(t, 1))
	recent := s.startRun(t, s.seedTestCases(t, 1))

	aged := []uuid.UUID{agedAndFinished, agedWithPendingCase, agedWithRunningCase}
	s.exec(t, "update tests_runs set created_at = now() - interval '48 hours' where id in (?)", bun.In(aged))
	s.exec(t, "update test_case_runs set status = ? where tests_run_id = ?", dao.RunStatusFinished, agedAndFinished)
	s.exec(t, "update test_case_runs set status = ? where tests_run_id = ?", dao.RunStatusRunning, agedWithRunningCase)

	// A validation error on the run that goes, to see the cascades take it.
	var caseIds []uuid.UUID
	require.NoError(t, s.database.Bun.NewRaw(
		"select id from test_case_runs where tests_run_id = ?", agedAndFinished).Scan(ctx, &caseIds))
	require.NotEmpty(t, caseIds)
	s.exec(t, "insert into validation_errors (test_case_run_id, message) values (?, ?)", caseIds[0], "failed")

	deleted, err := dao.RunInTx(ctx, s.dao, func(ctx context.Context) (int, error) {
		return s.repositories.TestsRuns.DeleteExpired(ctx, 24*time.Hour, 500)
	})
	require.NoError(t, err)
	assert.Equal(t, 1, deleted)

	assert.False(t, s.runExists(t, agedAndFinished))
	assert.True(t, s.runExists(t, agedWithPendingCase), "a run with a case still waiting may not be deleted")
	assert.True(t, s.runExists(t, agedWithRunningCase), "a run with a case in flight may not be deleted")
	assert.True(t, s.runExists(t, recent), "a run below the retention age may not be deleted")

	assert.Equal(t, 0, s.count(t, "select count(*) from test_case_runs where tests_run_id = ?", agedAndFinished))
	assert.Equal(t, 0, s.count(t, "select count(*) from validation_errors where test_case_run_id = ?", caseIds[0]))
}

func (s *stack) statusOf(t *testing.T, id uuid.UUID) string {
	t.Helper()
	return *s.rowOf(t, id).Status
}

func (s *stack) rowOf(t *testing.T, id uuid.UUID) dao.TestCaseRun {
	t.Helper()
	var rows []dao.TestCaseRun
	require.NoError(t, s.database.Bun.NewSelect().Model(&rows).Where("id = ?", id).Scan(context.Background()))
	require.Len(t, rows, 1)
	return rows[0]
}

func (s *stack) runExists(t *testing.T, id uuid.UUID) bool {
	t.Helper()
	return s.count(t, "select count(*) from tests_runs where id = ?", id) == 1
}

func (s *stack) count(t *testing.T, query string, args ...any) int {
	t.Helper()
	var counts []int
	require.NoError(t, s.database.Bun.NewRaw(query, args...).Scan(context.Background(), &counts))
	require.Len(t, counts, 1)
	return counts[0]
}
