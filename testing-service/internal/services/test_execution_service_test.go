package services

import (
	"bytes"
	"context"
	"encoding/csv"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/matching"
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

func (t *fakeTrigger) Activate(_ context.Context, sessionID string, _ *dao.RequestSettings) (*model.Exchange, error) {
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

type fakeTestCasesService struct {
	TestCasesService

	testCase *dao.TestCaseView
	err      error
}

func (s *fakeTestCasesService) FindById(context.Context, uuid.UUID) (*dao.TestCaseView, error) {
	return s.testCase, s.err
}

// fakeTestCaseRunsService stands in for the queue. The worker pool reaches it
// from several goroutines at once, so everything it records is guarded; the
// accessors below are what a test reads while the pool is running.
type fakeTestCaseRunsService struct {
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
	// The write failures the executor has to survive: a lost lease is a warning,
	// anything else a fault.
	finishErr error
	skipErr   error
	renewErr  error
}

func (s *fakeTestCaseRunsService) Finish(_ context.Context, id uuid.UUID, owner uuid.UUID) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.finished = append(s.finished, id)
	s.owners = append(s.owners, owner)
	return s.finishErr
}

func (s *fakeTestCaseRunsService) Skip(_ context.Context, id uuid.UUID, owner uuid.UUID) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.skipped = append(s.skipped, id)
	s.owners = append(s.owners, owner)
	return s.skipErr
}

// ClaimNext hands out the queued runs one at a time, stamped the way the two-step
// claim stamps them.
func (s *fakeTestCaseRunsService) ClaimNext(
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

func (s *fakeTestCaseRunsService) RenewLease(_ context.Context, _ uuid.UUID, owner uuid.UUID) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.renewals = append(s.renewals, owner)
	return s.renewErr
}

func (s *fakeTestCaseRunsService) ReclaimExpired(context.Context) (int, error) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.reclaims++
	return s.reclaimable, nil
}

func (s *fakeTestCaseRunsService) enqueue(testCaseRuns ...*dao.TestCaseRun) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.queued = append(s.queued, testCaseRuns...)
}

func (s *fakeTestCaseRunsService) finishedRuns() []uuid.UUID {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return slices.Clone(s.finished)
}

func (s *fakeTestCaseRunsService) renewalOwners() []uuid.UUID {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return slices.Clone(s.renewals)
}

func (s *fakeTestCaseRunsService) claimCount() int {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return s.claims
}

func (s *fakeTestCaseRunsService) reclaimCount() int {
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

type fakeTestCaseRunErrorsService struct {
	mutex sync.Mutex
	// attempts counts every AddError call, including the ones the duplicate key
	// refuses, which is how a test tells "the rule was never tried" from "the
	// rule was tried and rejected".
	attempts int
	recorded map[errorKey]bool
	messages []string
	matchers []*dao.Matcher
	owners   []uuid.UUID
}

func (s *fakeTestCaseRunErrorsService) FindByTestCaseRunId(
	context.Context, uuid.UUID, bool,
) (*[]dao.ValidationError, error) {
	return nil, nil
}

func (s *fakeTestCaseRunErrorsService) AddError(
	_ context.Context,
	testCaseRunID uuid.UUID,
	owner uuid.UUID,
	matcher *dao.Matcher,
	message string,
) (*dao.ValidationError, error) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.attempts++
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

func (s *fakeTestCaseRunErrorsService) attemptCount() int {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return s.attempts
}

// discard drops what one attempt recorded, the way the sweeper's statement drops
// it when it returns the case to the queue.
func (s *fakeTestCaseRunErrorsService) discard(testCaseRunID uuid.UUID) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	for key := range s.recorded {
		if key.testCaseRunID == testCaseRunID {
			delete(s.recorded, key)
		}
	}
}

func (s *fakeTestCaseRunErrorsService) BulkExport(context.Context, *[]uuid.UUID) (string, error) {
	return "", nil
}

func (s *fakeTestCaseRunErrorsService) BulkExportToCsv(context.Context, []uuid.UUID, *csv.Writer) error {
	return nil
}

type fakeTriggerResolverService struct {
	trigger triggers.Trigger
	err     error
}

func (s *fakeTriggerResolverService) ResolveTrigger(context.Context, *dao.TriggerReference) (triggers.Trigger, error) {
	return s.trigger, s.err
}

// logBuffer collects what the executor logged. The pool writes from several
// goroutines, so both ends go through the mutex.
type logBuffer struct {
	mutex sync.Mutex
	buf   bytes.Buffer
}

func (b *logBuffer) Write(p []byte) (int, error) {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	return b.buf.Write(p)
}

func (b *logBuffer) String() string {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	return b.buf.String()
}

type executionFixture struct {
	service   *testExecutionService
	testCases *fakeTestCasesService
	runs      *fakeTestCaseRunsService
	runErrors *fakeTestCaseRunErrorsService
	resolver  *fakeTriggerResolverService
	logs      *logBuffer
}

