package services

import (
	"cmp"
	"context"
	"log/slog"
	"math"
	"slices"
	"time"

	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// What a test run can be started from. These are the values of the "from" query
// parameter of the create endpoint.
const (
	RunSourceTestCases    = "test_cases"
	RunSourceTestsRuns    = "tests_runs"
	RunSourceTestCaseRuns = "test_case_runs"
)

// ErrEmptyTestCaseList reports a request to start a run over no test cases. Like
// the other refusals of this service it wraps ErrInvalidRequest, so the caller
// reads a 400 rather than a 500 about its own input.
var ErrEmptyTestCaseList = invalidRequest("the list of test case IDs is empty")

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
	repositories        dao.Repositories
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
	repositories dao.Repositories,
	testCaseRunsService TestCaseRunsService,
	notifier WorkNotifier,
) TestsRunsService {
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
	return dao.Run(ctx, s.runner, func(ctx context.Context) (*[]dao.TestsRunView, error) {
		return s.repositories.TestsRuns.FindAll(ctx, specification, sorting, pagination)
	})
}

func (s *testsRunsService) FindById(ctx context.Context, id uuid.UUID) (*dao.TestsRunView, error) {
	return dao.Run(ctx, s.runner, func(ctx context.Context) (*dao.TestsRunView, error) {
		return s.repositories.TestsRuns.FindById(ctx, id)
	})
}

