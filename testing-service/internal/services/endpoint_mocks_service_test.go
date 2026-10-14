package services

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/matching"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func TestGetEnabledMatchersCountReturnsZeroForNoMatchers(t *testing.T) {
	assert.Equal(t, 0, getEnabledMatchersCount(nil))
}

func TestGetEnabledMatchersCountCountsOnlyTheEnabledOnes(t *testing.T) {
	matchers := []*dao.Matcher{
		{Enabled: true},
		{Enabled: false},
		{Enabled: true},
		nil,
		{Enabled: false},
	}
	assert.Equal(t, 2, getEnabledMatchersCount(matchers))
}

func TestCompareEndpointMocksTreatsEqualMatcherCountsAsEqual(t *testing.T) {
	mock1 := dao.EndpointMock{RequestMatchers: []*dao.Matcher{{Enabled: true}}}
	mock2 := dao.EndpointMock{RequestMatchers: []*dao.Matcher{{Enabled: true}}}
	assert.Equal(t, 0, compareEndpointMocksByMatcherCountAndThenByCreationTime(mock1, mock2))
}

func TestCompareEndpointMocksPutsTheMockWithMoreMatchersFirst(t *testing.T) {
	fewer := dao.EndpointMock{RequestMatchers: []*dao.Matcher{}}
	more := dao.EndpointMock{RequestMatchers: []*dao.Matcher{{Enabled: true}}}
	assert.Positive(t, compareEndpointMocksByMatcherCountAndThenByCreationTime(fewer, more))
	assert.Negative(t, compareEndpointMocksByMatcherCountAndThenByCreationTime(more, fewer))
}

func TestCompareEndpointMocksBreaksTiesByCreationTime(t *testing.T) {
	older := time.Now()
	newer := older.AddDate(0, 0, 1)
	first := dao.EndpointMock{Metadata: dao.Metadata{CreatedAt: &older}}
	second := dao.EndpointMock{Metadata: dao.Metadata{CreatedAt: &newer}}
	assert.Negative(t, compareEndpointMocksByMatcherCountAndThenByCreationTime(first, second))
	assert.Positive(t, compareEndpointMocksByMatcherCountAndThenByCreationTime(second, first))
}

func TestCompareEndpointMocksPutsAMockWithoutACreationTimeFirst(t *testing.T) {
	timestamp := time.Now()
	unknown := dao.EndpointMock{}
	known := dao.EndpointMock{Metadata: dao.Metadata{CreatedAt: &timestamp}}
	assert.Negative(t, compareEndpointMocksByMatcherCountAndThenByCreationTime(unknown, known))
	assert.Positive(t, compareEndpointMocksByMatcherCountAndThenByCreationTime(known, unknown))
	assert.Equal(t, 0, compareEndpointMocksByMatcherCountAndThenByCreationTime(unknown, dao.EndpointMock{}))
}

// headerMatcher builds an enabled matcher that passes when the named header
// carries the given value.
func headerMatcher(name, value string) *dao.Matcher {
	return &dao.Matcher{
		ID:         uuid.New(),
		Enabled:    true,
		Type:       "equal",
		EntityType: matching.EntityTypeHeader,
		EntityName: &name,
		Parameters: []*dao.MatcherParameter{{Name: "value", Value: value}},
	}
}

func mockAnswering(body string, createdAt time.Time, matchers ...*dao.Matcher) dao.EndpointMock {
	return dao.EndpointMock{
		ID:              uuid.New(),
		Enabled:         true,
		Metadata:        dao.Metadata{CreatedAt: &createdAt},
		RequestMatchers: matchers,
		ResponseSettings: &dao.ResponseSettings{
			Status:  http.StatusOK,
			Message: &dao.Message{Body: &body},
		},
	}
}

func endpointMocksServiceOver(mocks ...dao.EndpointMock) (EndpointMocksService, *fakeEndpointMocksRepository) {
	repository := &fakeEndpointMocksRepository{mocks: mocks}
	repositories := dao.Repositories{EndpointMocks: repository}
	return NewEndpointMocksService(discardLogger(), &fakeRunner{}, repositories), repository
}

