package services

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
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
	// Run executes queued test cases until ctx is canceled.
	Run(ctx context.Context)
	// NotifyWork reports that work was queued.
	NotifyWork()
}

type testExecutionService struct {
	logger        *slog.Logger
	workerCount   int
	pollInterval  time.Duration
	renewInterval time.Duration
	sweepInterval time.Duration
	// wake carries the signal a queue writer sends. It holds one token: a worker
	// that takes it drains the queue and signals on in turn, so a second pending
	// token would buy nothing.
	wake                     chan struct{}
	testCasesService         TestCasesService
	testCaseRunsService      TestCaseRunsService
	testCaseRunErrorsService TestCaseRunErrorsService
	triggerResolverService   TriggerResolverService
}

// NewTestExecutionService returns a TestExecutionService over the worker count,
// the poll interval and the lease duration cfg carries.
func NewTestExecutionService(
	cfg config.Config,
	logger *slog.Logger,
	testCasesService TestCasesService,
	testCaseRunsService TestCaseRunsService,
	testCaseRunErrorsService TestCaseRunErrorsService,
	triggerResolverService TriggerResolverService,
) TestExecutionService {
	cfg = cfg.WithDefaults()
	return &testExecutionService{
		logger:       logger,
		workerCount:  cfg.WorkerCount,
		pollInterval: cfg.PollInterval,
		// A running case renews three times per lease and the sweeper looks
		// twice, so a slow case keeps its claim while an abandoned one waits at
		// most half a lease longer than the lease itself.
		renewInterval:            fractionOf(cfg.LeaseDuration, 3),
		sweepInterval:            fractionOf(cfg.LeaseDuration, 2),
		wake:                     make(chan struct{}, 1),
		testCasesService:         testCasesService,
		testCaseRunsService:      testCaseRunsService,
		testCaseRunErrorsService: testCaseRunErrorsService,
		triggerResolverService:   triggerResolverService,
	}
}

// fractionOf divides d, never returning an interval a ticker would reject.
func fractionOf(d time.Duration, divisor int) time.Duration {
	fraction := d / time.Duration(divisor)
	if fraction <= 0 {
		return time.Nanosecond
	}
	return fraction
}

// Run executes queued test cases until ctx is canceled: a pool of workers, each
// claiming a case of its own under a fresh owner token, plus the sweeper that
// returns expired leases to the queue. It returns once they have all stopped.
//
// Cancellation stops the pool where it stands rather than draining the queue.
// The case a worker was on keeps its lease until it expires, and the sweeper —
// here or in another replica — hands it out again.
func (s *testExecutionService) Run(ctx context.Context) {
	s.logger.InfoContext(ctx, "Starting the test executor",
		"workers", s.workerCount, "pollInterval", s.pollInterval)

	var running sync.WaitGroup
	running.Add(s.workerCount + 1)
	for range s.workerCount {
		go func() {
			defer running.Done()
			s.work(ctx)
		}()
	}
	go func() {
		defer running.Done()
		s.sweepExpiredLeases(ctx)
	}()
	running.Wait()

	s.logger.InfoContext(ctx, "Stopped the test executor")
}

// NotifyWork wakes one worker. The signal is dropped when a wake-up is already
// pending, and the ticker covers a signal that never arrived.
func (s *testExecutionService) NotifyWork() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *testExecutionService) work(ctx context.Context) {
	ticker := time.NewTicker(s.pollInterval)
	defer ticker.Stop()
	for {
		s.claimAndRun(ctx)
		select {
		case <-ctx.Done():
			return
		case <-s.wake:
		case <-ticker.C:
		}
	}
}

// claimAndRun takes cases one at a time until the queue hands out nothing more.
func (s *testExecutionService) claimAndRun(ctx context.Context) {
	for ctx.Err() == nil {
		// A fresh owner token per claim: it fences every write this worker then
		// makes about the case.
		testCaseRun, err := s.testCaseRunsService.ClaimNext(ctx, uuid.New(), uuid.NewString())
		if err != nil {
			s.logger.ErrorContext(ctx, "Cannot claim the next test case run", "error", err)
			return
		}
		if testCaseRun == nil {
			s.logger.DebugContext(ctx, "No test case run to claim", "pollInterval", s.pollInterval)
			return
		}
		// Another run may have a case of its own waiting, and this worker is
		// busy for as long as the one it just took.
		s.NotifyWork()
		s.runTestCase(ctx, testCaseRun)
	}
}

// sweepExpiredLeases returns the cases of workers that stopped reporting to the
// queue, one guarded statement at a time.
func (s *testExecutionService) sweepExpiredLeases(ctx context.Context) {
	ticker := time.NewTicker(s.sweepInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		reclaimed, err := s.testCaseRunsService.ReclaimExpired(ctx)
		if err != nil {
			s.logger.ErrorContext(ctx, "Cannot reclaim the test case runs with an expired lease", "error", err)
			continue
		}
		if reclaimed > 0 {
			s.logger.InfoContext(ctx, "Returned test case runs with an expired lease to the queue",
				"testCaseRuns", reclaimed)
			s.NotifyWork()
		}
	}
}

// renewLease keeps the claim alive while the case runs, and returns the function
// that stops it. Without renewal the sweeper would take a case that merely runs
// longer than one lease and hand it to a second worker.
func (s *testExecutionService) renewLease(ctx context.Context, testCaseRunID uuid.UUID, owner uuid.UUID) func() {
	ctx, cancel := context.WithCancel(ctx)
	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		ticker := time.NewTicker(s.renewInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
			if err := s.testCaseRunsService.RenewLease(ctx, testCaseRunID, owner); err != nil {
				s.logWriteFailure(ctx, "Cannot renew the lease of the test case run", testCaseRunID, err)
				return
			}
		}
	}()
	return func() {
		cancel()
		<-stopped
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
	stopRenewal := s.renewLease(ctx, testCaseRun.ID, owner)
	defer stopRenewal()

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
