package qip

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestFindChainElementRequestsTheChainAndElement(t *testing.T) {
	var requestPath string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestPath = r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"e-1","type":"http-trigger","chainId":"c-1",` +
			`"properties":{"contextPath":"/orders"},"createdBy":{"id":"0","username":"developer"}}`))
	}))
	defer server.Close()

	element, err := NewCatalogClient(server.URL, server.Client()).
		FindChainElement(context.Background(), "c-1", "e-1")

	require.NoError(t, err)
	assert.Equal(t, "/v1/chains/c-1/elements/e-1", requestPath)
	assert.Equal(t, "http-trigger", element.Type)
	assert.Equal(t, map[string]any{"contextPath": "/orders"}, element.Properties)
}

// The catalog answers with a good deal more than the trigger resolver reads, and
// the decoder has to walk past all of it.
func TestFindChainElementIgnoresTheFieldsItDoesNotRead(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"id":"e-1","type":"http-trigger","chainId":"c-1",` +
			`"createdBy":{"id":"0","username":"developer"},` +
			`"children":[{"id":"e-2","type":"sender"}],"swimlaneId":"s-1"}`))
	}))
	defer server.Close()

	element, err := NewCatalogClient(server.URL, server.Client()).
		FindChainElement(context.Background(), "c-1", "e-1")

	require.NoError(t, err)
	assert.Equal(t, "http-trigger", element.Type)
	assert.Nil(t, element.Properties)
}

func TestFindChainElementTrimsTheTrailingSlashOfTheAddress(t *testing.T) {
	var requestPath string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestPath = r.URL.Path
		_, _ = w.Write([]byte(`{"id":"e-1"}`))
	}))
	defer server.Close()

	_, err := NewCatalogClient(server.URL+"/", server.Client()).
		FindChainElement(context.Background(), "c-1", "e-1")

	require.NoError(t, err)
	assert.Equal(t, "/v1/chains/c-1/elements/e-1", requestPath)
}

func TestFindChainElementSendsTheHostAuthorization(t *testing.T) {
	var authorization string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authorization = r.Header.Get("Authorization")
		_, _ = w.Write([]byte(`{"id":"e-1"}`))
	}))
	defer server.Close()

	client := &http.Client{Transport: roundTripperFunc(func(r *http.Request) (*http.Response, error) {
		r.Header.Set("Authorization", "Bearer token")
		return http.DefaultTransport.RoundTrip(r)
	})}

	_, err := NewCatalogClient(server.URL, client).FindChainElement(context.Background(), "c-1", "e-1")

	require.NoError(t, err)
	assert.Equal(t, "Bearer token", authorization)
}

func TestFindChainElementReportsANonOKStatus(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("element not found"))
	}))
	defer server.Close()

	_, err := NewCatalogClient(server.URL, server.Client()).
		FindChainElement(context.Background(), "c-1", "missing")

	require.Error(t, err)
	assert.Contains(t, err.Error(), "404")
	assert.Contains(t, err.Error(), "element not found")
}

func TestFindChainElementReportsMalformedJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("{"))
	}))
	defer server.Close()

	_, err := NewCatalogClient(server.URL, server.Client()).
		FindChainElement(context.Background(), "c-1", "e-1")

	require.Error(t, err)
	assert.Contains(t, err.Error(), "failed to parse response content")
}

func TestFindChainElementReportsAnUnreachableCatalog(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	address := server.URL
	server.Close()

	_, err := NewCatalogClient(address, http.DefaultClient).
		FindChainElement(context.Background(), "c-1", "e-1")

	require.Error(t, err)
	assert.Contains(t, err.Error(), "failed to get chain element")
}

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

// The caller is an executor worker, whose context lives until shutdown, and the
// shared client carries no timeout. A catalog that accepts the connection and
// never answers would hold the worker, and the lease it keeps renewing, for as
// long as the process runs.
func TestFindChainElementGivesUpOnACatalogThatNeverAnswers(t *testing.T) {
	previous := defaultTimeout
	defaultTimeout = 50 * time.Millisecond
	defer func() { defaultTimeout = previous }()

	answered := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
		close(answered)
	}))
	defer server.Close()

	start := time.Now()
	_, err := NewCatalogClient(server.URL, server.Client()).
		FindChainElement(context.Background(), "c-1", "e-1")

	require.Error(t, err)
	assert.ErrorIs(t, err, context.DeadlineExceeded)
	assert.Less(t, time.Since(start), 5*time.Second)
	<-answered
}
