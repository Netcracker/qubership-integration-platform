package services

import (
	"context"
	"database/sql"
	"time"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// fakeRunner hands the handler no database handle at all: every repository the
// services reach through is a fake, so none of them looks one up.
type fakeRunner struct {
	acquireErr error
	txCalls    int
}

func (r *fakeRunner) Run(ctx context.Context, handler func(ctx context.Context, db bun.IDB) (any, error)) (any, error) {
	if r.acquireErr != nil {
		return nil, r.acquireErr
	}
	return handler(ctx, nil)
}

func (r *fakeRunner) RunInTx(
	ctx context.Context,
	_ *sql.TxOptions,
	handler func(ctx context.Context, db bun.IDB) (any, error),
) (any, error) {
	r.txCalls++
	return r.Run(ctx, handler)
}

type fakeEndpointMocksRepository struct {
	dao.EndpointMocksRepository

	mocks       []dao.EndpointMock
	findAllErr  error
	lastFilters []model.Filter
}

func (r *fakeEndpointMocksRepository) FindAll(
	_ context.Context,
	specification *model.SelectionSpecification,
	_ model.SortOptions,
	_ *model.PaginationOptions,
	_ bool,
) (*[]dao.EndpointMock, error) {
	if r.findAllErr != nil {
		return nil, r.findAllErr
	}
	if specification != nil && specification.Filters != nil {
		r.lastFilters = *specification.Filters
	}
	mocks := make([]dao.EndpointMock, len(r.mocks))
	copy(mocks, r.mocks)
	return &mocks, nil
}

type fakeMatchersRepository struct {
	dao.MatchersRepository

	inserted  []*dao.Matcher
	insertErr error
}

func (r *fakeMatchersRepository) Insert(_ context.Context, matcher *dao.Matcher) (*dao.Matcher, error) {
	if r.insertErr != nil {
		return nil, r.insertErr
	}
	stored := *matcher
	stored.ID = uuid.New()
	r.inserted = append(r.inserted, &stored)
	return &stored, nil
}

type fakeMatcherParametersRepository struct {
	dao.MatcherParametersRepository

	batches [][]dao.MatcherParameter
}

func (r *fakeMatcherParametersRepository) BulkInsert(_ context.Context, params *[]dao.MatcherParameter) error {
	r.batches = append(r.batches, *params)
	return nil
}

type fakeTestCasesRepository struct {
	dao.TestCasesRepository

	existing  map[uuid.UUID]bool
	existsErr error
}

func (r *fakeTestCasesRepository) Exists(_ context.Context, id uuid.UUID) (bool, error) {
	if r.existsErr != nil {
		return false, r.existsErr
	}
	return r.existing[id], nil
}

type fakeTestsRunsRepository struct {
	dao.TestsRunsRepository

	inserted []dao.TestsRun
}

func (r *fakeTestsRunsRepository) Insert(_ context.Context, testsRun *dao.TestsRun) error {
	r.inserted = append(r.inserted, *testsRun)
	return nil
}

// claimCall records what a claim was made under, so a test can assert that the
// owner token, the session and the lease duration reached the repository.
type claimCall struct {
	owner         uuid.UUID
	sessionID     string
	leaseDuration time.Duration
}

type fakeTestCaseRunsRepository struct {
	dao.TestCaseRunsRepository

	views          []dao.TestCaseRunView
	claimable      *dao.TestCaseRun
	claimErr       error
	claims         []claimCall
	inserted       []dao.TestCaseRun
	statusUpdates  []string
	updated        []dao.TestCaseRun
	updateOwners   []uuid.UUID
	leaseOwner     *uuid.UUID
	findAllErr     error
	lastSpecFilter []model.Filter
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
	_ bool,
) error {
	if r.leaseOwner != nil && *r.leaseOwner != owner {
		return dao.ErrLeaseLost
	}
	r.updated = append(r.updated, *testCaseRun)
	r.updateOwners = append(r.updateOwners, owner)
	return nil
}

func (r *fakeTestCaseRunsRepository) UpdateStatus(
	_ context.Context,
	_ func(bun.QueryBuilder) bun.QueryBuilder,
	status string,
) error {
	r.statusUpdates = append(r.statusUpdates, status)
	return nil
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

type fakeTestCaseRunErrorsRepository struct {
	dao.TestCaseRunErrorsRepository

	byTestCaseRun map[uuid.UUID][]dao.ValidationError
	inserted      []dao.ValidationError
	insertOwners  []uuid.UUID
	leaseOwner    *uuid.UUID
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
