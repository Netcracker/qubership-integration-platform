package dao

import (
	"context"

	"github.com/google/uuid"
	"github.com/uptrace/bun"
)

type TestCaseRunErrorsRepository interface {
	FindByIds(ctx context.Context, ids []uuid.UUID, withMatchers bool) (*[]ValidationError, error)
	FindByTestCaseRunId(ctx context.Context, id uuid.UUID, withMatchers bool) (*[]ValidationError, error)
	InsertOwned(ctx context.Context, validationError *ValidationError, owner uuid.UUID) (*ValidationError, error)
}

type testCaseRunErrorsRepository struct{}

func NewTestCaseRunErrorsRepository() TestCaseRunErrorsRepository {
	return &testCaseRunErrorsRepository{}
}

func (r *testCaseRunErrorsRepository) FindByIds(ctx context.Context, ids []uuid.UUID, withMatchers bool) (*[]ValidationError, error) {
	if len(ids) == 0 {
		return &[]ValidationError{}, nil
	}
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []ValidationError
	query := db.NewSelect().Model(&result).Where("validation_error.id IN (?)", bun.In(ids))
	if withMatchers {
		query = query.Relation("Matcher")
	}
	if err := query.Scan(ctx); err != nil {
		return nil, err
	}
	if result == nil {
		result = []ValidationError{}
	}
	return &result, nil
}

func (r *testCaseRunErrorsRepository) FindByTestCaseRunId(ctx context.Context, id uuid.UUID, withMatchers bool) (*[]ValidationError, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []ValidationError
	query := db.NewSelect().Model(&result).Where("test_case_run_id = ?", id)
	if withMatchers {
		query = query.Relation("Matcher")
	}
	if err := query.Scan(ctx); err != nil {
		return nil, err
	}
	if result == nil {
		result = []ValidationError{}
	}
	return &result, nil
}

// insertOwnedQuery records a validation error only while owner still holds the
// lease on the case run. Fencing the errors matters as much as fencing the
// status: a stalled worker would otherwise write its findings against the
// attempt another worker now owns.
//
// The fence takes a share lock on the case run rather than reading it. Under
// READ COMMITTED an unlocked subquery answers from the statement's snapshot, so
// an insert that started before ReclaimExpired committed would still see the old
// lease_owner and write, while the sweep's delete of the previous attempt's rows
// would not see the new one. The case would then run again into its own
// leftover, colliding on unique (test_case_run_id, matcher_id). The lock makes
// the two statements wait for each other instead.
const insertOwnedQuery = `
insert into validation_errors (test_case_run_id, matcher_id, message)
select ?::uuid, ?::uuid, ?::text
where exists (
	select 1 from test_case_runs
	where id = ?::uuid and lease_owner = ?::uuid
	for share
)
returning *`

// InsertOwned reports ErrLeaseLost when the fence rejected the write.
func (r *testCaseRunErrorsRepository) InsertOwned(
	ctx context.Context,
	validationError *ValidationError,
	owner uuid.UUID,
) (*ValidationError, error) {
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result []ValidationError
	err = db.NewRaw(
		insertOwnedQuery,
		validationError.TestCaseRunID,
		validationError.MatcherID,
		validationError.Message,
		validationError.TestCaseRunID,
		owner,
	).Scan(ctx, &result)
	if err != nil {
		return nil, err
	}
	if len(result) == 0 {
		return nil, ErrLeaseLost
	}
	return &result[0], nil
}
