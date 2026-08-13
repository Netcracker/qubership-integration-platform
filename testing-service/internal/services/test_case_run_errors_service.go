package services

import (
	"bytes"
	"context"
	"encoding/csv"

	"github.com/google/uuid"
	"github.com/uptrace/bun"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
)

// TestCaseRunErrorsService records and reports the validation errors of a test
// case run.
type TestCaseRunErrorsService interface {
	FindByTestCaseRunId(ctx context.Context, id uuid.UUID, withMatchers bool) (*[]dao.ValidationError, error)
	AddError(
		ctx context.Context,
		testCaseRunID uuid.UUID,
		owner uuid.UUID,
		matcher *dao.Matcher,
		message string,
	) (*dao.ValidationError, error)
	BulkExport(ctx context.Context, ids []uuid.UUID) (string, error)
	BulkExportToCsv(ctx context.Context, ids []uuid.UUID, writer *csv.Writer) error
}

type testCaseRunErrorsService struct {
	runner       dao.Runner
	repositories Repositories
}

// NewTestCaseRunErrorsService returns a TestCaseRunErrorsService over the given
// database access.
func NewTestCaseRunErrorsService(runner dao.Runner, repositories Repositories) TestCaseRunErrorsService {
	return &testCaseRunErrorsService{runner: runner, repositories: repositories}
}

func (s *testCaseRunErrorsService) FindByTestCaseRunId(
	ctx context.Context,
	id uuid.UUID,
	withMatchers bool,
) (*[]dao.ValidationError, error) {
	return dao.Run(ctx, s.runner, func(ctx context.Context, _ bun.IDB) (*[]dao.ValidationError, error) {
		return s.repositories.TestCaseRunErrors.FindByTestCaseRunId(ctx, id, withMatchers)
	})
}

// AddError records a validation error against the attempt owner claimed. The
// write is fenced on owner, so a worker whose lease was swept cannot report its
// findings against the attempt that replaced it.
func (s *testCaseRunErrorsService) AddError(
	ctx context.Context,
	testCaseRunID uuid.UUID,
	owner uuid.UUID,
	matcher *dao.Matcher,
	message string,
) (*dao.ValidationError, error) {
	return dao.RunInTx(ctx, s.runner, defaultTxOptions(), func(ctx context.Context, _ bun.IDB) (*dao.ValidationError, error) {
		validationError := dao.ValidationError{TestCaseRunID: &testCaseRunID, Message: message}
		if matcher != nil {
			validationError.MatcherID = &matcher.ID
		}
		createdValidationError, err := s.repositories.TestCaseRunErrors.InsertOwned(ctx, &validationError, owner)
		if err != nil {
			return nil, err
		}
		createdValidationError.Matcher = matcher
		return createdValidationError, nil
	})
}

func (s *testCaseRunErrorsService) BulkExport(ctx context.Context, ids []uuid.UUID) (string, error) {
	var buffer bytes.Buffer
	writer := csv.NewWriter(&buffer)
	if err := s.BulkExportToCsv(ctx, ids, writer); err != nil {
		return "", err
	}
	writer.Flush()
	return buffer.String(), writer.Error()
}

func (s *testCaseRunErrorsService) BulkExportToCsv(ctx context.Context, ids []uuid.UUID, writer *csv.Writer) error {
	return runQuery(ctx, s.runner, func(ctx context.Context) error {
		validationErrors, err := s.repositories.TestCaseRunErrors.FindByIds(ctx, ids, true)
		if err != nil {
			return err
		}
		if validationErrors == nil || len(*validationErrors) == 0 {
			return nil
		}
		if err = writer.Write([]string{"Rule", "Message"}); err != nil {
			return err
		}
		for _, validationError := range *validationErrors {
			if err = writer.Write([]string{getRuleName(validationError), validationError.Message}); err != nil {
				return err
			}
		}
		return writer.Write([]string{})
	})
}

func getRuleName(validationError dao.ValidationError) string {
	switch {
	case validationError.Matcher != nil:
		return validationError.Matcher.Name
	case validationError.MatcherID != nil:
		return validationError.MatcherID.String()
	default:
		return "N/A"
	}
}
