package triggers

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
)

const testSessionID = "session-1"

// capturedRequest is what the stub engine saw. The handler runs on the server's
// goroutine and the assertions on the test's, so every field goes through the
// mutex.
type capturedRequest struct {
	mutex       sync.Mutex
	path        string
	escapedPath string
	query       url.Values
	method      string
	headers     http.Header
	body        string
}

func (c *capturedRequest) record(r *http.Request, body string) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	c.path = r.URL.Path
	c.escapedPath = r.URL.EscapedPath()
	c.query = r.URL.Query()
	c.method = r.Method
	c.headers = r.Header.Clone()
	c.body = body
}

func (c *capturedRequest) read() capturedRequest {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	return capturedRequest{
		path:        c.path,
		escapedPath: c.escapedPath,
		query:       c.query,
		method:      c.method,
		headers:     c.headers,
		body:        c.body,
	}
}

// startEngine serves every request and records the last one it answered.
func startEngine(t *testing.T, handler http.HandlerFunc) (string, *http.Client, *capturedRequest) {
	t.Helper()
	captured := &capturedRequest{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		captured.record(r, string(body))
		if handler != nil {
			handler(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte("done"))
	}))
	t.Cleanup(server.Close)
	return server.URL + "/routes", server.Client(), captured
}

func newTrigger(t *testing.T, path string, triggersURL string, client *http.Client) Trigger {
	t.Helper()
	trigger, err := NewHTTPTrigger(triggersURL, client, map[string]any{"contextPath": path})
	require.NoError(t, err)
	return trigger
}

func TestActivateSendsTheRequestAndReturnsTheExchange(t *testing.T) {
	triggersURL, client, captured := startEngine(t, nil)
	body := `{"order":1}`
	settings := &dao.RequestSettings{
		Method: http.MethodPost,
		Message: &dao.Message{
			Body:    &body,
			Headers: []*dao.Header{{Name: "Content-Type", Value: "application/json"}, nil},
		},
	}

	exchange, err := newTrigger(t, "/orders", triggersURL, client).
		Activate(context.Background(), testSessionID, settings)

	require.NoError(t, err)
	assert.Equal(t, http.StatusAccepted, exchange.Status)
	assert.Equal(t, "done", string(exchange.Body))
	assert.Equal(t, []string{"text/plain"}, exchange.Headers["Content-Type"])
	seen := captured.read()
	assert.Equal(t, "/routes/orders", seen.path)
	assert.Equal(t, http.MethodPost, seen.method)
	assert.Equal(t, body, seen.body)
	assert.Equal(t, "application/json", seen.headers.Get("Content-Type"))
	assert.Equal(t, testSessionID, seen.headers.Get(sessionHeader))
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
			triggersURL, client, captured := startEngine(t, nil)
			settings := &dao.RequestSettings{Method: http.MethodGet, PathParameters: test.parameters}

			_, err := newTrigger(t, test.path, triggersURL, client).
				Activate(context.Background(), testSessionID, settings)

			require.NoError(t, err)
			assert.Equal(t, test.expected, captured.read().path)
		})
	}
}

// A value must land in a single path segment; otherwise it could reach a
// different route than the test case names.
func TestActivateEscapesASlashInAPathParameterValue(t *testing.T) {
	triggersURL, client, captured := startEngine(t, nil)
	settings := &dao.RequestSettings{
		Method:         http.MethodGet,
		PathParameters: []*dao.PathParameter{{Name: "id", Value: "a/b"}},
	}

	_, err := newTrigger(t, "/orders/{id}", triggersURL, client).
		Activate(context.Background(), testSessionID, settings)

	require.NoError(t, err)
	assert.Equal(t, "/routes/orders/a%2Fb", captured.read().escapedPath)
}

// url.JoinPath collapses dot segments and PathEscape leaves dots alone, so a
// value of ".." would walk the request out of the engine's trigger prefix.
func TestActivateRejectsAPathParameterThatWalksOutOfThePrefix(t *testing.T) {
	for _, value := range []string{"..", "."} {
		t.Run(value, func(t *testing.T) {
			triggersURL, client, _ := startEngine(t, nil)
			settings := &dao.RequestSettings{
				Method:         http.MethodGet,
				PathParameters: []*dao.PathParameter{{Name: "id", Value: value}},
			}

			_, err := newTrigger(t, "/orders/{id}/items", triggersURL, client).
				Activate(context.Background(), testSessionID, settings)

			require.Error(t, err)
			assert.Contains(t, err.Error(), "walks out of the engine prefix")
		})
	}
}