func newExecutionFixture(testCase *dao.TestCaseView, trigger triggers.Trigger) *executionFixture {
	return newExecutionFixtureWith(config.Config{}.WithDefaults(), testCase, trigger)
}

func newExecutionFixtureWith(
	cfg config.Config,
	testCase *dao.TestCaseView,
	trigger triggers.Trigger,
) *executionFixture {
	fixture := &executionFixture{
		testCases: &fakeTestCasesService{testCase: testCase},
		runs:      &fakeTestCaseRunsService{},
		runErrors: &fakeTestCaseRunErrorsService{},
		resolver:  &fakeTriggerResolverService{trigger: trigger},
		logs:      &logBuffer{},
	}
	logger := slog.New(slog.NewTextHandler(fixture.logs, nil))
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
		RequestSettings:         &dao.RequestSettings{Method: http.MethodGet},
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
		EntityType: matching.EntityTypeStatus,
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
	return config.Config{LeaseDuration: 30 * time.Millisecond}.WithDefaults()
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
	cfg := config.Config{PollInterval: time.Hour, WorkerCount: 2, LeaseDuration: time.Hour}.WithDefaults()
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

// The sweeper's own delete is covered against PostgreSQL in the integration
// suite; the stub stands in for it here, so what this test proves is that the
// retry records under the owner token of the worker that claimed it.
func TestASecondAttemptRecordsUnderItsOwnOwnerOnceTheEarlierErrorsAreGone(t *testing.T) {
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
	// Two failing rules, so the run still has something to validate after the
	// first store is refused.
	fixture := newExecutionFixture(enabledTestCase(statusRule("200", true), statusRule("201", true)), trigger)
	first := claimedRun()

	fixture.service.runTestCase(context.Background(), first)
	require.Equal(t, 2, fixture.runErrors.attemptCount(), "the first attempt records both rules")

	second := *first
	owner := uuid.New()
	second.LeaseOwner = &owner
	fixture.service.runTestCase(context.Background(), &second)

	// This is what the sweeper's delete exists for: unique (test_case_run_id,
	// matcher_id) refuses the repeated error, and validation stops there rather
	// than working through the rules that are left.
	assert.Len(t, fixture.runErrors.messages, 2)
	assert.Equal(t, 3, fixture.runErrors.attemptCount(),
		"the second rule may not be tried once storing the first one failed")
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

func TestClaimAndRunSignalsSoASiblingRunNeedNotWaitOutAPoll(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusOK}}
	fixture := newExecutionFixture(enabledTestCase(), trigger)
	fixture.runs.enqueue(queuedRun())
	require.Empty(t, fixture.service.wake)

	fixture.service.claimAndRun(context.Background())

	assert.Len(t, fixture.service.wake, 1,
		"a worker that takes a case has to wake a sibling, which is busy for as long as this one")
}

func TestFractionOfDividesTheDuration(t *testing.T) {
	assert.Equal(t, 30*time.Second, fractionOf(90*time.Second, 3))
	assert.Equal(t, 45*time.Second, fractionOf(90*time.Second, 2))
}

func TestFractionOfNeverReturnsAnIntervalATickerRejects(t *testing.T) {
	for _, d := range []time.Duration{0, -time.Second, time.Nanosecond} {
		assert.Positive(t, fractionOf(d, 3), "time.NewTicker panics on a non-positive interval")
	}
}

// A renewal that lands exactly when the lease runs out races the sweeper, which
// may have handed the case to a second worker already.
func TestTheRenewAndSweepCadenceLeaveRoomInsideTheLease(t *testing.T) {
	lease := 90 * time.Second
	fixture := newExecutionFixtureWith(config.Config{LeaseDuration: lease}.WithDefaults(), enabledTestCase(), nil)

	assert.Less(t, fixture.service.renewInterval, lease/2, "a lease has to be renewed at least twice over")
	assert.Less(t, fixture.service.sweepInterval, lease, "a sweep has to look at least once per lease")
}

func TestAFailingFinishIsReportedAsAFault(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusOK}}
	fixture := newExecutionFixture(enabledTestCase(), trigger)
	fixture.runs.finishErr = errors.New("no connection")

	fixture.service.runTestCase(context.Background(), claimedRun())

	logs := fixture.logs.String()
	assert.Contains(t, logs, "Cannot finish the test case run")
	assert.Contains(t, logs, "level=ERROR")
}

// A lost lease is not a fault of its own: the sweeper returned the case to the
// queue and another worker is running it.
func TestALostLeaseIsReportedAsAWarningRatherThanAFault(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusOK}}
	fixture := newExecutionFixture(enabledTestCase(), trigger)
	fixture.runs.finishErr = dao.ErrLeaseLost

	fixture.service.runTestCase(context.Background(), claimedRun())

	logs := fixture.logs.String()
	assert.Contains(t, logs, "Dropping a test case run that another worker owns now")
	assert.Contains(t, logs, "level=WARN")
	assert.NotContains(t, logs, "level=ERROR")
}

