package services

import (
	"context"
	"database/sql"

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

type fakeTestCaseRunsRepository struct {
	dao.TestCaseRunsRepository

	views          []dao.TestCaseRunView
	pending        *dao.TestCaseRun
	inserted       []dao.TestCaseRun
	statusUpdates  []string
	updated        []dao.TestCaseRun
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

func (r *fakeTestCaseRunsRepository) Update(_ context.Context, testCaseRun *dao.TestCaseRun, _ bool) error {
	r.updated = append(r.updated, *testCaseRun)
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

func (r *fakeTestCaseRunsRepository) FindPending(_ context.Context) (*dao.TestCaseRun, error) {
	return r.pending, nil
}

type fakeTestCaseRunErrorsRepository struct {
	dao.TestCaseRunErrorsRepository

	byTestCaseRun map[uuid.UUID][]dao.ValidationError
	inserted      []dao.ValidationError
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

func (r *fakeTestCaseRunErrorsRepository) Insert(
	_ context.Context,
	validationError *dao.ValidationError,
) (*dao.ValidationError, error) {
	stored := *validationError
	stored.ID = uuid.New()
	r.inserted = append(r.inserted, stored)
	return &stored, nil
}
