package triggers

import (
	"net/http"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/qip"
)

func TestGetTriggerBuildsAnHTTPTrigger(t *testing.T) {
	factory := NewFactory(qip.NewEngineClient("http://engine:8080"), http.DefaultClient)

	trigger, err := factory.GetTrigger(HTTPTriggerType, map[string]any{"contextPath": "/orders"})

	require.NoError(t, err)
	assert.Equal(t, "/orders", trigger.(*httpTrigger).path)
}

func TestGetTriggerAcceptsANonStringPath(t *testing.T) {
	factory := NewFactory(qip.NewEngineClient("http://engine:8080"), http.DefaultClient)

	trigger, err := factory.GetTrigger(HTTPTriggerType, map[string]any{"contextPath": 42})

	require.NoError(t, err)
	assert.Equal(t, "42", trigger.(*httpTrigger).path)
}

func TestGetTriggerRejectsAMissingPath(t *testing.T) {
	factory := NewFactory(qip.NewEngineClient("http://engine:8080"), http.DefaultClient)

	_, err := factory.GetTrigger(HTTPTriggerType, map[string]any{})

	require.Error(t, err)
	assert.Contains(t, err.Error(), "missing contextPath property")
}

func TestGetTriggerRejectsAnUnsupportedType(t *testing.T) {
	factory := NewFactory(qip.NewEngineClient("http://engine:8080"), http.DefaultClient)

	_, err := factory.GetTrigger("async-api-trigger", nil)

	require.Error(t, err)
	assert.Contains(t, err.Error(), "trigger type not supported: async-api-trigger")
}
