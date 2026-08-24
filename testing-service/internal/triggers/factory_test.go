package triggers

import (
	"net/http"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const testEngineAddress = "http://engine:8080"

func TestGetTriggerBuildsAnHTTPTrigger(t *testing.T) {
	trigger, err := NewFactory(http.DefaultClient).
		GetTrigger(testEngineAddress, HTTPTriggerType, map[string]any{"contextPath": "/orders"})

	require.NoError(t, err)
	assert.Equal(t, "/orders", trigger.(*httpTrigger).path)
	assert.Equal(t, "http://engine:8080/routes", trigger.(*httpTrigger).triggersURL)
}

// The address reaches the factory per call, so two chains on two domains are
// activated on the engine each was deployed to.
func TestGetTriggerPublishesEachTriggerUnderTheAddressItWasGiven(t *testing.T) {
	factory := NewFactory(http.DefaultClient)
	parameters := map[string]any{"contextPath": "/orders"}

	classic, err := factory.GetTrigger("http://qip-engine:8080", HTTPTriggerType, parameters)
	require.NoError(t, err)
	micro, err := factory.GetTrigger("http://10.244.0.24:8080", HTTPTriggerType, parameters)
	require.NoError(t, err)

	assert.Equal(t, "http://qip-engine:8080/routes", classic.(*httpTrigger).triggersURL)
	assert.Equal(t, "http://10.244.0.24:8080/routes", micro.(*httpTrigger).triggersURL)
}

func TestGetTriggerDropsATrailingSlashFromTheEngineAddress(t *testing.T) {
	trigger, err := NewFactory(http.DefaultClient).
		GetTrigger("http://engine:8080/", HTTPTriggerType, map[string]any{"contextPath": "/orders"})

	require.NoError(t, err)
	assert.Equal(t, "http://engine:8080/routes", trigger.(*httpTrigger).triggersURL)
}

func TestGetTriggerAcceptsANonStringPath(t *testing.T) {
	trigger, err := NewFactory(http.DefaultClient).
		GetTrigger(testEngineAddress, HTTPTriggerType, map[string]any{"contextPath": 42})

	require.NoError(t, err)
	assert.Equal(t, "42", trigger.(*httpTrigger).path)
}

func TestGetTriggerRejectsAMissingPath(t *testing.T) {
	_, err := NewFactory(http.DefaultClient).
		GetTrigger(testEngineAddress, HTTPTriggerType, map[string]any{})

	require.Error(t, err)
	assert.Contains(t, err.Error(), "missing contextPath property")
}

func TestGetTriggerRejectsAnUnsupportedType(t *testing.T) {
	_, err := NewFactory(http.DefaultClient).GetTrigger(testEngineAddress, "async-api-trigger", nil)

	require.Error(t, err)
	assert.Contains(t, err.Error(), "trigger type not supported: async-api-trigger")
}

func TestGetTriggerRejectsAnAddressThatIsNotAURL(t *testing.T) {
	_, err := NewFactory(http.DefaultClient).
		GetTrigger("://engine", HTTPTriggerType, map[string]any{"contextPath": "/orders"})

	require.Error(t, err)
	assert.Contains(t, err.Error(), "is not a URL")
}