func (s *testsRunsService) startNew(ctx context.Context, testCaseIDs *[]uuid.UUID) (*uuid.UUID, error) {
	if testCaseIDs == nil || len(*testCaseIDs) == 0 {
		return nil, ErrEmptyTestCaseList
	}
	testsRunID, err := dao.RunInTx(ctx, s.runner, func(ctx context.Context) (*uuid.UUID, error) {
		if err := s.verifyAllTestCasesExist(ctx, testCaseIDs); err != nil {
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

		if err = s.createTestCaseRuns(ctx, testsRunID, testCaseIDs); err != nil {
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
	testCaseIDs, err := s.getTestCaseIDs(ctx, ids, entityType)
	if err != nil {
		return nil, err
	}
	return s.startNew(ctx, testCaseIDs)
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
	testsRunIDs := []uuid.UUID{id}
	return s.testCaseRunsService.CancelByTestsRuns(ctx, &testsRunIDs)
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
		deleted, err := dao.RunInTx(ctx, s.runner, func(ctx context.Context) (int, error) {
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
			return invalidRequest("test case does not exist: %v", id)
		}
	}
	return nil
}

func (s *testsRunsService) createTestCaseRuns(ctx context.Context, testsRunID uuid.UUID, testCaseIDs *[]uuid.UUID) error {
	testCaseRuns := make([]dao.TestCaseRun, 0, len(*testCaseIDs))
	for index := range *testCaseIDs {
		// The ordinal is the order the cases were selected in, and it is what the
		// claim runs them by. Leaving it unset would order a run arbitrarily.
		ordinal := index + 1
		testCaseRuns = append(testCaseRuns, dao.TestCaseRun{
			TestsRunID: &testsRunID,
			TestCaseID: &(*testCaseIDs)[index],
			Ordinal:    &ordinal,
		})
	}
	return s.repositories.TestCaseRuns.Insert(ctx, &testCaseRuns)
}

// getTestCaseIDs resolves the ids the caller passed to the test cases a run
// should cover. Runs and case runs resolve through the case runs they own.
func (s *testsRunsService) getTestCaseIDs(
	ctx context.Context,
	ids *[]uuid.UUID,
	entityType string,
) (*[]uuid.UUID, error) {
	switch entityType {
	case RunSourceTestCases:
		return ids, nil
	case RunSourceTestCaseRuns:
		if ids == nil || len(*ids) == 0 {
			return ids, nil
		}
		specification := model.SelectionSpecification{Ids: ids}
		// Rerunning individual cases repeats the order the caller listed them in.
		position := positionsOf(ids)
		return s.getTestCaseIDsFromTestCaseRuns(ctx, &specification,
			func(a, b dao.TestCaseRunView) int {
				return cmp.Compare(position.of(a.ID), position.of(b.ID))
			})
	case RunSourceTestsRuns:
		if ids == nil || len(*ids) == 0 {
			return ids, nil
		}
		filters := []model.Filter{{
			Feature:   "tests_run_id",
			Condition: model.ConditionIn,
			Values:    uuid.UUIDs(*ids).Strings(),
		}}
		specification := model.SelectionSpecification{Filters: &filters}
		// Rerunning whole runs repeats the order the caller listed the runs in,
		// and within each run the order its own cases ran in.
		position := positionsOf(ids)
		return s.getTestCaseIDsFromTestCaseRuns(ctx, &specification,
			func(a, b dao.TestCaseRunView) int {
				if result := cmp.Compare(position.of(testsRunIDOf(a)), position.of(testsRunIDOf(b))); result != 0 {
					return result
				}
				return cmp.Compare(ordinalOf(a), ordinalOf(b))
			})
	default:
		return nil, invalidRequest("unknown entity type: %v", entityType)
	}
}

// orderIndex holds the place each id had in the request.
type orderIndex map[uuid.UUID]int

// of reports where id belongs. An id the request did not name sorts last, ahead
// of nothing the caller can point at.
func (i orderIndex) of(id uuid.UUID) int {
	if position, named := i[id]; named {
		return position
	}
	return math.MaxInt
}

// positionsOf indexes ids by the place the caller gave them. A repeated id keeps
// its first place, so it does not pull the cases of its second mention forward.
func positionsOf(ids *[]uuid.UUID) orderIndex {
	if ids == nil {
		return orderIndex{}
	}
	positions := make(orderIndex, len(*ids))
	for index, id := range *ids {
		if _, taken := positions[id]; !taken {
			positions[id] = index
		}
	}
	return positions
}

// ordinalOf reports the place a case run had in its own test run. A row from
// before the ordinal column sorts last, the way the claim orders it.
func ordinalOf(testCaseRun dao.TestCaseRunView) int {
	if testCaseRun.Ordinal == nil {
		return math.MaxInt
	}
	return *testCaseRun.Ordinal
}

func testsRunIDOf(testCaseRun dao.TestCaseRunView) uuid.UUID {
	if testCaseRun.TestsRunID == nil {
		return uuid.Nil
	}
	return *testCaseRun.TestsRunID
}

// getTestCaseIDsFromTestCaseRuns resolves case runs to the distinct test cases
// they cover, in the order compare puts the case runs in.
//
// That order is what createTestCaseRuns stamps the ordinals from, and the
// ordinal is what the claim runs a test run by, so a rerun has to repeat the
// order of its source rather than reshuffle it. The listing itself returns rows
// in no particular order: sorting it in the query would mean accepting
// tests_run_id and ordinal as sorting fields of the public listing, which is a
// wider change than the rerun needs, and the rows of one rerun are few enough to
// order here.
func (s *testsRunsService) getTestCaseIDsFromTestCaseRuns(
	ctx context.Context,
	specification *model.SelectionSpecification,
	compare func(a, b dao.TestCaseRunView) int,
) (*[]uuid.UUID, error) {
	sorting := model.SortOptions{Order: model.OrderAscending}
	testCaseRuns, err := s.testCaseRunsService.FindAll(ctx, specification, sorting, nil)
	if err != nil {
		return nil, err
	}
	var testCaseIDs []uuid.UUID
	if testCaseRuns == nil {
		return &testCaseIDs, nil
	}
	// A clone, because the sort would otherwise reorder the caller's rows.
	ordered := slices.Clone(*testCaseRuns)
	slices.SortStableFunc(ordered, compare)

	seen := make(map[uuid.UUID]struct{}, len(ordered))
	for _, testCaseRun := range ordered {
		if testCaseRun.TestCaseID == nil {
			continue
		}
		if _, taken := seen[*testCaseRun.TestCaseID]; taken {
			continue
		}
		seen[*testCaseRun.TestCaseID] = struct{}{}
		testCaseIDs = append(testCaseIDs, *testCaseRun.TestCaseID)
	}
	return &testCaseIDs, nil
}
