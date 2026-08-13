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

	started  []string
	finished int
	skipped  int
}

func (s *stubTestCaseRunsService) Start(_ context.Context, _ uuid.UUID, sessionID string) error {
	s.started = append(s.started, sessionID)
	return nil
}

func (s *stubTestCaseRunsService) Finish(context.Context, uuid.UUID) error {
	s.finished++
	return nil
}

func (s *stubTestCaseRunsService) Skip(context.Context, uuid.UUID) error {
	s.skipped++
	return nil
}

type stubTestCaseRunErrorsService struct {
	messages []string
	matchers []*dao.Matcher
}

func (s *stubTestCaseRunErrorsService) FindByTestCaseRunId(
	context.Context, uuid.UUID, bool,
) (*[]dao.ValidationError, error) {
	return nil, nil
}

func (s *stubTestCaseRunErrorsService) AddError(
	_ context.Context,
	_ uuid.UUID,
	matcher *dao.Matcher,
	message string,
) (*dao.ValidationError, error) {
	s.messages = append(s.messages, message)
	s.matchers = append(s.matchers, matcher)
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

func pendingRun() *dao.TestCaseRun {
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

	fixture.service.runTestCase(context.Background(), pendingRun())

	assert.Equal(t, 1, fixture.runs.skipped)
	assert.Empty(t, fixture.runs.started)
	assert.Zero(t, fixture.runs.finished)
}

func TestRunTestCaseStopsWhenTheTestCaseIsGone(t *testing.T) {
	fixture := newExecutionFixture(nil, &fakeTrigger{})

	fixture.service.runTestCase(context.Background(), pendingRun())

	assert.Empty(t, fixture.runs.started)
	assert.Zero(t, fixture.runs.finished)
	assert.Zero(t, fixture.runs.skipped)
}

func TestRunTestCaseStopsOnAFailingTestCaseLookup(t *testing.T) {
	fixture := newExecutionFixture(enabledTestCase(), &fakeTrigger{})
	fixture.testCases.err = errors.New("no connection")

	fixture.service.runTestCase(context.Background(), pendingRun())

	assert.Empty(t, fixture.runs.started)
	assert.Zero(t, fixture.runs.finished)
}

func TestRunTestCaseRecordsAFailingTriggerResolutionAndStillFinishes(t *testing.T) {
	fixture := newExecutionFixture(enabledTestCase(), nil)
	fixture.resolver.err = errors.New("element not found")

	fixture.service.runTestCase(context.Background(), pendingRun())

	require.Len(t, fixture.runErrors.messages, 1)
	assert.Contains(t, fixture.runErrors.messages[0], "Failed to resolve trigger")
	assert.Nil(t, fixture.runErrors.matchers[0])
	assert.Equal(t, 1, fixture.runs.finished)
}

func TestRunTestCaseRecordsAFailingActivationAndStillFinishes(t *testing.T) {
	trigger := &fakeTrigger{err: errors.New("connection refused")}
	fixture := newExecutionFixture(enabledTestCase(), trigger)

	fixture.service.runTestCase(context.Background(), pendingRun())

	require.Len(t, fixture.runErrors.messages, 1)
	assert.Contains(t, fixture.runErrors.messages[0], "Failed to activate trigger")
	assert.Equal(t, 1, fixture.runs.finished)
}

func TestRunTestCaseHandsTheSessionIdentifierToTheTrigger(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusOK}}
	fixture := newExecutionFixture(enabledTestCase(), trigger)

	fixture.service.runTestCase(context.Background(), pendingRun())

	require.Len(t, fixture.runs.started, 1)
	assert.Equal(t, fixture.runs.started[0], trigger.sessionID)
	assert.NotEmpty(t, trigger.sessionID)
	assert.Empty(t, fixture.runErrors.messages)
	assert.Equal(t, 1, fixture.runs.finished)
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

	fixture.service.runTestCase(context.Background(), pendingRun())

	require.Len(t, fixture.runErrors.messages, 1)
	assert.Equal(t, rule, fixture.runErrors.matchers[0])
	assert.Contains(t, fixture.runErrors.messages[0], "500")
}

func TestRunTestCaseIgnoresDisabledValidationRules(t *testing.T) {
	trigger := &fakeTrigger{response: &model.Exchange{Status: http.StatusInternalServerError}}
	fixture := newExecutionFixture(enabledTestCase(statusRule("200", false), nil), trigger)

	fixture.service.runTestCase(context.Background(), pendingRun())

	assert.Empty(t, fixture.runErrors.messages)
	assert.Equal(t, 1, fixture.runs.finished)
}

func TestRunTestCaseStopsWhenTheRunReferencesNoTestCase(t *testing.T) {
	fixture := newExecutionFixture(enabledTestCase(), &fakeTrigger{})

	fixture.service.runTestCase(context.Background(), &dao.TestCaseRun{ID: uuid.New()})

	assert.Empty(t, fixture.runs.started)
	assert.Zero(t, fixture.runs.finished)
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