func callWithHeaders(t *testing.T, service EndpointMocksService, headers map[string][]string) *model.Exchange {
	t.Helper()
	reference := dao.EndpointReference{ChainID: "chain-1", ElementID: "element-1"}
	response, err := service.Call(context.Background(), reference, model.Exchange{Headers: headers})
	require.NoError(t, err)
	require.NotNil(t, response)
	return response
}

func TestCallPrefersTheMockWithTheMostEnabledMatchers(t *testing.T) {
	timestamp := time.Now()
	general := mockAnswering("general", timestamp, headerMatcher("X-Kind", "order"))
	specific := mockAnswering("specific", timestamp,
		headerMatcher("X-Kind", "order"), headerMatcher("X-Region", "eu"))
	// The least specific mock comes first, so a service that kept the query order
	// would answer with it.
	service, _ := endpointMocksServiceOver(general, specific)

	response := callWithHeaders(t, service, map[string][]string{
		"X-Kind":   {"order"},
		"X-Region": {"eu"},
	})

	assert.Equal(t, "specific", string(response.Body))
}

func TestCallPrefersTheOldestMockAmongEquallySpecificOnes(t *testing.T) {
	newer := mockAnswering("newer", time.Now(), headerMatcher("X-Kind", "order"))
	older := mockAnswering("older", time.Now().AddDate(0, 0, -1), headerMatcher("X-Kind", "order"))
	service, _ := endpointMocksServiceOver(newer, older)

	response := callWithHeaders(t, service, map[string][]string{"X-Kind": {"order"}})

	assert.Equal(t, "older", string(response.Body))
}

func TestCallSkipsAMockWhoseMatcherDoesNotHold(t *testing.T) {
	timestamp := time.Now()
	unmatched := mockAnswering("unmatched", timestamp,
		headerMatcher("X-Kind", "order"), headerMatcher("X-Region", "us"))
	matched := mockAnswering("matched", timestamp, headerMatcher("X-Kind", "order"))
	service, _ := endpointMocksServiceOver(unmatched, matched)

	response := callWithHeaders(t, service, map[string][]string{
		"X-Kind":   {"order"},
		"X-Region": {"eu"},
	})

	assert.Equal(t, "matched", string(response.Body))
}

func TestCallIgnoresDisabledMatchers(t *testing.T) {
	disabled := headerMatcher("X-Region", "us")
	disabled.Enabled = false
	service, _ := endpointMocksServiceOver(
		mockAnswering("answered", time.Now(), headerMatcher("X-Kind", "order"), disabled))

	response := callWithHeaders(t, service, map[string][]string{"X-Kind": {"order"}})

	assert.Equal(t, "answered", string(response.Body))
}

// No pass-through to the real endpoint: an unmatched call is a 404.
func TestCallAnswersNotFoundWhenNoMockMatches(t *testing.T) {
	service, _ := endpointMocksServiceOver(mockAnswering("never", time.Now(), headerMatcher("X-Kind", "order")))

	response := callWithHeaders(t, service, map[string][]string{"X-Kind": {"invoice"}})

	assert.Equal(t, http.StatusNotFound, response.Status)
	assert.Empty(t, response.Body)
}

func TestCallSelectsMocksOfTheGivenEndpointOnly(t *testing.T) {
	service, repository := endpointMocksServiceOver()

	callWithHeaders(t, service, nil)

	assert.ElementsMatch(t, []model.Filter{
		{Feature: "chain_id", Condition: model.ConditionIs, Values: []string{"chain-1"}},
		{Feature: "element_id", Condition: model.ConditionIs, Values: []string{"element-1"}},
		{Feature: "enabled", Condition: model.ConditionIs, Values: []string{"true"}},
	}, repository.lastFilters)
}

func TestCallReportsAFailingLookup(t *testing.T) {
	failure := errors.New("no connection")
	repository := &fakeEndpointMocksRepository{findErr: failure}
	service := NewEndpointMocksService(discardLogger(), &fakeRunner{}, dao.Repositories{EndpointMocks: repository})

	response, err := service.Call(context.Background(), dao.EndpointReference{}, model.Exchange{})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, response)
}

