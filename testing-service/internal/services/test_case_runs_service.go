package services

import (
	"bytes"
	"context"
	"encoding/csv"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// TestCaseRunsService manages the queue entries of a test run: one per test case,
// each moving from pending through running to finished, skipped or canceled.
type TestCaseRunsService interface {
	FindAll(
		ctx context.Context,
		specification *model.SelectionSpecification,
		sorting model.SortOptions,
		pagination *model.PaginationOptions,
	) (*[]dao.TestCaseRunView, error)
	FindById(ctx context.Context, id uuid.UUID) (*dao.TestCaseRunView, error)
	Cancel(ctx context.Context, id uuid.UUID) error
	BulkCancel(ctx context.Context, ids *[]uuid.UUID) error
	CancelByTestsRuns(ctx context.Context, testsRunIds *[]uuid.UUID) error

	ClaimNext(ctx context.Context, owner uuid.UUID, sessionID string) (*dao.TestCaseRun, error)
	Finish(ctx context.Context, id uuid.UUID, owner uuid.UUID) error
	Skip(ctx context.Context, id uuid.UUID, owner uuid.UUID) error
	RenewLease(ctx context.Context, id uuid.UUID, owner uuid.UUID) error
	ReclaimExpired(ctx context.Context) (int, error)

	Export(ctx context.Context, ids *[]uuid.UUID) (string, error)
	ExportByTestsRunIds(ctx context.Context, ids *[]uuid.UUID) (string, error)
}

type testCaseRunsService struct {
	runner        dao.Runner
	repositories  dao.Repositories
	leaseDuration time.Duration
}

// NewTestCaseRunsService returns a TestCaseRunsService over the given database
// access, leasing claimed runs for the duration cfg carries.
func NewTestCaseRunsService(cfg config.Config, runner dao.Runner, repositories dao.Repositories) TestCaseRunsService {
	return &testCaseRunsService{
		runner:        runner,
		repositories:  repositories,
		leaseDuration: cfg.LeaseDuration,
	}
}

func (s *testCaseRunsService) FindAll(
	ctx context.Context,
	specification *model.SelectionSpecification,
	sorting model.SortOptions,
	pagination *model.PaginationOptions,
) (*[]dao.TestCaseRunView, error) {
	return dao.Run(ctx, s.runner, func(ctx context.Context) (*[]dao.TestCaseRunView, error) {
		return s.repositories.TestCaseRuns.FindAll(ctx, specification, sorting, pagination)
	})
}

func (s *testCaseRunsService) FindById(ctx context.Context, id uuid.UUID) (*dao.TestCaseRunView, error) {
	return dao.Run(ctx, s.runner, func(ctx context.Context) (*dao.TestCaseRunView, error) {
		return s.repositories.TestCaseRuns.FindById(ctx, id)
	})
}

func (s *testCaseRunsService) Cancel(ctx context.Context, id uuid.UUID) error {
	return s.cancelTestCaseRuns(ctx, func(builder bun.QueryBuilder) bun.QueryBuilder {
		return builder.Where("id = ?", id)
	})
}

func (s *testCaseRunsService) BulkCancel(ctx context.Context, ids *[]uuid.UUID) error {
	if ids == nil || len(*ids) == 0 {
		return nil
	}
	return s.cancelTestCaseRuns(ctx, func(builder bun.QueryBuilder) bun.QueryBuilder {
		return builder.Where("id IN (?)", bun.In(*ids))
	})
}

func (s *testCaseRunsService) CancelByTestsRuns(ctx context.Context, testsRunIds *[]uuid.UUID) error {
	if testsRunIds == nil || len(*testsRunIds) == 0 {
		return nil
	}
	return s.cancelTestCaseRuns(ctx, func(builder bun.QueryBuilder) bun.QueryBuilder {
		return builder.Where("tests_run_id IN (?)", bun.In(*testsRunIds))
	})
}

// cancelTestCaseRuns cancels the selected runs that are still pending. A run that
// already started is left alone.
func (s *testCaseRunsService) cancelTestCaseRuns(ctx context.Context, selector func(bun.QueryBuilder) bun.QueryBuilder) error {
	return runInTx(ctx, s.runner, func(ctx context.Context) error {
		pendingOnly := func(builder bun.QueryBuilder) bun.QueryBuilder {
			return builder.
				WhereGroup(" AND ", selector).
				WhereGroup(" AND ", func(builder bun.QueryBuilder) bun.QueryBuilder {
					return builder.Where("status = ?", dao.RunStatusPending)
				})
		}
		return s.repositories.TestCaseRuns.UpdateStatus(ctx, pendingOnly, dao.RunStatusCanceled)
	})
}

// ClaimNext hands out the next test case run to execute, already stamped as
// running and leased to owner. The claim is what starts the run, so no separate
// start call follows it.
func (s *testCaseRunsService) ClaimNext(ctx context.Context, owner uuid.UUID, sessionID string) (*dao.TestCaseRun, error) {
	return dao.RunInTx(ctx, s.runner, func(ctx context.Context) (*dao.TestCaseRun, error) {
		return s.repositories.TestCaseRuns.Claim(ctx, owner, sessionID, s.leaseDuration)
	})
}

func (s *testCaseRunsService) Finish(ctx context.Context, id uuid.UUID, owner uuid.UUID) error {
	return runInTx(ctx, s.runner, func(ctx context.Context) error {
		timestamp := time.Now()
		status := dao.RunStatusFinished
		testCaseRun := &dao.TestCaseRun{ID: id, Status: &status, Finish: &timestamp}
		return s.repositories.TestCaseRuns.UpdateOwned(ctx, testCaseRun, owner)
	})
}

func (s *testCaseRunsService) Skip(ctx context.Context, id uuid.UUID, owner uuid.UUID) error {
	return runInTx(ctx, s.runner, func(ctx context.Context) error {
		timestamp := time.Now()
		status := dao.RunStatusSkipped
		testCaseRun := &dao.TestCaseRun{ID: id, Status: &status, Finish: &timestamp}
		return s.repositories.TestCaseRuns.UpdateOwned(ctx, testCaseRun, owner)
	})
}

// RenewLease extends the claim on a case that is still running, for the same
// duration the claim itself leased it for.
func (s *testCaseRunsService) RenewLease(ctx context.Context, id uuid.UUID, owner uuid.UUID) error {
	return runInTx(ctx, s.runner, func(ctx context.Context) error {
		return s.repositories.TestCaseRuns.RenewLease(ctx, id, owner, s.leaseDuration)
	})
}

// ReclaimExpired returns the cases whose lease ran out to the queue and reports
// how many it took back.
func (s *testCaseRunsService) ReclaimExpired(ctx context.Context) (int, error) {
	return dao.RunInTx(ctx, s.runner, func(ctx context.Context) (int, error) {
		return s.repositories.TestCaseRuns.ReclaimExpired(ctx)
	})
}

func (s *testCaseRunsService) Export(ctx context.Context, ids *[]uuid.UUID) (string, error) {
	if ids == nil || len(*ids) == 0 {
		return "", nil
	}
	specification := model.SelectionSpecification{Ids: ids}
	return s.exportSelectionToCsv(ctx, &specification)
}

func (s *testCaseRunsService) ExportByTestsRunIds(ctx context.Context, ids *[]uuid.UUID) (string, error) {
	if ids == nil || len(*ids) == 0 {
		return "", nil
	}
	filters := []model.Filter{{
		Feature:   "tests_run_id",
		Condition: model.ConditionIn,
		Values:    uuid.UUIDs(*ids).Strings(),
	}}
	specification := model.SelectionSpecification{Filters: &filters}
	return s.exportSelectionToCsv(ctx, &specification)
}

func (s *testCaseRunsService) exportSelectionToCsv(ctx context.Context, specification *model.SelectionSpecification) (string, error) {
	var buffer bytes.Buffer
	writer := csv.NewWriter(&buffer)
	err := runQuery(ctx, s.runner, func(ctx context.Context) error {
		return s.exportToCsv(ctx, specification, writer)
	})
	if err != nil {
		return "", err
	}
	writer.Flush()
	return buffer.String(), writer.Error()
}

// testCaseRunCsvHeader names the columns exportToCsv writes, in order.
var testCaseRunCsvHeader = []string{
	"Tests Run ID",
	"Test Case Run ID",
	"Chain ID",
	"Test Case ID",
	"Test Case Name",
	"Test Case Description",
	"Start",
	"Finish",
	"Status",
	"Session ID",
	"Errors",
	"Rule ID",
	"Rule Name",
	"Rule Description",
	"Message",
}

func (s *testCaseRunsService) exportToCsv(
	ctx context.Context,
	specification *model.SelectionSpecification,
	writer *csv.Writer,
) error {
	sorting := model.SortOptions{Order: model.OrderAscending}
	testCaseRuns, err := s.repositories.TestCaseRuns.FindAll(ctx, specification, sorting, nil)
	if err != nil {
		return err
	}
	if testCaseRuns == nil || len(*testCaseRuns) == 0 {
		return nil
	}
	if err = writer.Write(testCaseRunCsvHeader); err != nil {
		return err
	}

	for _, testCaseRun := range *testCaseRuns {
		fields := csvFields(
			optionalUUID(testCaseRun.TestsRunID),
			testCaseRun.ID.String(),
			optionalString(testCaseRun.ChainID),
			optionalUUID(testCaseRun.TestCaseID),
			optionalString(testCaseRun.TestCaseName),
			optionalString(testCaseRun.TestCaseDescription),
			optionalTime(testCaseRun.Start),
			optionalTime(testCaseRun.Finish),
			optionalString(testCaseRun.Status),
			optionalString(testCaseRun.SessionID),
			strconv.Itoa(testCaseRun.Errors),
		)
		if testCaseRun.Errors == 0 {
			if err = writer.Write(append(fields, "", "", "", "")); err != nil {
				return err
			}
			continue
		}
		validationErrors, err := s.repositories.TestCaseRunErrors.FindByTestCaseRunId(ctx, testCaseRun.ID, true)
		if err != nil {
			return err
		}
		if validationErrors == nil {
			continue
		}
		for _, validationError := range *validationErrors {
			var name, description string
			if validationError.Matcher != nil {
				name = validationError.Matcher.Name
				description = validationError.Matcher.Description
			}
			// Clone the row: appending in place would overwrite the columns
			// written for the previous validation error.
			row := append(slices.Clone(fields), csvFields(
				optionalUUID(validationError.MatcherID), name, description, validationError.Message)...)
			if err = writer.Write(row); err != nil {
				return err
			}
		}
	}
	return writer.Write([]string{})
}

func optionalString(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// csvFormulaLeaders are the characters a spreadsheet reads as the start of a
// formula. A test case name or a validation message is user text, so a value
// that begins with one has to be neutralized before it is exported.
const csvFormulaLeaders = "=+-@\t\r"

// csvField makes value safe to open in a spreadsheet.
func csvField(value string) string {
	if value == "" || !strings.ContainsRune(csvFormulaLeaders, rune(value[0])) {
		return value
	}
	return "'" + value
}

// csvFields is csvField over a whole row.
func csvFields(values ...string) []string {
	escaped := make([]string, len(values))
	for i, value := range values {
		escaped[i] = csvField(value)
	}
	return escaped
}

func optionalUUID(id *uuid.UUID) string {
	if id == nil {
		return ""
	}
	return id.String()
}

func optionalTime(t *time.Time) string {
	if t == nil {
		return ""
	}
	return t.Format(time.RFC3339Nano)
}
