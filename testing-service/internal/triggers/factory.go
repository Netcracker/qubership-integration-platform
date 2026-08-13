package triggers

import (
	"fmt"
	"net/http"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/qip"
)

// HTTPTriggerType is the chain element type this package knows how to activate.
const HTTPTriggerType = "http-trigger"

// Factory builds a Trigger from a chain element type and its properties.
type Factory interface {
	GetTrigger(triggerType string, parameters map[string]any) (Trigger, error)
}

type factory struct {
	engine     qip.EngineClient
	httpClient *http.Client
}

// NewFactory returns a Factory that activates chains over HTTP. The client
// carries the host's authorization as a round tripper, so it is shared by every
// trigger it builds.
func NewFactory(engine qip.EngineClient, httpClient *http.Client) Factory {
	return &factory{engine: engine, httpClient: httpClient}
}

func (f *factory) GetTrigger(triggerType string, parameters map[string]any) (Trigger, error) {
	switch triggerType {
	case HTTPTriggerType:
		return NewHTTPTrigger(f.engine, f.httpClient, parameters)
	default:
		return nil, fmt.Errorf("trigger type not supported: %s", triggerType)
	}
}
