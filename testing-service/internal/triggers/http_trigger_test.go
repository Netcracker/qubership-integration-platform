package triggers

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/qip"
)

// capturedRequest is what the stub engine saw.
type capturedRequest struct {
	path        string
	escapedPath string
	query       url.Values
	method      string
	headers     http.Header
	body        string
}

// startEngine serves every request and records the last one it answered.
func startEngine(t *testing.T, handler http.HandlerFunc) (qip.EngineClient, *http.Client, *capturedRequest) {
	t.Helper()
	captured := &capturedRequest{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		captured.path = r.URL.Path
		captured.escapedPath = r.URL.EscapedPath()
		captured.query = r.URL.Query()
		captured.method = r.Method
		captured.headers = r.Header.Clone()
		captured.body = string(body)
		if handler != nil {
			handler(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte("done"))
	}))
	t.Cleanup(server.Close)
	return qip.NewEngineClient(server.URL), server.Client(), captured
}

func newTrigger(t *testing.T, path string, engine qip.EngineClient, client *http.Client) Trigger {
	t.Helper()
	trigger, err := NewHTTPTrigger(engine, client, map[string]any{"contextPath": path})
	require.NoError(t, err)
	return trigger
}

func sessionContext() context.Context {
	return WithSessionID(context.Background(), "session-1")
}

func TestActivateSendsTheRequestAndReturnsTheExchange(t *testing.T) {
	engine, client, captured := startEngine(t, nil)
	body := `{"order":1}`
	settings := &dao.RequestSettings{
		Method: http.MethodPost,
		Message: &dao.Message{
			Body:    &body,
			Headers: []*dao.Header{{Name: "Content-Type", Value: "application/json"}, nil},
		},
	}

	exchange, err := newTrigger(t, "/orders", engine, client).Activate(sessionContext(), settings)

	require.NoError(t, err)
	assert.Equal(t, http.StatusAccepted, exchange.Status)
	assert.Equal(t, "done", string(exchange.Body))
	assert.Equal(t, []string{"text/plain"}, exchange.Headers["Content-Type"])
	assert.Equal(t, "/routes/orders", captured.path)
	assert.Equal(t, http.MethodPost, captured.method)
	assert.Equal(t, body, captured.body)
	assert.Equal(t, "application/json", captured.headers.Get("Content-Type"))
	assert.Equal(t, "session-1", captured.headers.Get(sessionHeader))
}

func TestActivateFailsWithoutASessionID(t *testing.T) {
	engine, client, _ := startEngine(t, nil)

	_, err := newTrigger(t, "/orders", engine, client).
		Activate(context.Background(), &dao.RequestSettings{Method: http.MethodGet})

	require.Error(t, err)
	assert.Contains(t, err.Error(), "session ID is absent from the context")
}

func TestActivateSubstitutesPathParameters(t *testing.T) {
	tests := []struct {
		name       string
		path       string
		parameters []*dao.PathParameter
		expected   string
	}{
		{
			name:       "single parameter",
			path:       "/orders/{id}",
			parameters: []*dao.PathParameter{{Name: "id", Value: "42"}},
			expected:   "/routes/orders/42",
		},
		{
			name: "several parameters",
			path: "/orders/{orderId}/items/{itemId}",
			parameters: []*dao.PathParameter{
				{Name: "itemId", Value: "7"},
				{Name: "orderId", Value: "42"},
			},
			expected: "/routes/orders/42/items/7",
		},
		{
			name:       "adjacent parameters",
			path:       "/{prefix}{suffix}",
			parameters: []*dao.PathParameter{{Name: "prefix", Value: "a"}, {Name: "suffix", Value: "b"}},
			expected:   "/routes/ab",
		},
		{
			name:       "no parameters",
			path:       "/orders",
			parameters: nil,
			expected:   "/routes/orders",
		},
		{
			name:       "nil entry among the parameters",
			path:       "/orders/{id}",
			parameters: []*dao.PathParameter{nil, {Name: "id", Value: "42"}},
			expected:   "/routes/orders/42",
		},
		{
			name:       "value with a space",
			path:       "/orders/{id}",
			parameters: []*dao.PathParameter{{Name: "id", Value: "a b"}},
			expected:   "/routes/orders/a b",
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			engine, client, captured := startEngine(t, nil)
			settings := &dao.RequestSettings{Method: http.MethodGet, PathParameters: test.parameters}

			_, err := newTrigger(t, test.path, engine, client).Activate(sessionContext(), settings)

			require.NoError(t, err)
			assert.Equal(t, test.expected, captured.path)
		})
	}
}

