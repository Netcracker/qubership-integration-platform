package services

import (
	"context"
	"errors"
	"fmt"
	"slices"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// Entity kinds a test run can be started from.
const (
	EntityTypeTestCases    = "test_cases"
	EntityTypeTestsRuns    = "tests_runs"
	EntityTypeTestCaseRuns = "test_case_runs"
)

// ErrEmptyTestCaseList reports a request to start a run over no test cases.
var ErrEmptyTestCaseList = errors.New("the list of test case IDs is empty")

// TestsRunsService manages test runs: a queue of test case runs plus the
// aggregate the list API reports.
type TestsRunsService interface {
	FindAll(
		ctx context.Context,
		specification *model.SelectionSpecification,
		sorting model.SortOptions,
		pagination *model.PaginationOptions,
	) (*[]dao.TestsRunView, error)
	FindById(ctx context.Context, id uuid.UUID) (*dao.TestsRunView, error)
	StartNew(ctx context.Context, testCaseIds *[]uuid.UUID) (*uuid.UUID, error)
	StartNewFromEntitiesWithType(ctx context.Context, ids *[]uuid.UUID, entityType string) (*uuid.UUID, error)
	Delete(ctx context.Context, id uuid.UUID) error
	BulkDelete(ctx context.Context, ids *[]uuid.UUID) error
	Cancel(ctx context.Context, id uuid.UUID) error
	BulkCancel(ctx context.Context, ids *[]uuid.UUID) error
	Export(ctx context.Context, ids *[]uuid.UUID) (string, error)
}

type testsRunsService struct {
	runner              dao.Runner
	repositories        Repositories
	testCaseRunsService TestCaseRunsService
}

// NewTestsRunsService returns a TestsRunsService over the given database access.
func NewTestsRunsService(
	runner dao.Runner,
	repositories Repositories,
	testCaseRunsService TestCaseRunsService,
) TestsRunsService {
	return &testsRunsService{runner: runner, repositories: repositories, testCaseRunsService: testCaseRunsService}
}

func (s *testsRunsService) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
) (*[]dao.TestsRunView, error) {
	return dao.Run(ctx, s.runner, func(ctx context.Context, _ bun.IDB) (*[]dao.TestsRunView, error) {
		return s.repositories.TestsRuns.FindAll(ctx, specification, sorting, pagination)
	})
}

func (s *testsRunsService) FindById(ctx context.Context, id uuid.UUID) (*dao.TestsRunView, error) {
	return dao.Run(ctx, s.runner, func(ctx context.Context, _ bun.IDB) (*dao.TestsRunView, error) {
		return s.repositories.TestsRuns.FindById(ctx, id)
	})
}

func (s *testsRunsService) StartNew(ctx context.Context, testCaseIds *[]uuid.UUID) (*uuid.UUID, error) {
	if testCaseIds == nil || len(*testCaseIds) == 0 {
		return nil, ErrEmptyTestCaseList
	}
	return dao.RunInTx(ctx, s.runner, defaultTxOptions(), func(ctx context.Context, _ bun.IDB) (*uuid.UUID, error) {
		if err := s.verifyAllTestCasesExist(ctx, testCaseIds); err != nil {
			return nil, err
		}

		testsRunID, err := uuid.NewUUID()
		if err != nil {
			return nil, err
		}
		testsRun := dao.TestsRun{ID: testsRunID}
		if err = s.repositories.TestsRuns.Insert(ctx, &testsRun); err != nil {
			return nil, err
		}

		if err = s.createTestCaseRuns(ctx, testsRunID, testCaseIds); err != nil {
			return nil, err
		}
		return &testsRunID, nil
	})
}

func (s *testsRunsService) StartNewFromEntitiesWithType(
	ctx context.Context,
	ids *[]uuid.UUID,
	entityType string,
) (*uuid.UUID, error) {
	testCaseIds, err := s.getTestCasesIds(ctx, ids, entityType)
	if err != nil {
		return nil, err
	}
	return s.StartNew(ctx, testCaseIds)
}

func (s *testsRunsService) Delete(ctx context.Context, id uuid.UUID) error {
	return runInTx(ctx, s.runner, func(ctx context.Context) error {
		return s.repositories.TestsRuns.Delete(ctx, id)
	})
}

