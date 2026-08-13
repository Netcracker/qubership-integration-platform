package services

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/matching"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/triggers"
)

// TestExecutionService runs the queued test case runs.
type TestExecutionService interface {
	Start(ctx context.Context)
	GracefullyStop(ctx context.Context)
}

type testExecutionService struct {
	logger                   *slog.Logger
	quit                     chan bool
	pollInterval             time.Duration
	testCasesService         TestCasesService
	testCaseRunsService      TestCaseRunsService
	testCaseRunErrorsService TestCaseRunErrorsService
	triggerResolverService   TriggerResolverService
}

// NewTestExecutionService returns a TestExecutionService polling the queue at the
// interval cfg carries.
func NewTestExecutionService(
	cfg config.Config,
	logger *slog.Logger,
	testCasesService TestCasesService,
	testCaseRunsService TestCaseRunsService,
	testCaseRunErrorsService TestCaseRunErrorsService,
	triggerResolverService TriggerResolverService,
) TestExecutionService {
	return &testExecutionService{
		logger:                   logger,
		quit:                     make(chan bool),
		pollInterval:             cfg.WithDefaults().PollInterval,
		testCasesService:         testCasesService,
		testCaseRunsService:      testCaseRunsService,
		testCaseRunErrorsService: testCaseRunErrorsService,
		triggerResolverService:   triggerResolverService,
	}
}

func (s *testExecutionService) Start(ctx context.Context) {
	s.logger.InfoContext(ctx, "Starting the test execution loop")
	go s.runTestCases(ctx)
}

func (s *testExecutionService) GracefullyStop(ctx context.Context) {
	s.logger.InfoContext(ctx, "Stopping the test execution loop")
	s.quit <- true
}

func (s *testExecutionService) runTestCases(ctx context.Context) {
	ticker := time.NewTicker(s.pollInterval)
	defer ticker.Stop()
	for {
		for {
			// A fresh owner token per claim: it fences every write this worker
			// then makes about the case.
			testCaseRun, err := s.testCaseRunsService.ClaimNext(ctx, uuid.New(), uuid.NewString())
			if err != nil {
				s.logger.ErrorContext(ctx, "Cannot claim the next test case run", "error", err)
			}
			if testCaseRun == nil {
				s.logger.DebugContext(ctx, "No test case run to claim", "pollInterval", s.pollInterval)
				ticker.Reset(s.pollInterval)
				break
			}
			s.runTestCase(ctx, testCaseRun)
		}

		select {
		case <-s.quit:
			return
		case <-ticker.C:
		}
	}
}

// runTestCase executes a test case run that the claim already stamped as running
// and leased. A permanent fault finishes the run with the fault recorded against
// it; a failure that may pass on the next attempt leaves the lease to expire, so
// the sweeper returns the case to the queue.
func (s *testExecutionService) runTestCase(ctx context.Context, testCaseRun *dao.TestCaseRun) {
	if testCaseRun.LeaseOwner == nil {
		s.logger.ErrorContext(ctx, "Claimed test case run carries no lease owner", "testCaseRunId", testCaseRun.ID)
		return
	}
	owner := *testCaseRun.LeaseOwner

	if testCaseRun.TestCaseID == nil {
		s.logger.ErrorContext(ctx, "Test case run references no test case", "testCaseRunId", testCaseRun.ID)
		s.recordError(ctx, testCaseRun.ID, owner, nil, "The test case run references no test case")
		s.finish(ctx, testCaseRun.ID, owner)
		return
	}
	testCase, err := s.testCasesService.FindById(ctx, *testCaseRun.TestCaseID)
	if err != nil {
		s.logger.ErrorContext(ctx, "Cannot read the test case", "testCaseId", *testCaseRun.TestCaseID, "error", err)
		return
	}
	if testCase == nil {
		s.logger.ErrorContext(ctx, "Test case not found", "testCaseId", *testCaseRun.TestCaseID)
		s.recordError(ctx, testCaseRun.ID, owner, nil,
			fmt.Sprintf("Test case not found: %v", *testCaseRun.TestCaseID))
		s.finish(ctx, testCaseRun.ID, owner)
		return
	}

	if !testCase.Enabled {
		s.logger.InfoContext(ctx, "Skipping a disabled test case",
			"testCaseId", testCase.ID, "testCaseName", testCase.Name)
		if err = s.testCaseRunsService.Skip(ctx, testCaseRun.ID, owner); err != nil {
			s.logWriteFailure(ctx, "Cannot skip the test case run", testCaseRun.ID, err)
		}
		return
	}

	sessionID := optionalString(testCaseRun.SessionID)
	s.logger.InfoContext(ctx, "Starting a test case run",
		"testCaseRunId", testCaseRun.ID, "testCaseId", testCase.ID, "testCaseName", testCase.Name, "sessionId", sessionID)

	s.activateAndValidate(ctx, testCaseRun.ID, owner, testCase, sessionID)

	s.finish(ctx, testCaseRun.ID, owner)
	s.logger.InfoContext(ctx, "Finished a test case run",
		"testCaseRunId", testCaseRun.ID, "testCaseId", testCase.ID, "testCaseName", testCase.Name)
}

