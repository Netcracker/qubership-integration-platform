package services

import (
	"context"
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
			testCaseRun, err := s.testCaseRunsService.FindPendingTestCaseRun(ctx)
			if err != nil {
				s.logger.ErrorContext(ctx, "Cannot read the next pending test case run", "error", err)
			}
			if testCaseRun == nil {
				s.logger.DebugContext(ctx, "No pending test case run to execute", "pollInterval", s.pollInterval)
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

func (s *testExecutionService) runTestCase(ctx context.Context, testCaseRun *dao.TestCaseRun) {
	if testCaseRun.TestCaseID == nil {
		s.logger.ErrorContext(ctx, "Test case run references no test case", "testCaseRunId", testCaseRun.ID)
		return
	}
	testCase, err := s.testCasesService.FindById(ctx, *testCaseRun.TestCaseID)
	if err != nil {
		s.logger.ErrorContext(ctx, "Cannot read the test case", "testCaseId", *testCaseRun.TestCaseID, "error", err)
		return
	}
	if testCase == nil {
		s.logger.ErrorContext(ctx, "Test case not found", "testCaseId", *testCaseRun.TestCaseID)
		return
	}

	if !testCase.Enabled {
		s.logger.InfoContext(ctx, "Skipping a disabled test case",
			"testCaseId", testCase.ID, "testCaseName", testCase.Name)
		if err = s.testCaseRunsService.Skip(ctx, testCaseRun.ID); err != nil {
			s.logger.ErrorContext(ctx, "Cannot skip the test case run", "testCaseRunId", testCaseRun.ID, "error", err)
		}
		return
	}

	sessionID := uuid.New().String()
	s.logger.InfoContext(ctx, "Starting a test case run",
		"testCaseRunId", testCaseRun.ID, "testCaseId", testCase.ID, "testCaseName", testCase.Name, "sessionId", sessionID)
	if err = s.testCaseRunsService.Start(ctx, testCaseRun.ID, sessionID); err != nil {
		s.logger.ErrorContext(ctx, "Cannot start the test case run", "testCaseRunId", testCaseRun.ID, "error", err)
		return
	}

	s.activateAndValidate(ctx, testCaseRun.ID, testCase, sessionID)

	if err = s.testCaseRunsService.Finish(ctx, testCaseRun.ID); err != nil {
		s.logger.ErrorContext(ctx, "Cannot finish the test case run", "testCaseRunId", testCaseRun.ID, "error", err)
	}
	s.logger.InfoContext(ctx, "Finished a test case run",
		"testCaseRunId", testCaseRun.ID, "testCaseId", testCase.ID, "testCaseName", testCase.Name)
}

// activateAndValidate activates the trigger of testCase and validates what came
// back. Every failure is recorded against the test case run rather than returned:
// the run itself still finishes.
func (s *testExecutionService) activateAndValidate(
	ctx context.Context,
	testCaseRunID uuid.UUID,
	testCase *dao.TestCaseView,
	sessionID string,
) {
	trigger, err := s.triggerResolverService.ResolveTrigger(ctx, testCase.TriggerReference)
	if err != nil {
		s.logger.ErrorContext(ctx, "Cannot resolve the trigger", "testCaseRunId", testCaseRunID, "error", err)
		s.recordError(ctx, testCaseRunID, nil, fmt.Sprintf("Failed to resolve trigger: %v", err))
		return
	}

	response, err := trigger.Activate(triggers.WithSessionID(ctx, sessionID), testCase.RequestSettings)
	if err != nil {
		s.logger.ErrorContext(ctx, "Cannot activate the trigger", "testCaseRunId", testCaseRunID, "error", err)
		s.recordError(ctx, testCaseRunID, nil, fmt.Sprintf("Failed to activate trigger: %v", err))
		return
	}

	if err = s.validateResponse(ctx, testCaseRunID, response, testCase.ResponseValidationRules); err != nil {
		s.logger.ErrorContext(ctx, "Cannot validate the response", "testCaseRunId", testCaseRunID, "error", err)
	}
}

func (s *testExecutionService) validateResponse(
	ctx context.Context,
	testCaseRunID uuid.UUID,
	response *model.Exchange,
	validationRules []*dao.Matcher,
) error {
	for _, rule := range validationRules {
		if rule == nil || !rule.Enabled {
			continue
		}
		if err := s.validateResponseWithRule(ctx, testCaseRunID, response, rule); err != nil {
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
	response *model.Exchange,
	rule *dao.Matcher,
) error {
	var name string
	if rule.EntityName != nil {
		name = *rule.EntityName
	}
	dataGetter, err := matching.GetEntityDataGetter(rule.EntityType, name)
	if err != nil {
		return s.handleValidationError(ctx, testCaseRunID, rule, err)
	}
	data, err := dataGetter.GetData(*response)
	if err != nil {
		return s.handleValidationError(ctx, testCaseRunID, rule, err)
	}
	predicate, err := matching.GetMatcherPredicate(rule.Type, buildParametersMap(rule.Parameters))
	if err != nil {
		return s.handleValidationError(ctx, testCaseRunID, rule, err)
	}
	if err = predicate.Test(data); err != nil {
		return s.handleValidationError(ctx, testCaseRunID, rule, err)
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
	matcher *dao.Matcher,
	err error,
) error {
	_, storeErr := s.testCaseRunErrorsService.AddError(ctx, testCaseRunID, matcher, err.Error())
	return storeErr
}

func (s *testExecutionService) recordError(ctx context.Context, testCaseRunID uuid.UUID, matcher *dao.Matcher, message string) {
	if _, err := s.testCaseRunErrorsService.AddError(ctx, testCaseRunID, matcher, message); err != nil {
		s.logger.ErrorContext(ctx, "Cannot record the test case run error",
			"testCaseRunId", testCaseRunID, "error", err)
	}
}
