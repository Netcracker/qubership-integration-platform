package services

import (
	"context"
	"encoding/csv"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/triggers"
)

type fakeTrigger struct {
	response *model.Exchange
	err      error
	// delay holds the activation, standing in for a test case that takes longer
	// than one lease.
	delay time.Duration

	mutex     sync.Mutex
	sessionID string
}

func (t *fakeTrigger) Activate(ctx context.Context, _ *dao.RequestSettings) (*model.Exchange, error) {
	sessionID, _ := triggers.SessionID(ctx)
	t.mutex.Lock()
	t.sessionID = sessionID
	t.mutex.Unlock()
	if t.delay > 0 {
		time.Sleep(t.delay)
	}
	if t.err != nil {
		return nil, t.err
	}
	return t.response, nil
}

func (t *fakeTrigger) lastSessionID() string {
	t.mutex.Lock()
	defer t.mutex.Unlock()
	return t.sessionID
}

type stubTestCasesService struct {
	TestCasesService

	testCase *dao.TestCaseView
	err      error
}

func (s *stubTestCasesService) FindById(context.Context, uuid.UUID) (*dao.TestCaseView, error) {
	return s.testCase, s.err
}

// stubTestCaseRunsService stands in for the queue. The worker pool reaches it
// from several goroutines at once, so everything it records is guarded; the
// accessors below are what a test reads while the pool is running.
type stubTestCaseRunsService struct {
	TestCaseRunsService

	mutex       sync.Mutex
	queued      []*dao.TestCaseRun
	claims      int
	claimErr    error
	reclaimable int
	reclaims    int
	renewals    []uuid.UUID
	finished    []uuid.UUID
	skipped     []uuid.UUID
	owners      []uuid.UUID
}

func (s *stubTestCaseRunsService) Finish(_ context.Context, id uuid.UUID, owner uuid.UUID) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.finished = append(s.finished, id)
	s.owners = append(s.owners, owner)
	return nil
}

func (s *stubTestCaseRunsService) Skip(_ context.Context, id uuid.UUID, owner uuid.UUID) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.skipped = append(s.skipped, id)
	s.owners = append(s.owners, owner)
	return nil
}

// ClaimNext hands out the queued runs one at a time, stamped the way the two-step
// claim stamps them.
func (s *stubTestCaseRunsService) ClaimNext(
	_ context.Context,
	owner uuid.UUID,
	sessionID string,
) (*dao.TestCaseRun, error) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.claims++
	if s.claimErr != nil {
		return nil, s.claimErr
	}
	if len(s.queued) == 0 {
		return nil, nil
	}
	status := dao.RunStatusRunning
	claimed := *s.queued[0]
	s.queued = s.queued[1:]
	claimed.Status = &status
	claimed.SessionID = &sessionID
	claimed.LeaseOwner = &owner
	return &claimed, nil
}

func (s *stubTestCaseRunsService) RenewLease(_ context.Context, _ uuid.UUID, owner uuid.UUID) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.renewals = append(s.renewals, owner)
	return nil
}

func (s *stubTestCaseRunsService) ReclaimExpired(context.Context) (int, error) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.reclaims++
	return s.reclaimable, nil
}

func (s *stubTestCaseRunsService) enqueue(testCaseRuns ...*dao.TestCaseRun) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.queued = append(s.queued, testCaseRuns...)
}

func (s *stubTestCaseRunsService) finishedRuns() []uuid.UUID {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return slices.Clone(s.finished)
}

func (s *stubTestCaseRunsService) renewalOwners() []uuid.UUID {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return slices.Clone(s.renewals)
}

func (s *stubTestCaseRunsService) claimCount() int {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return s.claims
}

func (s *stubTestCaseRunsService) reclaimCount() int {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return s.reclaims
}

// errorKey is the unique (test_case_run_id, matcher_id) that validation_errors
// carries. PostgreSQL treats a null matcher as distinct, so only a recorded
// matcher takes part.
type errorKey struct {
	testCaseRunID uuid.UUID
	matcherID     uuid.UUID
}

// errDuplicateValidationError is what the constraint raises when a second
// attempt records a matcher the first one already failed on.
var errDuplicateValidationError = errors.New("duplicate key value violates unique constraint")

