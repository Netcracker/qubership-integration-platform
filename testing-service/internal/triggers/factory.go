package triggers

import (
	"fmt"
	"net/http"
	"net/url"
	"strings"
)

const (
	// HTTPTriggerType is the chain element type this package knows how to activate.
	HTTPTriggerType = "http-trigger"
	// triggersPathSegment is where the engine publishes chain HTTP triggers; it
	// mirrors CAMEL_ROUTES_PREFIX on the engine side.
	triggersPathSegment = "routes"
)

// Factory builds a Trigger from a chain element type and its properties.
type Factory interface {
	GetTrigger(triggerType string, parameters map[string]any) (Trigger, error)
}

type factory struct {
	triggersURL string
	httpClient  *http.Client
}

// NewFactory returns a Factory that activates chains published under the given
// engine address. The client carries the host's authorization as a round
// tripper, so it is shared by every trigger the factory builds; testingservice.New
// has already substituted one for a host that supplied none.
func NewFactory(engineAddress string, httpClient *http.Client) (Factory, error) {
	triggersURL, err := url.JoinPath(strings.TrimSuffix(engineAddress, "/"), triggersPathSegment)
	if err != nil {
		return nil, fmt.Errorf("engine address %q is not a URL: %w", engineAddress, err)
	}
	return &factory{triggersURL: triggersURL, httpClient: httpClient}, nil
}

func (f *factory) GetTrigger(triggerType string, parameters map[string]any) (Trigger, error) {
	switch triggerType {
	case HTTPTriggerType:
		return NewHTTPTrigger(f.triggersURL, f.httpClient, parameters)
	default:
		return nil, fmt.Errorf("trigger type not supported: %s", triggerType)
	}
}
