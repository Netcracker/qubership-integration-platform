package qip

import (
	"net/url"
	"strings"
)

// triggersPathSegment is where the engine publishes chain HTTP triggers; it
// mirrors CAMEL_ROUTES_PREFIX on the engine side.
const triggersPathSegment = "routes"

// EngineClient locates the engine endpoints the testing service calls.
type EngineClient interface {
	// HTTPTriggersURL returns the base URL that chain HTTP triggers hang off.
	HTTPTriggersURL() (string, error)
}

type engineClient struct {
	address string
}

// NewEngineClient returns a client for the engine at the given base address.
func NewEngineClient(address string) EngineClient {
	return &engineClient{address: strings.TrimSuffix(address, "/")}
}

func (c *engineClient) HTTPTriggersURL() (string, error) {
	return url.JoinPath(c.address, triggersPathSegment)
}