type stubTestCaseRunErrorsService struct {
	mutex    sync.Mutex
	recorded map[errorKey]bool
	messages []string
	matchers []*dao.Matcher
	owners   []uuid.UUID
}

func (s *stubTestCaseRunErrorsService) FindByTestCaseRunId(
	context.Context, uuid.UUID, bool,
) (*[]dao.ValidationError, error) {
	return nil, nil
}

func (s *stubTestCaseRunErrorsService) AddError(
	_ context.Context,
	testCaseRunID uuid.UUID,
	owner uuid.UUID,
	matcher *dao.Matcher,
	message string,
) (*dao.ValidationError, error) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if matcher != nil {
		key := errorKey{testCaseRunID: testCaseRunID, matcherID: matcher.ID}
		if s.recorded[key] {
			return nil, errDuplicateValidationError
		}
		if s.recorded == nil {
			s.recorded = map[errorKey]bool{}
		}
		s.recorded[key] = true
	}
	s.messages = append(s.messages, message)
	s.matchers = append(s.matchers, matcher)
	s.owners = append(s.owners, owner)
	return &dao.ValidationError{}, nil
}

// discard drops what one attempt recorded, the way the sweeper's statement drops
// it when it returns the case to the queue.
func (s *stubTestCaseRunErrorsService) discard(testCaseRunID uuid.UUID) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	for key := range s.recorded {
		if key.testCaseRunID == testCaseRunID {
			delete(s.recorded, key)
		}
	}
}

func (s *stubTestCaseRunErrorsService) BulkExport(context.Context, []uuid.UUID) (string, error) {
	return "", nil
}

func (s *stubTestCaseRunErrorsService) BulkExportToCsv(context.Context, []uuid.UUID, *csv.Writer) error {
	return nil
}

type stubTriggerResolverService struct {
	trigger triggers.Trigger
	err     error
}

func (s *stubTriggerResolverService) ResolveTrigger(context.Context, *dao.TriggerReference) (triggers.Trigger, error) {
	return s.trigger, s.err
}

type executionFixture struct {
	service   *testExecutionService
	testCases *stubTestCasesService
	runs      *stubTestCaseRunsService
	runErrors *stubTestCaseRunErrorsService
	resolver  *stubTriggerResolverService
}

func newExecutionFixture(testCase *dao.TestCaseView, trigger triggers.Trigger) *executionFixture {
	return newExecutionFixtureWith(config.Config{}, testCase, trigger)
}

