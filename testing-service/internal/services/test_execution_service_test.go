package services

import (
	"context"
	"encoding/csv"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/triggers"
)

type fakeTrigger struct {
	response  *model.Exchange
	err       error
	sessionID string
}

func (t *fakeTrigger) Activate(ctx context.Context, _ *dao.RequestSettings) (*model.Exchange, error) {
	t.sessionID, _ = triggers.SessionID(ctx)
	if t.err != nil {
		return nil, t.err
	}
	return t.response, nil
}

type stubTestCasesService struct {
	TestCasesService

	testCase *dao.TestCaseView
	err      error
}

func (s *stubTestCasesService) FindById(context.Context, uuid.UUID) (*dao.TestCaseView, error) {
	return s.testCase, s.err
}

type stubTestCaseRunsService struct {
	TestCaseRunsService

	finished []uuid.UUID
	skipped  []uuid.UUID
	owners   []uuid.UUID
}

func (s *stubTestCaseRunsService) Finish(_ context.Context, id uuid.UUID, owner uuid.UUID) error {
	s.finished = append(s.finished, id)
	s.owners = append(s.owners, owner)
	return nil
}

func (s *stubTestCaseRunsService) Skip(_ context.Context, id uuid.UUID, owner uuid.UUID) error {
	s.skipped = append(s.skipped, id)
	s.owners = append(s.owners, owner)
	return nil
}

type stubTestCaseRunErrorsService struct {
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
	_ uuid.UUID,
	owner uuid.UUID,
	matcher *dao.Matcher,
	message string,
) (*dao.ValidationError, error) {
	s.messages = append(s.messages, message)
	s.matchers = append(s.matchers, matcher)
	s.owners = append(s.owners, owner)
	return &dao.ValidationError{}, nil
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
	fixture := &executionFixture{
		testCases: &stubTestCasesService{testCase: testCase},
		runs:      &stubTestCaseRunsService{},
		runErrors: &stubTestCaseRunErrorsService{},
		resolver:  &stubTriggerResolverService{trigger: trigger},
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	fixture.service = NewTestExecutionService(
		config.Config{},
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

	assert.Equal(t, *testCaseRun.SessionID, trigger.sessionID)
	assert.NotEmpty(t, trigger.sessionID)
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
