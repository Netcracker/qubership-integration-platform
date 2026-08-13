package services

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"time"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
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

	// RunRetention deletes aged test runs until ctx is canceled, and returns at
	// once when retention is off.
	RunRetention(ctx context.Context)
}

// retentionBatchSize caps how many test runs one retention statement deletes.
// A backlog is worked off one batch per statement rather than under a single
// transaction that holds locks for as long as it takes.
const retentionBatchSize = 500

// WorkNotifier reports queued work to whatever executes it. Taking the notifier
// rather than the executor keeps the queue writer independent of the pool.
type WorkNotifier interface {
	NotifyWork()
}

type testsRunsService struct {
	logger              *slog.Logger
	runner              dao.Runner
	repositories        Repositories
	testCaseRunsService TestCaseRunsService
	notifier            WorkNotifier
	retentionAge        time.Duration
	retentionInterval   time.Duration
}

// NewTestsRunsService returns a TestsRunsService over the given database access,
// keeping test runs for the retention age cfg carries. A nil notifier leaves the
// executor to find new runs on its next poll.
func NewTestsRunsService(
	cfg config.Config,
	logger *slog.Logger,
	runner dao.Runner,
	repositories Repositories,
	testCaseRunsService TestCaseRunsService,
	notifier WorkNotifier,
) TestsRunsService {
	cfg = cfg.WithDefaults()
	return &testsRunsService{
		logger:              logger,
		runner:              runner,
		repositories:        repositories,
		testCaseRunsService: testCaseRunsService,
		notifier:            notifier,
		retentionAge:        cfg.RetentionAge,
		retentionInterval:   cfg.RetentionInterval,
	}
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
	testsRunID, err := dao.RunInTx(ctx, s.runner, defaultTxOptions(), func(ctx context.Context, _ bun.IDB) (*uuid.UUID, error) {
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
	if err != nil {
		return nil, err
	}

	// The cases are committed, so a worker can start on them now instead of
	// waiting out a poll interval.
	if s.notifier != nil {
		s.notifier.NotifyWork()
	}
	return testsRunID, nil
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

// RunRetention sweeps once per retention interval until ctx is canceled. The
// first sweep waits out an interval, so a restart does not delete anything before
// the service is serving.
func (s *testsRunsService) RunRetention(ctx context.Context) {
	if s.retentionAge <= 0 {
		s.logger.InfoContext(ctx, "Retention of test runs is off, so every run is kept")
		return
	}
	s.logger.InfoContext(ctx, "Starting the retention of test runs",
		"retentionAge", s.retentionAge, "retentionInterval", s.retentionInterval)

	ticker := time.NewTicker(s.retentionInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			s.logger.InfoContext(ctx, "Stopped the retention of test runs")
			return
		case <-ticker.C:
		}
		deleted, err := s.deleteExpired(ctx)
		if err != nil {
			s.logger.ErrorContext(ctx, "Cannot delete the test runs that reached the retention age", "error", err)
		}
		if deleted > 0 {
			s.logger.InfoContext(ctx, "Deleted the test runs that reached the retention age", "testsRuns", deleted)
		}
	}
}

// deleteExpired works off the backlog one batch per transaction, and reports how
// many runs it deleted in total. A batch that comes back short is the last one.
func (s *testsRunsService) deleteExpired(ctx context.Context) (int, error) {
	total := 0
	for ctx.Err() == nil {
		deleted, err := dao.RunInTx(ctx, s.runner, defaultTxOptions(), func(ctx context.Context, _ bun.IDB) (int, error) {
			return s.repositories.TestsRuns.DeleteExpired(ctx, s.retentionAge, retentionBatchSize)
		})
		total += deleted
		if err != nil {
			return total, err
		}
		if deleted < retentionBatchSize {
			break
		}
	}
	return total, nil
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