func TestActivateRejectsAnUndefinedPathParameter(t *testing.T) {
	triggersURL, client, _ := startEngine(t, nil)
	settings := &dao.RequestSettings{
		Method:         http.MethodGet,
		PathParameters: []*dao.PathParameter{{Name: "other", Value: "42"}},
	}

	_, err := newTrigger(t, "/orders/{id}", triggersURL, client).
		Activate(context.Background(), testSessionID, settings)

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
			triggersURL, client, captured := startEngine(t, nil)
			settings := &dao.RequestSettings{Method: http.MethodGet, QueryParameters: test.parameters}

			_, err := newTrigger(t, "/orders", triggersURL, client).
				Activate(context.Background(), testSessionID, settings)

			require.NoError(t, err)
			assert.Equal(t, test.expected, captured.read().query)
		})
	}
}

func TestActivateGivesUpWhenTheTimeoutExpires(t *testing.T) {
	release := make(chan struct{})
	triggersURL, client, _ := startEngine(t, func(http.ResponseWriter, *http.Request) {
		<-release
	})
	defer close(release)
	settings := &dao.RequestSettings{Method: http.MethodGet, Timeout: 50}

	start := time.Now()
	_, err := newTrigger(t, "/orders", triggersURL, client).
		Activate(context.Background(), testSessionID, settings)

	require.Error(t, err)
	assert.ErrorIs(t, err, context.DeadlineExceeded)
	assert.Less(t, time.Since(start), 5*time.Second)
}

// A case that names no timeout still gets one: the client is shared and carries
// none, so without a ceiling a hung engine would pin the worker for good.
func TestActivateBoundsACaseThatNamesNoTimeout(t *testing.T) {
	original := defaultTimeout
	defaultTimeout = 50 * time.Millisecond
	t.Cleanup(func() { defaultTimeout = original })

	release := make(chan struct{})
	triggersURL, client, _ := startEngine(t, func(http.ResponseWriter, *http.Request) {
		<-release
	})
	defer close(release)

	_, err := newTrigger(t, "/orders", triggersURL, client).
		Activate(context.Background(), testSessionID, &dao.RequestSettings{Method: http.MethodGet})

	require.Error(t, err)
	assert.ErrorIs(t, err, context.DeadlineExceeded)
}

func TestActivateStopsWhenTheCallerCancels(t *testing.T) {
	release := make(chan struct{})
	triggersURL, client, _ := startEngine(t, func(http.ResponseWriter, *http.Request) {
		<-release
	})
	defer close(release)

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(50 * time.Millisecond)
		cancel()
	}()

	_, err := newTrigger(t, "/orders", triggersURL, client).
		Activate(ctx, testSessionID, &dao.RequestSettings{Method: http.MethodGet})

	require.Error(t, err)
	assert.ErrorIs(t, err, context.Canceled)
}

func TestActivateReportsAnUnreachableEngine(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	triggersURL := server.URL + "/routes"
	client := server.Client()
	server.Close()

	_, err := newTrigger(t, "/orders", triggersURL, client).
		Activate(context.Background(), testSessionID, &dao.RequestSettings{Method: http.MethodGet})

	require.Error(t, err)
	// The address is what makes such a failure readable, so the error has to
	// carry it rather than a prefix the caller repeats anyway.
	assert.Contains(t, err.Error(), triggersURL+"/orders")
	assert.Contains(t, err.Error(), "connection refused")
}

func TestActivateRejectsAnInvalidMethod(t *testing.T) {
	triggersURL, client, _ := startEngine(t, nil)

	_, err := newTrigger(t, "/orders", triggersURL, client).
		Activate(context.Background(), testSessionID, &dao.RequestSettings{Method: "not a method"})

	require.Error(t, err)
	assert.Contains(t, err.Error(), "failed to build request")
}

// Request settings are optional on a test case, so an enabled case can reach the
// executor without them. This runs in a worker goroutine that nothing recovers,
// where a dereference would take the whole process down.
func TestActivateReportsATestCaseWithoutRequestSettings(t *testing.T) {
	triggersURL, client, captured := startEngine(t, nil)

	exchange, err := newTrigger(t, "/orders", triggersURL, client).
		Activate(context.Background(), testSessionID, nil)

	require.Error(t, err)
	assert.Nil(t, exchange)
	assert.ErrorContains(t, err, "no request settings")
	assert.Empty(t, captured.read().path, "nothing was sent to the engine")
}