// A matcher this service cannot build does not hold, so the mock carrying it is
// passed over. Failing the call instead would answer every intercepted call on
// the element with a 500 — the mocks of one endpoint are answered from a single
// sorted list — and the engine has no fallback to the real endpoint.
func TestCallSkipsAMockWhoseMatcherCannotBeBuilt(t *testing.T) {
	broken := headerMatcher("X-Kind", "order")
	broken.Type = "no-such-predicate"
	timestamp := time.Now()
	// The broken mock carries the most matchers, so it is the one tried first.
	service, _ := endpointMocksServiceOver(
		mockAnswering("broken", timestamp, broken, headerMatcher("X-Kind", "order")),
		mockAnswering("sound", timestamp, headerMatcher("X-Kind", "order")),
	)

	response := callWithHeaders(t, service, map[string][]string{"X-Kind": {"order"}})

	assert.Equal(t, "sound", string(response.Body))
}

// A row stored before the entity name was required must not answer for the
// mocks it outranks. Its matcher reads nothing out of every exchange, so an
// `empty` matcher over it used to hold for every call, and the mock came first
// on creation time among mocks with one matcher each.
func TestCallSkipsAStoredMockWhoseMatcherNamesNoEntity(t *testing.T) {
	nameless := &dao.Matcher{
		ID: uuid.New(), Enabled: true, Type: "empty", EntityType: matching.EntityTypeHeader,
	}
	timestamp := time.Now()
	service, _ := endpointMocksServiceOver(
		mockAnswering("nameless", timestamp.AddDate(0, 0, -1), nameless),
		mockAnswering("specific", timestamp, headerMatcher("X-Kind", "order")),
	)

	response := callWithHeaders(t, service, map[string][]string{"X-Kind": {"order"}})

	assert.Equal(t, "specific", string(response.Body))
}

// A row stored before the name was checked against the grammar of its entity
// type is skipped like a nameless one. No request carries a header called
// `X Kind`, and no path template segment spells `order/Id`, so an `empty`
// matcher over either held for every call.
func TestCallSkipsAStoredMockWhoseMatcherNamesNothingReachable(t *testing.T) {
	for entityType, entityName := range map[string]string{
		matching.EntityTypeHeader:        "X Kind",
		matching.EntityTypePathParameter: "order/Id",
	} {
		t.Run(entityType, func(t *testing.T) {
			unreachable := &dao.Matcher{
				ID: uuid.New(), Enabled: true, Type: "empty",
				EntityType: entityType, EntityName: &entityName,
			}
			timestamp := time.Now()
			service, _ := endpointMocksServiceOver(
				mockAnswering("unreachable", timestamp.AddDate(0, 0, -1), unreachable),
				mockAnswering("specific", timestamp, headerMatcher("X-Kind", "order")),
			)

			response := callWithHeaders(t, service, map[string][]string{"X-Kind": {"order"}})

			assert.Equal(t, "specific", string(response.Body))
		})
	}
}

// A skipped mock falls through to the answer an unmatched call gets.
func TestCallAnswersNotFoundWhenEveryMatchingMockIsBroken(t *testing.T) {
	broken := headerMatcher("X-Kind", "order")
	broken.Type = "match"
	broken.Parameters = []*dao.MatcherParameter{{Name: "pattern", Value: "("}}
	service, _ := endpointMocksServiceOver(mockAnswering("never", time.Now(), broken))

	response := callWithHeaders(t, service, map[string][]string{"X-Kind": {"order"}})

	assert.Equal(t, http.StatusNotFound, response.Status)
	assert.Empty(t, response.Body)
}

// buildResponseExchangeOf is buildResponseExchange with the context and logger
// the tests do not care about.
func buildResponseExchangeOf(responseSettings *dao.ResponseSettings) *model.Exchange {
	return buildResponseExchange(context.Background(), discardLogger(), responseSettings)
}

