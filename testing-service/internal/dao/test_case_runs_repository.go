package dao

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func GetTestCaseRunsSortingFields() *[]string {
	return &[]string{"id", "test_case_name", "chain_id", "start", "finish", "status", "errors"}
}

func GetTestCaseRunsFilterConfiguration() *model.FilterConfiguration {
	return &model.FilterConfiguration{
		"id":             GetIdFilterConfiguration("test_case_run_view.id"),
		"test_case_id":   GetIdFilterConfiguration("test_case_run_view.test_case_id"),
		"test_case_name": GetStringFilterConfiguration("test_case_run_view.test_case_name"),
		"tests_run_id":   GetIdFilterConfiguration("test_case_run_view.tests_run_id"),
		"chain_id":       GetStringFilterConfiguration("test_case_run_view.chain_id"),
		"start":          GetTimestampFilterConfiguration("test_case_run_view.start"),
		"finish":         GetTimestampFilterConfiguration("test_case_run_view.finish"),
		"status":         GetEnumFilterConfiguration("test_case_run_view.status"),
		"errors":         GetIntegerFilterConfiguration("test_case_run_view.errors"),
	}
}

// ErrLeaseLost reports a worker write that was refused because the case run is
// no longer held under the lease the worker named. The sweeper returned the case
// to the queue, and another worker may already own the attempt.
var ErrLeaseLost = errors.New("the test case run is no longer held under this lease")

type TestCaseRunsRepository interface {
	FindAll(
		ctx context.Context,
		specification *model.SelectionSpecification,
		sorting model.SortOptions,
		pagination *model.PaginationOptions,
	) (*[]TestCaseRunView, error)
	FindById(ctx context.Context, id uuid.UUID) (*TestCaseRunView, error)
	Insert(ctx context.Context, testCaseRuns *[]TestCaseRun) error
	UpdateOwned(ctx context.Context, testCaseRun *TestCaseRun, owner uuid.UUID, omitZero bool) error
	UpdateStatus(ctx context.Context, selector func(bun.QueryBuilder) bun.QueryBuilder, status string) error
	Claim(ctx context.Context, owner uuid.UUID, sessionID string, leaseDuration time.Duration) (*TestCaseRun, error)
}

type testCaseRunsRepository struct {
	logger          *slog.Logger
	paginationLimit int
}

func NewTestCaseRunsRepository(logger *slog.Logger, paginationLimit int) TestCaseRunsRepository {
	return &testCaseRunsRepository{logger: logger, paginationLimit: paginationLimit}
}

func (r *testCaseRunsRepository) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
) (*[]TestCaseRunView, error) {
	if err := ValidateSortOptions(sorting, GetTestCaseRunsSortingFields()); err != nil {
		return nil, err
	}

	filterConfiguration := GetTestCaseRunsFilterConfiguration()
	if specification != nil {
		if err := ValidateFilters(specification.Filters, filterConfiguration); err != nil {
			return nil, err
		}
	}

	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []TestCaseRunView

	query := db.NewSelect().Model(&result).ApplyQueryBuilder(func(builder bun.QueryBuilder) bun.QueryBuilder {
		return AddSpecification(builder, specification, filterConfiguration)
	})

	query = AddSorting(query, sorting)
	if pagination != nil {
		query = AddPagination(query, *pagination, r.paginationLimit)
	}

	if r.logger.Enabled(ctx, slog.LevelDebug) {
		r.logger.DebugContext(ctx, "Selecting test case runs", "query", query.String())
	}

	if err := query.Scan(ctx); err != nil {
		return nil, err
	}
	if result == nil {
		result = []TestCaseRunView{}
	}
	return &result, nil
}

func (r *testCaseRunsRepository) FindById(ctx context.Context, id uuid.UUID) (*TestCaseRunView, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []TestCaseRunView
	if err := db.NewSelect().Model(&result).Where("id = ?", id).Scan(ctx); err != nil {
		return nil, err
	}
	if len(result) == 0 {
		return nil, nil
	}
	return &result[0], nil
}

func (r *testCaseRunsRepository) Insert(ctx context.Context, testCaseRuns *[]TestCaseRun) error {
	if testCaseRuns == nil || len(*testCaseRuns) == 0 {
		return nil
	}
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	_, err = db.NewInsert().Model(testCaseRuns).Exec(ctx)
	return err
}