func newExecutionFixtureWith(
	cfg config.Config,
	testCase *dao.TestCaseView,
	trigger triggers.Trigger,
) *executionFixture {
	fixture := &executionFixture{
		testCases: &stubTestCasesService{testCase: testCase},
		runs:      &stubTestCaseRunsService{},
		runErrors: &stubTestCaseRunErrorsService{},
		resolver:  &stubTriggerResolverService{trigger: trigger},
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	fixture.service = NewTestExecutionService(
		cfg,
		logger,
		fixture.testCases,
		fixture.runs,
		fixture.runErrors,
		fixture.resolver,
	).(*testExecutionService)
	return fixture
}

// claimedRun is what the claim hands the executor: already running, leased to an
// owner and carrying the session the trigger has to report.
func claimedRun() *dao.TestCaseRun {
	testCaseID := uuid.New()
	owner := uuid.New()
	sessionID := uuid.NewString()
	status := dao.RunStatusRunning
	return &dao.TestCaseRun{
		ID:         uuid.New(),
		TestCaseID: &testCaseID,
		Status:     &status,
		SessionID:  &sessionID,
		LeaseOwner: &owner,
	}
}

// queuedRun is a case run as the queue holds it, before a claim stamps it.
func queuedRun() *dao.TestCaseRun {
	testCaseID := uuid.New()
	return &dao.TestCaseRun{ID: uuid.New(), TestCaseID: &testCaseID}
}

func enabledTestCase(rules ...*dao.Matcher) *dao.TestCaseView {
	return &dao.TestCaseView{TestCase: dao.TestCase{
		ID:                      uuid.New(),
		Name:                    "order flow",
		Enabled:                 true,
		TriggerReference:        &dao.TriggerReference{ChainID: "chain-1", ElementID: "element-1"},
		RequestSettings:         &dao.RequestSettings{Method: dao.HttpMethodGet},
		ResponseValidationRules: rules,
	}}
}

func TestRunTestCaseSkipsADisabledTestCase(t *testing.T) {
	testCase := enabledTestCase()
	testCase.Enabled = false
	fixture := newExecutionFixture(testCase, &fakeTrigger{})
	testCaseRun := claimedRun()

	fixture.service.runTestCase(context.Background(), testCaseRun)

	assert.Equal(t, []uuid.UUID{testCaseRun.ID}, fixture.runs.skipped)
	assert.Empty(t, fixture.runs.finished)
	assert.Equal(t, []uuid.UUID{*testCaseRun.LeaseOwner}, fixture.runs.owners, "the skip names the owner")
}

func TestRunTestCaseFinishesWithAnErrorWhenTheTestCaseIsGone(t *testing.T) {
	fixture := newExecutionFixture(nil, &fakeTrigger{})
	testCaseRun := claimedRun()

	fixture.service.runTestCase(context.Background(), testCaseRun)

	// A missing test case never comes back, so the run has to reach a terminal
	// state rather than wait for the sweeper to hand it out again.
	require.Len(t, fixture.runErrors.messages, 1)
	assert.Contains(t, fixture.runErrors.messages[0], testCaseRun.TestCaseID.String())
	assert.Equal(t, []uuid.UUID{testCaseRun.ID}, fixture.runs.finished)
	assert.Empty(t, fixture.runs.skipped)
}

func TestRunTestCaseLeavesTheLeaseToExpireOnAFailingTestCaseLookup(t *testing.T) {
	fixture := newExecutionFixture(enabledTestCase(), &fakeTrigger{})
	fixture.testCases.err = errors.New("no connection")

	fixture.service.runTestCase(context.Background(), claimedRun())

	// The failure may be transient, so the sweeper gets to return the case to
	// the queue instead of the executor writing it off.
	assert.Empty(t, fixture.runs.finished)
	assert.Empty(t, fixture.runErrors.messages)
}

func TestRunTestCaseIgnoresARunThatCarriesNoLeaseOwner(t *testing.T) {
	fixture := newExecutionFixture(enabledTestCase(), &fakeTrigger{})
	testCaseRun := claimedRun()
	testCaseRun.LeaseOwner = nil

	fixture.service.runTestCase(context.Background(), testCaseRun)

	assert.Empty(t, fixture.runs.finished, "without an owner token no write could be fenced")
	assert.Empty(t, fixture.runErrors.messages)
}

func TestRunTestCaseRecordsAFailingTriggerResolutionAndStillFinishes(t *testing.T) {
	fixture := newExecutionFixture(enabledTestCase(), nil)
	fixture.resolver.err = errors.New("element not found")

	fixture.service.runTestCase(context.Background(), claimedRun())

	require.Len(t, fixture.runErrors.messages, 1)
	assert.Contains(t, fixture.runErrors.messages[0], "Failed to resolve trigger")
	assert.Nil(t, fixture.runErrors.matchers[0])
	assert.Len(t, fixture.runs.finished, 1)
}

func TestRunTestCaseRecordsAFailingActivationAndStillFinishes(t *testing.T) {
	trigger := &fakeTrigger{err: errors.New("connection refused")}
	fixture := newExecutionFixture(enabledTestCase(), trigger)

	fixture.service.runTestCase(context.Background(), claimedRun())

	require.Len(t, fixture.runErrors.messages, 1)
	assert.Contains(t, fixture.runErrors.messages[0], "Failed to activate trigger")
	assert.Len(t, fixture.runs.finished, 1)
}

func TestRunTestCaseHandsTheClaimedSessionIdentifierToTheTrigger(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusOK}}
	fixture := newExecutionFixture(enabledTestCase(), trigger)
	testCaseRun := claimedRun()

	fixture.service.runTestCase(context.Background(), testCaseRun)

	assert.Equal(t, *testCaseRun.SessionID, trigger.lastSessionID())
	assert.NotEmpty(t, trigger.lastSessionID())
	assert.Empty(t, fixture.runErrors.messages)
	assert.Len(t, fixture.runs.finished, 1)
}