// A value must land in a single path segment; otherwise it could reach a
// different route than the test case names.
func TestActivateEscapesASlashInAPathParameterValue(t *testing.T) {
	engine, client, captured := startEngine(t, nil)
	settings := &dao.RequestSettings{
		Method:         http.MethodGet,
		PathParameters: []*dao.PathParameter{{Name: "id", Value: "a/b"}},
	}

	_, err := newTrigger(t, "/orders/{id}", engine, client).Activate(sessionContext(), settings)

	require.NoError(t, err)
	assert.Equal(t, "/routes/orders/a%2Fb", captured.escapedPath)
}

func TestActivateRejectsAnUndefinedPathParameter(t *testing.T) {
	engine, client, _ := startEngine(t, nil)
	settings := &dao.RequestSettings{
		Method:         http.MethodGet,
		PathParameters: []*dao.PathParameter{{Name: "other", Value: "42"}},
	}

	_, err := newTrigger(t, "/orders/{id}", engine, client).Activate(sessionContext(), settings)

	require.Error(t, err)
	assert.Contains(t, err.Error(), `path parameter "id" is not defined`)
}

func TestActivateSendsQueryParameters(t *testing.T) {
	tests := []struct {
		name       string
		parameters []*dao.QueryParameter
		expected   url.Values
	}{
		{
			name:     "none",
			expected: url.Values{},
		},
		{
			name:       "one",
			parameters: []*dao.QueryParameter{{Name: "status", Value: "new"}},
			expected:   url.Values{"status": {"new"}},
		},
		{
			name: "repeated name",
			parameters: []*dao.QueryParameter{
				{Name: "status", Value: "new"},
				{Name: "status", Value: "paid"},
			},
			expected: url.Values{"status": {"new", "paid"}},
		},
		{
			name:       "value needing escaping",
			parameters: []*dao.QueryParameter{{Name: "text", Value: "a b&c"}},
			expected:   url.Values{"text": {"a b&c"}},
		},
		{
			name:       "nil entry among the parameters",
			parameters: []*dao.QueryParameter{nil, {Name: "status", Value: "new"}},
			expected:   url.Values{"status": {"new"}},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			engine, client, captured := startEngine(t, nil)
			settings := &dao.RequestSettings{Method: http.MethodGet, QueryParameters: test.parameters}

			_, err := newTrigger(t, "/orders", engine, client).Activate(sessionContext(), settings)

			require.NoError(t, err)
			assert.Equal(t, test.expected, captured.query)
		})
	}
}

func TestActivateGivesUpWhenTheTimeoutExpires(t *testing.T) {
	release := make(chan struct{})
	engine, client, _ := startEngine(t, func(http.ResponseWriter, *http.Request) {
		<-release
	})
	defer close(release)
	settings := &dao.RequestSettings{Method: http.MethodGet, Timeout: 50}

	start := time.Now()
	_, err := newTrigger(t, "/orders", engine, client).Activate(sessionContext(), settings)

	require.Error(t, err)
	assert.ErrorIs(t, err, context.DeadlineExceeded)
	assert.Less(t, time.Since(start), 5*time.Second)
}

func TestActivateStopsWhenTheCallerCancels(t *testing.T) {
	release := make(chan struct{})
	engine, client, _ := startEngine(t, func(http.ResponseWriter, *http.Request) {
		<-release
	})
	defer close(release)

	ctx, cancel := context.WithCancel(sessionContext())
	go func() {
		time.Sleep(50 * time.Millisecond)
		cancel()
	}()

	_, err := newTrigger(t, "/orders", engine, client).
		Activate(ctx, &dao.RequestSettings{Method: http.MethodGet})

	require.Error(t, err)
	assert.ErrorIs(t, err, context.Canceled)
}

func TestActivateReportsAnUnreachableEngine(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	engine := qip.NewEngineClient(server.URL)
	client := server.Client()
	server.Close()

	_, err := newTrigger(t, "/orders", engine, client).
		Activate(sessionContext(), &dao.RequestSettings{Method: http.MethodGet})

	require.Error(t, err)
	assert.Contains(t, err.Error(), "failed to activate trigger")
}

func TestActivateRejectsAnInvalidMethod(t *testing.T) {
	engine, client, _ := startEngine(t, nil)

	_, err := newTrigger(t, "/orders", engine, client).
		Activate(sessionContext(), &dao.RequestSettings{Method: "not a method"})

	require.Error(t, err)
	assert.Contains(t, err.Error(), "failed to build request")
}

func TestSessionIDRejectsAContextWithoutOne(t *testing.T) {
	_, err := SessionID(context.Background())
	require.Error(t, err)
}

func TestSessionIDReturnsTheStoredValue(t *testing.T) {
	sessionID, err := SessionID(WithSessionID(context.Background(), "session-1"))
	require.NoError(t, err)
	assert.Equal(t, "session-1", sessionID)
}
