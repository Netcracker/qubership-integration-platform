package services

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
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
		EntityType: dao.EntityTypeHeader,
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
	repositories := Repositories{EndpointMocks: repository}
	return NewEndpointMocksService(&fakeRunner{}, repositories, NewMatchersService(repositories)), repository
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
	repository := &fakeEndpointMocksRepository{findAllErr: failure}
	service := NewEndpointMocksService(&fakeRunner{}, Repositories{EndpointMocks: repository}, nil)

	response, err := service.Call(context.Background(), dao.EndpointReference{}, model.Exchange{})

	require.ErrorIs(t, err, failure)
	assert.Nil(t, response)
}

func TestCallReportsAnUnknownMatcherType(t *testing.T) {
	matcher := headerMatcher("X-Kind", "order")
	matcher.Type = "no-such-predicate"
	service, _ := endpointMocksServiceOver(mockAnswering("never", time.Now(), matcher))

	response, err := service.Call(context.Background(), dao.EndpointReference{}, model.Exchange{})

	require.Error(t, err)
	assert.Nil(t, response)
}

func TestBuildResponseExchangeDefaultsToAnEmptyOkResponse(t *testing.T) {
	exchange := buildResponseExchange(nil)

	assert.Equal(t, http.StatusOK, exchange.Status)
	assert.Empty(t, exchange.Body)
	assert.Empty(t, exchange.Headers)
}

func TestBuildResponseExchangeGroupsRepeatedHeaders(t *testing.T) {
	body := "payload"
	exchange := buildResponseExchange(&dao.ResponseSettings{
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