func TestRunTestCaseFencesEveryWriteOnTheClaimedOwner(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusInternalServerError}}
	fixture := newExecutionFixture(enabledTestCase(statusRule("200", true)), trigger)
	testCaseRun := claimedRun()
	owner := *testCaseRun.LeaseOwner

	fixture.service.runTestCase(context.Background(), testCaseRun)

	assert.Equal(t, []uuid.UUID{owner}, fixture.runErrors.owners, "the recorded error names the owner")
	assert.Equal(t, []uuid.UUID{owner}, fixture.runs.owners, "the finish names the owner")
}

func statusRule(expected string, enabled bool) *dao.Matcher {
	return &dao.Matcher{
		ID:         uuid.New(),
		Enabled:    enabled,
		Type:       "equal",
		EntityType: dao.EntityTypeStatus,
		Parameters: []*dao.MatcherParameter{{Name: "value", Value: expected}},
	}
}

func TestRunTestCaseRecordsAValidationRuleThatDoesNotHold(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusInternalServerError}}
	rule := statusRule("200", true)
	fixture := newExecutionFixture(enabledTestCase(rule), trigger)

	fixture.service.runTestCase(context.Background(), claimedRun())

	require.Len(t, fixture.runErrors.messages, 1)
	assert.Equal(t, rule, fixture.runErrors.matchers[0])
	assert.Contains(t, fixture.runErrors.messages[0], "500")
}

func TestRunTestCaseIgnoresDisabledValidationRules(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusInternalServerError}}
	fixture := newExecutionFixture(enabledTestCase(statusRule("200", false), nil), trigger)

	fixture.service.runTestCase(context.Background(), claimedRun())

	assert.Empty(t, fixture.runErrors.messages)
	assert.Len(t, fixture.runs.finished, 1)
}

func TestRunTestCaseFinishesWithAnErrorWhenTheRunReferencesNoTestCase(t *testing.T) {
	fixture := newExecutionFixture(enabledTestCase(), &fakeTrigger{})
	testCaseRun := claimedRun()
	testCaseRun.TestCaseID = nil

	fixture.service.runTestCase(context.Background(), testCaseRun)

	require.Len(t, fixture.runErrors.messages, 1)
	assert.Equal(t, []uuid.UUID{testCaseRun.ID}, fixture.runs.finished)
}

// shortLeases makes the executor renew every 10 ms and sweep every 15 ms, which
// is what lets a test observe both without waiting out a real lease.
func shortLeases() config.Config {
	return config.Config{LeaseDuration: 30 * time.Millisecond}
}

func TestRunTestCaseRenewsTheLeaseWhileTheCaseRuns(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusOK}, delay: 100 * time.Millisecond}
	fixture := newExecutionFixtureWith(shortLeases(), enabledTestCase(), trigger)
	testCaseRun := claimedRun()

	fixture.service.runTestCase(context.Background(), testCaseRun)

	renewals := fixture.runs.renewalOwners()
	require.NotEmpty(t, renewals, "a case outliving its lease has to keep the claim alive")
	for _, owner := range renewals {
		assert.Equal(t, *testCaseRun.LeaseOwner, owner, "a renewal names the owner the case was claimed under")
	}
}

func TestRunTestCaseStopsRenewingOnceTheCaseIsDone(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusOK}}
	fixture := newExecutionFixtureWith(shortLeases(), enabledTestCase(), trigger)

	fixture.service.runTestCase(context.Background(), claimedRun())
	renewals := len(fixture.runs.renewalOwners())
	time.Sleep(5 * fixture.service.renewInterval)

	assert.Len(t, fixture.runs.renewalOwners(), renewals,
		"the renewal outlived the case and holds a lease nobody is working under")
}

func TestClaimAndRunKeepsTakingCasesUntilTheQueueIsEmpty(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusOK}}
	fixture := newExecutionFixture(enabledTestCase(), trigger)
	fixture.runs.enqueue(queuedRun(), queuedRun())

	fixture.service.claimAndRun(context.Background())

	assert.Len(t, fixture.runs.finishedRuns(), 2)
	assert.Equal(t, 3, fixture.runs.claimCount(), "the empty claim is what ends the pass")
}

