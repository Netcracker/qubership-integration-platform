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
	// GetTrigger builds a trigger that activates the chain on engineAddress. The
	// address is a parameter rather than a field, because a chain reaches the
	// testing service from whichever domain it was deployed to, and those do not
	// share one address.
	GetTrigger(engineAddress string, triggerType string, parameters map[string]any) (Trigger, error)
}

type factory struct {
	httpClient *http.Client
}

// NewFactory returns a Factory over the given client. The client carries the
// host's authorization as a round tripper, so it is shared by every trigger the
// factory builds; testingservice.New has already substituted one for a host that
// supplied none.
func NewFactory(httpClient *http.Client) Factory {
	return &factory{httpClient: httpClient}
}

func (f *factory) GetTrigger(
	engineAddress string,
	triggerType string,
	parameters map[string]any,
) (Trigger, error) {
	triggersURL, err := url.JoinPath(strings.TrimSuffix(engineAddress, "/"), triggersPathSegment)
	if err != nil {
		return nil, fmt.Errorf("engine address %q is not a URL: %w", engineAddress, err)
	}
	switch triggerType {
	case HTTPTriggerType:
		return NewHTTPTrigger(triggersURL, f.httpClient, parameters)
	default:
		return nil, fmt.Errorf("trigger type not supported: %s", triggerType)
	}
}