func TestAFailingSkipIsReported(t *testing.T) {
	testCase := enabledTestCase()
	testCase.Enabled = false
	fixture := newExecutionFixture(testCase, &fakeTrigger{})
	fixture.runs.skipErr = errors.New("no connection")

	fixture.service.runTestCase(context.Background(), claimedRun())

	assert.Contains(t, fixture.logs.String(), "Cannot skip the test case run")
}

// The renewal goroutine gives up on a lost lease: the claim is gone, and asking
// again under a token the queue no longer honors buys nothing.
func TestRenewalStopsAfterALostLease(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusOK}, delay: 100 * time.Millisecond}
	fixture := newExecutionFixtureWith(shortLeases(), enabledTestCase(), trigger)
	fixture.runs.renewErr = dao.ErrLeaseLost

	fixture.service.runTestCase(context.Background(), claimedRun())

	assert.Len(t, fixture.runs.renewalOwners(), 1, "the renewal has to stop rather than keep asking")
	assert.Contains(t, fixture.logs.String(), "Dropping a test case run that another worker owns now")
}

// Any other failure is a blip. Stopping on it spends the redundancy the cadence
// was chosen for: the lease expires under a worker that is still running the
// case, the sweeper returns the row to the queue, and a second worker activates
// the same chain while the first invocation is in flight.
func TestRenewalSurvivesATransientFailure(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusOK}, delay: 100 * time.Millisecond}
	fixture := newExecutionFixtureWith(shortLeases(), enabledTestCase(), trigger)
	fixture.runs.renewErr = errors.New("connection reset by peer")

	fixture.service.runTestCase(context.Background(), claimedRun())

	assert.Greater(t, len(fixture.runs.renewalOwners()), 1, "one blip stopped the renewal for good")
	assert.Contains(t, fixture.logs.String(), "Cannot renew the lease of the test case run")
}

// panickingTrigger stands in for a defect reached from one row: a panic in an
// executor worker, where nothing above it recovers.
type panickingTrigger struct{}

func (t *panickingTrigger) Activate(context.Context, string, *dao.RequestSettings) (*model.Exchange, error) {
	panic("nil map write")
}

// Request settings are optional on a test case, so an enabled case saved without
// them reaches the executor with a nil. The run has to end with the fault
// recorded: a crash here would leave the case running, the sweeper would return
// it to the queue, and the next worker would crash on it again.
func TestRunTestCaseRecordsAFaultForATestCaseWithoutRequestSettings(t *testing.T) {
	engine := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer engine.Close()
	trigger, err := triggers.NewHTTPTrigger(engine.URL, engine.Client(), map[string]any{"contextPath": "/orders"})
	require.NoError(t, err)
	testCase := enabledTestCase()
	testCase.RequestSettings = nil
	fixture := newExecutionFixture(testCase, trigger)
	testCaseRun := claimedRun()

	fixture.service.runTestCaseGuarded(context.Background(), testCaseRun)

	require.Len(t, fixture.runErrors.messages, 1)
	assert.Contains(t, fixture.runErrors.messages[0], "Failed to activate trigger")
	assert.Contains(t, fixture.runErrors.messages[0], "no request settings")
	assert.Equal(t, []uuid.UUID{testCaseRun.ID}, fixture.runs.finishedRuns())
	assert.NotContains(t, fixture.logs.String(), "Recovered from a panic", "no panic to recover from")
}

// One bad row must not take the worker pool down with it.
func TestRunTestCaseGuardedRecordsAPanicAsAFaultAndFinishesTheRun(t *testing.T) {
	fixture := newExecutionFixture(enabledTestCase(), &panickingTrigger{})
	testCaseRun := claimedRun()

	fixture.service.runTestCaseGuarded(context.Background(), testCaseRun)

	require.Len(t, fixture.runErrors.messages, 1)
	assert.Contains(t, fixture.runErrors.messages[0], "nil map write")
	assert.Equal(t, []uuid.UUID{testCaseRun.ID}, fixture.runs.finishedRuns(),
		"the run has to reach a terminal state or the sweeper hands it out again")
	assert.Contains(t, fixture.logs.String(), "Recovered from a panic while running a test case")
}

// The pool keeps working once it has written a panicking case off.
func TestTheExecutorKeepsClaimingAfterAPanickingTestCase(t *testing.T) {
	fixture := newExecutionFixture(enabledTestCase(), &panickingTrigger{})
	fixture.runs.enqueue(claimedRun(), claimedRun())

	fixture.service.claimAndRun(context.Background())

	assert.Len(t, fixture.runs.finishedRuns(), 2)
	assert.Len(t, fixture.runErrors.messages, 2)
}