func (s *testExecutionService) finish(ctx context.Context, testCaseRunID uuid.UUID, owner uuid.UUID) {
	if err := s.testCaseRunsService.Finish(ctx, testCaseRunID, owner); err != nil {
		s.logWriteFailure(ctx, "Cannot finish the test case run", testCaseRunID, err)
	}
}

// logWriteFailure reports a write that did not apply. A lost lease is not a
// fault of its own: the sweeper returned the case to the queue and another
// worker runs it.
func (s *testExecutionService) logWriteFailure(
	ctx context.Context,
	message string,
	testCaseRunID uuid.UUID,
	err error,
) {
	if errors.Is(err, dao.ErrLeaseLost) {
		s.logger.WarnContext(ctx, "Dropping a test case run that another worker owns now",
			"testCaseRunId", testCaseRunID, "error", err)
		return
	}
	s.logger.ErrorContext(ctx, message, "testCaseRunId", testCaseRunID, "error", err)
}

// activateAndValidate activates the trigger of testCase and validates what came
// back. Every failure is recorded against the test case run rather than returned:
// the run itself still finishes.
func (s *testExecutionService) activateAndValidate(
	ctx context.Context,
	testCaseRunID uuid.UUID,
	owner uuid.UUID,
	testCase *dao.TestCaseView,
	sessionID string,
) {
	trigger, err := s.triggerResolverService.ResolveTrigger(ctx, testCase.TriggerReference)
	if err != nil {
		s.logger.ErrorContext(ctx, "Cannot resolve the trigger", "testCaseRunId", testCaseRunID, "error", err)
		s.recordError(ctx, testCaseRunID, owner, nil, fmt.Sprintf("Failed to resolve trigger: %v", err))
		return
	}

	response, err := trigger.Activate(triggers.WithSessionID(ctx, sessionID), testCase.RequestSettings)
	if err != nil {
		s.logger.ErrorContext(ctx, "Cannot activate the trigger", "testCaseRunId", testCaseRunID, "error", err)
		s.recordError(ctx, testCaseRunID, owner, nil, fmt.Sprintf("Failed to activate trigger: %v", err))
		return
	}

	if err = s.validateResponse(ctx, testCaseRunID, owner, response, testCase.ResponseValidationRules); err != nil {
		s.logger.ErrorContext(ctx, "Cannot validate the response", "testCaseRunId", testCaseRunID, "error", err)
	}
}

func (s *testExecutionService) validateResponse(
	ctx context.Context,
	testCaseRunID uuid.UUID,
	owner uuid.UUID,
	response *model.Exchange,
	validationRules []*dao.Matcher,
) error {
	for _, rule := range validationRules {
		if rule == nil || !rule.Enabled {
			continue
		}
		if err := s.validateResponseWithRule(ctx, testCaseRunID, owner, response, rule); err != nil {
			return err
		}
	}
	return nil
}

// validateResponseWithRule returns an error only when recording the failure
// failed; a rule that does not hold is stored as a validation error.
func (s *testExecutionService) validateResponseWithRule(
	ctx context.Context,
	testCaseRunID uuid.UUID,
	owner uuid.UUID,
	response *model.Exchange,
	rule *dao.Matcher,
) error {
	var name string
	if rule.EntityName != nil {
		name = *rule.EntityName
	}
	dataGetter, err := matching.GetEntityDataGetter(rule.EntityType, name)
	if err != nil {
		return s.handleValidationError(ctx, testCaseRunID, owner, rule, err)
	}
	data, err := dataGetter.GetData(*response)
	if err != nil {
		return s.handleValidationError(ctx, testCaseRunID, owner, rule, err)
	}
	predicate, err := matching.GetMatcherPredicate(rule.Type, buildParametersMap(rule.Parameters))
	if err != nil {
		return s.handleValidationError(ctx, testCaseRunID, owner, rule, err)
	}
	if err = predicate.Test(data); err != nil {
		return s.handleValidationError(ctx, testCaseRunID, owner, rule, err)
	}
	return nil
}

func buildParametersMap(parameters []*dao.MatcherParameter) map[string][]string {
	result := map[string][]string{}
	for _, parameter := range parameters {
		if parameter == nil {
			continue
		}
		result[parameter.Name] = append(result[parameter.Name], parameter.Value)
	}
	return result
}

func (s *testExecutionService) handleValidationError(
	ctx context.Context,
	testCaseRunID uuid.UUID,
	owner uuid.UUID,
	matcher *dao.Matcher,
	err error,
) error {
	_, storeErr := s.testCaseRunErrorsService.AddError(ctx, testCaseRunID, owner, matcher, err.Error())
	return storeErr
}

func (s *testExecutionService) recordError(
	ctx context.Context,
	testCaseRunID uuid.UUID,
	owner uuid.UUID,
	matcher *dao.Matcher,
	message string,
) {
	if _, err := s.testCaseRunErrorsService.AddError(ctx, testCaseRunID, owner, matcher, message); err != nil {
		s.logWriteFailure(ctx, "Cannot record the test case run error", testCaseRunID, err)
	}
}