func (s *testsRunsService) BulkDelete(ctx context.Context, ids *[]uuid.UUID) error {
	return runInTx(ctx, s.runner, func(ctx context.Context) error {
		return s.repositories.TestsRuns.BulkDelete(ctx, ids)
	})
}

func (s *testsRunsService) Cancel(ctx context.Context, id uuid.UUID) error {
	testsRunIds := []uuid.UUID{id}
	return s.testCaseRunsService.CancelByTestsRuns(ctx, &testsRunIds)
}

func (s *testsRunsService) BulkCancel(ctx context.Context, ids *[]uuid.UUID) error {
	return s.testCaseRunsService.CancelByTestsRuns(ctx, ids)
}

func (s *testsRunsService) Export(ctx context.Context, ids *[]uuid.UUID) (string, error) {
	return s.testCaseRunsService.ExportByTestsRunIds(ctx, ids)
}

func (s *testsRunsService) verifyAllTestCasesExist(ctx context.Context, ids *[]uuid.UUID) error {
	for _, id := range *ids {
		exists, err := s.repositories.TestCases.Exists(ctx, id)
		if err != nil {
			return err
		}
		if !exists {
			return fmt.Errorf("test case does not exist: %v", id)
		}
	}
	return nil
}

func (s *testsRunsService) createTestCaseRuns(ctx context.Context, testsRunID uuid.UUID, testCaseIds *[]uuid.UUID) error {
	testCaseRuns := make([]dao.TestCaseRun, 0, len(*testCaseIds))
	for index := range *testCaseIds {
		// The ordinal is the order the cases were selected in, and it is what the
		// claim runs them by. Leaving it unset would order a run arbitrarily.
		ordinal := index + 1
		testCaseRuns = append(testCaseRuns, dao.TestCaseRun{
			TestsRunID: &testsRunID,
			TestCaseID: &(*testCaseIds)[index],
			Ordinal:    &ordinal,
		})
	}
	return s.repositories.TestCaseRuns.Insert(ctx, &testCaseRuns)
}

// getTestCasesIds resolves the ids the caller passed to the test cases a run
// should cover. Runs and case runs resolve through the case runs they own.
func (s *testsRunsService) getTestCasesIds(
	ctx context.Context,
	ids *[]uuid.UUID,
	entityType string,
) (*[]uuid.UUID, error) {
	switch entityType {
	case EntityTypeTestCases:
		return ids, nil
	case EntityTypeTestCaseRuns:
		specification := model.SelectionSpecification{Ids: ids}
		return s.getTestCasesIdsFromTestCaseRuns(ctx, &specification)
	case EntityTypeTestsRuns:
		if ids == nil || len(*ids) == 0 {
			return ids, nil
		}
		filters := []model.Filter{{
			Feature:   "tests_run_id",
			Condition: model.ConditionIn,
			Values:    uuid.UUIDs(*ids).Strings(),
		}}
		specification := model.SelectionSpecification{Filters: &filters}
		return s.getTestCasesIdsFromTestCaseRuns(ctx, &specification)
	default:
		return nil, fmt.Errorf("unknown entity type: %v", entityType)
	}
}

func (s *testsRunsService) getTestCasesIdsFromTestCaseRuns(
	ctx context.Context,
	specification *model.SelectionSpecification,
) (*[]uuid.UUID, error) {
	sorting := model.SortOptions{Order: model.OrderAscending}
	testCaseRuns, err := s.testCaseRunsService.FindAll(ctx, specification, sorting, nil)
	if err != nil {
		return nil, err
	}
	var testCaseIds []uuid.UUID
	if testCaseRuns != nil {
		for _, testCaseRun := range *testCaseRuns {
			if testCaseRun.TestCaseID != nil {
				testCaseIds = append(testCaseIds, *testCaseRun.TestCaseID)
			}
		}
	}
	slices.SortFunc(testCaseIds, func(a, b uuid.UUID) int {
		return slices.Compare(a[:], b[:])
	})
	testCaseIds = slices.Compact(testCaseIds)
	return &testCaseIds, nil
}
