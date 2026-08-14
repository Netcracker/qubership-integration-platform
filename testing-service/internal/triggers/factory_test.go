package triggers

import (
	"net/http"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newTestFactory(t *testing.T, httpClient *http.Client) Factory {
	t.Helper()
	factory, err := NewFactory("http://engine:8080", httpClient)
	require.NoError(t, err)
	return factory
}

func TestGetTriggerBuildsAnHTTPTrigger(t *testing.T) {
	trigger, err := newTestFactory(t, http.DefaultClient).
		GetTrigger(HTTPTriggerType, map[string]any{"contextPath": "/orders"})

	require.NoError(t, err)
	assert.Equal(t, "/orders", trigger.(*httpTrigger).path)
	assert.Equal(t, "http://engine:8080/routes", trigger.(*httpTrigger).triggersURL)
}

func TestNewFactoryDropsATrailingSlashFromTheEngineAddress(t *testing.T) {
	factory, err := NewFactory("http://engine:8080/", http.DefaultClient)

	require.NoError(t, err)
	trigger, err := factory.GetTrigger(HTTPTriggerType, map[string]any{"contextPath": "/orders"})
	require.NoError(t, err)
	assert.Equal(t, "http://engine:8080/routes", trigger.(*httpTrigger).triggersURL)
}

func TestGetTriggerAcceptsANonStringPath(t *testing.T) {
	trigger, err := newTestFactory(t, http.DefaultClient).
		GetTrigger(HTTPTriggerType, map[string]any{"contextPath": 42})

	require.NoError(t, err)
	assert.Equal(t, "42", trigger.(*httpTrigger).path)
}

func TestGetTriggerRejectsAMissingPath(t *testing.T) {
	_, err := newTestFactory(t, http.DefaultClient).GetTrigger(HTTPTriggerType, map[string]any{})

	require.Error(t, err)
	assert.Contains(t, err.Error(), "missing contextPath property")
}

func TestGetTriggerRejectsAnUnsupportedType(t *testing.T) {
	_, err := newTestFactory(t, http.DefaultClient).GetTrigger("async-api-trigger", nil)

	require.Error(t, err)
	assert.Contains(t, err.Error(), "trigger type not supported: async-api-trigger")
}

func TestNewFactoryRejectsAnAddressThatIsNotAURL(t *testing.T) {
	_, err := NewFactory("://engine", http.DefaultClient)

	require.Error(t, err)
	assert.Contains(t, err.Error(), "is not a URL")
}
