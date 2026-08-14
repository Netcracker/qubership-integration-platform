package services

import (
	"context"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"github.com/uptrace/bun"
	"github.com/uptrace/bun/dialect/pgdialect"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// fakeRunner hands the handler no database handle at all: every repository the
// services reach through is a fake, so none of them looks one up.
type fakeRunner struct {
	acquireErr error
	txCalls    int
}

func (r *fakeRunner) Run(ctx context.Context, handler func(ctx context.Context) (any, error)) (any, error) {
	if r.acquireErr != nil {
		return nil, r.acquireErr
	}
	return handler(ctx)
}

func (r *fakeRunner) RunInTx(ctx context.Context, handler func(ctx context.Context) (any, error)) (any, error) {
	r.txCalls++
	return r.Run(ctx, handler)
}

// fakeWorkNotifier counts the wake-up signals the queue writer sent.
type fakeWorkNotifier struct {
	signals atomic.Int32
}

func (n *fakeWorkNotifier) NotifyWork() { n.signals.Add(1) }

type fakeMatcherParametersRepository struct {
	dao.MatcherParametersRepository

	batches [][]dao.MatcherParameter
}

func (r *fakeMatcherParametersRepository) BulkInsert(_ context.Context, params *[]dao.MatcherParameter) error {
	r.batches = append(r.batches, *params)
	return nil
}

// deleteExpiredCall records the age and the batch size a retention sweep asked
// for, so a test can assert what reached the repository.
type deleteExpiredCall struct {
	age       time.Duration
	batchSize int
}

type fakeTestsRunsRepository struct {
	dao.TestsRunsRepository

	inserted []dao.TestsRun

	mutex sync.Mutex
	// expired holds the runs the sweep may take, oldest first. What each batch
	// leaves behind is what the next one sees, which is how the fake stands in for
	// the batching the real statement does with its LIMIT.
	expired            []uuid.UUID
	deleted            []uuid.UUID
	deleteExpiredCalls []deleteExpiredCall
	deleteExpiredErr   error
}

func (r *fakeTestsRunsRepository) Insert(_ context.Context, testsRun *dao.TestsRun) error {
	r.inserted = append(r.inserted, *testsRun)
	return nil
}

func (r *fakeTestsRunsRepository) DeleteExpired(_ context.Context, age time.Duration, batchSize int) (int, error) {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	r.deleteExpiredCalls = append(r.deleteExpiredCalls, deleteExpiredCall{age: age, batchSize: batchSize})
	if r.deleteExpiredErr != nil {
		return 0, r.deleteExpiredErr
	}
	batch := min(batchSize, len(r.expired))
	r.deleted = append(r.deleted, r.expired[:batch]...)
	r.expired = r.expired[batch:]
	return batch, nil
}

func (r *fakeTestsRunsRepository) sweeps() []deleteExpiredCall {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	return slices.Clone(r.deleteExpiredCalls)
}

func (r *fakeTestsRunsRepository) deletedRuns() []uuid.UUID {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	return slices.Clone(r.deleted)
}

// claimCall records what a claim was made under, so a test can assert that the
// owner token, the session and the lease duration reached the repository.
type claimCall struct {
	owner         uuid.UUID
	sessionID     string
	leaseDuration time.Duration
}

// renewCall records a lease renewal, so a test can assert the duration and the
// owner token reached the repository.
type renewCall struct {
	id            uuid.UUID
	owner         uuid.UUID
	leaseDuration time.Duration
}

type fakeTestCaseRunsRepository struct {
	dao.TestCaseRunsRepository

	views           []dao.TestCaseRunView
	claimable       *dao.TestCaseRun
	claimErr        error
	claims          []claimCall
	inserted        []dao.TestCaseRun
	statusUpdates   []string
	statusSelectors []string
	updated         []dao.TestCaseRun
	updateOwners    []uuid.UUID
	renewals        []renewCall
	reclaimable     int
	reclaimErr      error
	reclaims        int
	leaseOwner      *uuid.UUID
	findAllErr      error
	lastSpecFilter  []model.Filter
}

func (r *fakeTestCaseRunsRepository) FindAll(
	_ context.Context,
	specification *model.SelectionSpecification,
	_ model.SortOptions,
	_ *model.PaginationOptions,
) (*[]dao.TestCaseRunView, error) {
	if r.findAllErr != nil {
		return nil, r.findAllErr
	}
	if specification != nil && specification.Filters != nil {
		r.lastSpecFilter = *specification.Filters
	}
	views := r.views
	return &views, nil
}

func (r *fakeTestCaseRunsRepository) Insert(_ context.Context, testCaseRuns *[]dao.TestCaseRun) error {
	r.inserted = append(r.inserted, *testCaseRuns...)
	return nil
}

// UpdateOwned enforces the fence the real repository enforces in SQL: a write
// naming an owner that no longer holds the lease does not apply.
func (r *fakeTestCaseRunsRepository) UpdateOwned(
	_ context.Context,
	testCaseRun *dao.TestCaseRun,
	owner uuid.UUID,
) error {
	if r.leaseOwner != nil && *r.leaseOwner != owner {
		return dao.ErrLeaseLost
	}
	r.updated = append(r.updated, *testCaseRun)
	r.updateOwners = append(r.updateOwners, owner)
	return nil
}

// UpdateStatus runs the selector against a real bun update query and keeps the
// SQL it produced. Throwing the selector away would leave the pending-only guard
// and the id predicate untested, and a cancel that also killed the running cases
// would still pass.
func (r *fakeTestCaseRunsRepository) UpdateStatus(
	_ context.Context,
	selector func(bun.QueryBuilder) bun.QueryBuilder,
	status string,
) error {
	r.statusUpdates = append(r.statusUpdates, status)
	db := bun.NewDB(nil, pgdialect.New())
	testCaseRun := dao.TestCaseRun{Status: &status}
	query := db.NewUpdate().Model(&testCaseRun).Column("status").ApplyQueryBuilder(selector)
	sql, err := query.AppendQuery(db.Formatter(), nil)
	if err != nil {
		return err
	}
	r.statusSelectors = append(r.statusSelectors, string(sql))
	return nil
}

// lastStatusSelector returns the SQL of the most recent status update.
func (r *fakeTestCaseRunsRepository) lastStatusSelector(t *testing.T) string {
	t.Helper()
	require.NotEmpty(t, r.statusSelectors, "no status update reached the repository")
	return r.statusSelectors[len(r.statusSelectors)-1]
}

func (r *fakeTestCaseRunsRepository) Claim(
	_ context.Context,
	owner uuid.UUID,
	sessionID string,
	leaseDuration time.Duration,
) (*dao.TestCaseRun, error) {
	r.claims = append(r.claims, claimCall{owner: owner, sessionID: sessionID, leaseDuration: leaseDuration})
	if r.claimErr != nil {
		return nil, r.claimErr
	}
	if r.claimable == nil {
		return nil, nil
	}
	status := dao.RunStatusRunning
	claimed := *r.claimable
	claimed.Status = &status
	claimed.SessionID = &sessionID
	claimed.LeaseOwner = &owner
	return &claimed, nil
}

// RenewLease enforces the fence the real repository enforces in SQL.
func (r *fakeTestCaseRunsRepository) RenewLease(
	_ context.Context,
	id uuid.UUID,
	owner uuid.UUID,
	leaseDuration time.Duration,
) error {
	if r.leaseOwner != nil && *r.leaseOwner != owner {
		return dao.ErrLeaseLost
	}
	r.renewals = append(r.renewals, renewCall{id: id, owner: owner, leaseDuration: leaseDuration})
	return nil
}

func (r *fakeTestCaseRunsRepository) ReclaimExpired(context.Context) (int, error) {
	r.reclaims++
	if r.reclaimErr != nil {
		return 0, r.reclaimErr
	}
	return r.reclaimable, nil
}

type fakeTestCaseRunErrorsRepository struct {
	dao.TestCaseRunErrorsRepository

	byTestCaseRun map[uuid.UUID][]dao.ValidationError
	inserted      []dao.ValidationError
	insertOwners  []uuid.UUID
	leaseOwner    *uuid.UUID
	findErr       error
}

func (r *fakeTestCaseRunErrorsRepository) FindByTestCaseRunId(
	_ context.Context,
	id uuid.UUID,
	_ bool,
) (*[]dao.ValidationError, error) {
	validationErrors := r.byTestCaseRun[id]
	return &validationErrors, nil
}

func (r *fakeTestCaseRunErrorsRepository) FindByIds(
	_ context.Context,
	ids []uuid.UUID,
	_ bool,
) (*[]dao.ValidationError, error) {
	if r.findErr != nil {
		return nil, r.findErr
	}
	var validationErrors []dao.ValidationError
	for _, id := range ids {
		validationErrors = append(validationErrors, r.byTestCaseRun[id]...)
	}
	return &validationErrors, nil
}

func (r *fakeTestCaseRunErrorsRepository) InsertOwned(
	_ context.Context,
	validationError *dao.ValidationError,
	owner uuid.UUID,
) (*dao.ValidationError, error) {
	if r.leaseOwner != nil && *r.leaseOwner != owner {
		return nil, dao.ErrLeaseLost
	}
	stored := *validationError
	stored.ID = uuid.New()
	r.inserted = append(r.inserted, stored)
	r.insertOwners = append(r.insertOwners, owner)
	return &stored, nil
}