func TestClaimAndRunStopsOnAFailingClaim(t *testing.T) {
	fixture := newExecutionFixture(enabledTestCase(), &fakeTrigger{})
	fixture.runs.claimErr = errors.New("no connection")

	fixture.service.claimAndRun(context.Background())

	assert.Equal(t, 1, fixture.runs.claimCount(), "a failing claim waits for the next pass rather than spinning")
}

func TestRunStartsQueuedWorkOnASignalRatherThanOnTheNextPoll(t *testing.T) {
	cfg := config.Config{PollInterval: time.Hour, WorkerCount: 2, LeaseDuration: time.Hour}
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusOK}}
	fixture := newExecutionFixtureWith(cfg, enabledTestCase(), trigger)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		fixture.service.Run(ctx)
	}()

	queued := queuedRun()
	fixture.runs.enqueue(queued)
	fixture.service.NotifyWork()

	require.Eventually(t, func() bool {
		return len(fixture.runs.finishedRuns()) == 1
	}, 5*time.Second, 5*time.Millisecond, "the poll interval is an hour, so only the signal can start this run")

	cancel()
	select {
	case <-stopped:
	case <-time.After(5 * time.Second):
		t.Fatal("Run did not return after the context was canceled")
	}
	assert.Equal(t, []uuid.UUID{queued.ID}, fixture.runs.finishedRuns())
}

func TestSweepReturnsExpiredLeasesToTheQueueAndWakesAWorker(t *testing.T) {
	fixture := newExecutionFixtureWith(shortLeases(), enabledTestCase(), &fakeTrigger{})
	fixture.runs.reclaimable = 2
	ctx, cancel := context.WithCancel(context.Background())

	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		fixture.service.sweepExpiredLeases(ctx)
	}()

	require.Eventually(t, func() bool {
		return fixture.runs.reclaimCount() > 0
	}, 5*time.Second, time.Millisecond)

	cancel()
	<-stopped
	assert.Len(t, fixture.service.wake, 1, "a reclaimed case has to wake a worker")
}

func TestARunReclaimedBySweeperRecordsItsErrorsAgainOnTheNextAttempt(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusInternalServerError}}
	fixture := newExecutionFixture(enabledTestCase(statusRule("200", true)), trigger)
	first := claimedRun()

	fixture.service.runTestCase(context.Background(), first)
	require.Len(t, fixture.runErrors.messages, 1)

	// The lease expired: the sweeper returned the case to the queue, dropped what
	// the first attempt recorded, and another worker claimed it under a token of
	// its own.
	fixture.runErrors.discard(first.ID)
	second := *first
	owner := uuid.New()
	second.LeaseOwner = &owner

	fixture.service.runTestCase(context.Background(), &second)

	require.Len(t, fixture.runErrors.messages, 2, "the second attempt records the same matcher again")
	assert.Equal(t, owner, fixture.runErrors.owners[1])
	assert.Len(t, fixture.runs.finishedRuns(), 2)
}

func TestASecondAttemptCollidesWhenTheErrorsOfTheFirstOneSurvive(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusInternalServerError}}
	fixture := newExecutionFixture(enabledTestCase(statusRule("200", true)), trigger)
	first := claimedRun()

	fixture.service.runTestCase(context.Background(), first)
	second := *first
	owner := uuid.New()
	second.LeaseOwner = &owner
	fixture.service.runTestCase(context.Background(), &second)

	// This is what the sweeper's delete exists for: unique (test_case_run_id,
	// matcher_id) refuses the repeated error, and validation stops there.
	assert.Len(t, fixture.runErrors.messages, 1)
}

func TestBuildParametersMapGroupsRepeatedNames(t *testing.T) {
	parameters := buildParametersMap([]*dao.MatcherParameter{
		{Name: "value", Value: "first"},
		nil,
		{Name: "value", Value: "second"},
		{Name: "path", Value: "$.id"},
	})

	assert.Equal(t, map[string][]string{
		"value": {"first", "second"},
		"path":  {"$.id"},
	}, parameters)
}