// UpdateOwned applies the update only while owner still holds the lease, and
// reports ErrLeaseLost when it does not. Every write a worker makes about the
// case it claimed goes through here.
func (r *testCaseRunsRepository) UpdateOwned(
	ctx context.Context,
	testCaseRun *TestCaseRun,
	owner uuid.UUID,
	omitZero bool,
) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	query := db.NewUpdate().Model(testCaseRun)
	if omitZero {
		query = query.OmitZero()
	}
	result, err := query.WherePK().Where("lease_owner = ?", owner).Exec(ctx)
	if err != nil {
		return err
	}
	affected, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if affected == 0 {
		return ErrLeaseLost
	}
	return nil
}

func (r *testCaseRunsRepository) UpdateStatus(ctx context.Context, selector func(bun.QueryBuilder) bun.QueryBuilder, status string) error {
	db, err := GetDb(ctx)
	if err != nil {
		return err
	}
	testCaseRun := TestCaseRun{Status: &status}
	_, err = db.NewUpdate().Model(&testCaseRun).Column("status").ApplyQueryBuilder(selector).Exec(ctx)
	return err
}

// claimTestCaseRunQuery claims the next pending case of one test run and stamps
// the fencing token. The subselect takes the row lock the update needs and skips
// a case another transaction holds, a cancellation in flight for example.
const claimTestCaseRunQuery = `
update test_case_runs set
    status = ?,
    start = now(),
    session_id = ?,
    lease_until = now() + make_interval(secs => ?),
    lease_owner = ?
where id = (
    select id from test_case_runs
    where tests_run_id = ? and status = ?
    order by ordinal, id
    for update skip locked
    limit 1
)
returning *`

// claimTestsRunQuery locks one test run that has work and nothing running yet.
// Locking it is what serializes the workers entering the same run; the guards
// alone do not, because under READ COMMITTED each worker evaluates them against
// its own snapshot and neither sees the other's uncommitted row.
const claimTestsRunQuery = `
select r.id from tests_runs r
where exists (select 1 from test_case_runs c where c.tests_run_id = r.id and c.status = ?)
  and not exists (select 1 from test_case_runs c where c.tests_run_id = r.id and c.status = ?)`

// Claim takes the next test case run the caller may execute, or returns nil when
// there is none. It runs two statements, and both belong to the caller's
// transaction: the first locks a test run, the second claims that run's next
// pending case by ordinal. Cases of one run therefore stay sequential while
// different runs progress at the same time.
func (r *testCaseRunsRepository) Claim(
	ctx context.Context,
	owner uuid.UUID,
	sessionID string,
	leaseDuration time.Duration,
) (*TestCaseRun, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var exhausted []uuid.UUID
	for {
		testsRunID, err := r.claimTestsRun(ctx, db, exhausted)
		if err != nil || testsRunID == nil {
			return nil, err
		}
		testCaseRun, err := r.claimTestCaseRun(ctx, db, *testsRunID, owner, sessionID, leaseDuration)
		if err != nil {
			return nil, err
		}
		if testCaseRun != nil {
			return testCaseRun, nil
		}
		// The run had no case left to take: the last pending one was canceled
		// between the two statements, or another transaction holds it. Move on to
		// the next run rather than wait out a poll interval. The run row stays
		// locked until the caller commits, so skip locked will not skip it on the
		// next pass and it has to be excluded by id.
		exhausted = append(exhausted, *testsRunID)
	}
}

func (r *testCaseRunsRepository) claimTestsRun(
	ctx context.Context,
	db bun.IDB,
	exhausted []uuid.UUID,
) (*uuid.UUID, error) {
	query := claimTestsRunQuery
	args := []any{RunStatusPending, RunStatusRunning}
	if len(exhausted) > 0 {
		query += " and r.id not in (?)"
		args = append(args, bun.In(exhausted))
	}
	query += " order by r.created_at for update skip locked limit 1"

	var ids []uuid.UUID
	if err := db.NewRaw(query, args...).Scan(ctx, &ids); err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return nil, nil
	}
	return &ids[0], nil
}

func (r *testCaseRunsRepository) claimTestCaseRun(
	ctx context.Context,
	db bun.IDB,
	testsRunID uuid.UUID,
	owner uuid.UUID,
	sessionID string,
	leaseDuration time.Duration,
) (*TestCaseRun, error) {
	var result []TestCaseRun
	err := db.NewRaw(
		claimTestCaseRunQuery,
		RunStatusRunning,
		sessionID,
		leaseDuration.Seconds(),
		owner,
		testsRunID,
		RunStatusPending,
	).Scan(ctx, &result)
	if err != nil {
		return nil, err
	}
	if len(result) == 0 {
		return nil, nil
	}
	return &result[0], nil
}