func TestBuildResponseExchangeDefaultsToAnEmptyOkResponse(t *testing.T) {
	exchange := buildResponseExchangeOf(nil)

	assert.Equal(t, http.StatusOK, exchange.Status)
	assert.Empty(t, exchange.Body)
	assert.Empty(t, exchange.Headers)
}

func TestBuildResponseExchangeGroupsRepeatedHeaders(t *testing.T) {
	body := "payload"
	exchange := buildResponseExchangeOf(&dao.ResponseSettings{
		Status: http.StatusAccepted,
		Message: &dao.Message{
			Body: &body,
			Headers: []*dao.Header{
				{Name: "x-trace", Value: "first"},
				nil,
				{Name: "X-Trace", Value: "second"},
			},
		},
	})

	assert.Equal(t, http.StatusAccepted, exchange.Status)
	assert.Equal(t, "payload", string(exchange.Body))
	assert.Equal(t, []string{"first", "second"}, exchange.Headers["X-Trace"])
}

// A row stored before the range was enforced still has to answer with a status
// line a client can read.
func TestBuildResponseExchangeFallsBackForAStoredStatusOutOfRange(t *testing.T) {
	for _, status := range []int{0, -1, 99, 600, 70000} {
		exchange := buildResponseExchangeOf(&dao.ResponseSettings{Status: status})
		assert.Equalf(t, http.StatusOK, exchange.Status, "stored status %d", status)
	}
}

// A row stored before the field-name and value checks is left out of the answer
// rather than written to the wire, where it would malform the response.
func TestBuildResponseExchangeSkipsAStoredHeaderItCannotWriteOut(t *testing.T) {
	unwritable := map[string]*dao.Header{
		"empty name":           {Name: "", Value: "yes"},
		"space in the name":    {Name: "X Mocked", Value: "yes"},
		"colon in the name":    {Name: "X-Mocked: yes", Value: "1"},
		"null in the name":     {Name: "X-Mocked\x00", Value: "yes"},
		"newline in the name":  {Name: "X-Mocked\nX-Injected", Value: "1"},
		"null in the value":    {Name: "X-Mocked", Value: "yes\x00no"},
		"newline in the value": {Name: "X-Mocked", Value: "yes\r\nX-Injected: 1"},
	}
	for name, header := range unwritable {
		t.Run(name, func(t *testing.T) {
			logs := &bytes.Buffer{}
			logger := slog.New(slog.NewTextHandler(logs, nil))

			exchange := buildResponseExchange(context.Background(), logger, &dao.ResponseSettings{
				Status:  http.StatusOK,
				Message: &dao.Message{Headers: []*dao.Header{header, {Name: "X-Kept", Value: "yes"}}},
			})

			assert.Equal(t, map[string][]string{"X-Kept": {"yes"}}, exchange.Headers)
			assert.Contains(t, logs.String(), "Skipping a stored response header")
		})
	}
}

func TestAwaitResponseDelayReturnsAtOnceWhenTheDelayHasPassed(t *testing.T) {
	ctx := WithRequestStart(context.Background(), time.Now().Add(-time.Second))

	start := time.Now()
	require.NoError(t, awaitResponseDelay(ctx, &dao.ResponseSettings{Delay: 50}))

	assert.Less(t, time.Since(start), 50*time.Millisecond)
}

func TestAwaitResponseDelayWaitsOutTheRemainder(t *testing.T) {
	ctx := WithRequestStart(context.Background(), time.Now())

	start := time.Now()
	require.NoError(t, awaitResponseDelay(ctx, &dao.ResponseSettings{Delay: 30}))

	assert.GreaterOrEqual(t, time.Since(start), 20*time.Millisecond)
}

func TestAwaitResponseDelayGivesUpWhenTheCallerDoes(t *testing.T) {
	ctx, cancel := context.WithCancel(WithRequestStart(context.Background(), time.Now()))
	cancel()

	err := awaitResponseDelay(ctx, &dao.ResponseSettings{Delay: 5000})

	require.ErrorIs(t, err, context.Canceled)
}
