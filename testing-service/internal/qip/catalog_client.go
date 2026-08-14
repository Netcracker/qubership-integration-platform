package qip

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// defaultTimeout bounds one catalog lookup. It is a variable so a test can
// shorten it.
var defaultTimeout = 30 * time.Second

// CatalogClient reads chain elements from the runtime catalog.
type CatalogClient interface {
	// FindChainElement returns the element with the given id. The catalog looks
	// the element up by its own id, but the chain is part of the route.
	FindChainElement(ctx context.Context, chainID, elementID string) (*ChainElement, error)
}

type catalogClient struct {
	address    string
	httpClient *http.Client
}

// NewCatalogClient returns a client for the runtime catalog at the given base
// address. The client carries the host's authorization as a round tripper, and
// testingservice.New has already substituted one for a host that supplied none.
func NewCatalogClient(address string, httpClient *http.Client) CatalogClient {
	return &catalogClient{address: strings.TrimSuffix(address, "/"), httpClient: httpClient}
}

func (c *catalogClient) FindChainElement(ctx context.Context, chainID, elementID string) (*ChainElement, error) {
	// The caller is an executor worker, whose context lives until shutdown, and
	// the shared client carries no timeout. A catalog that accepts the connection
	// and never answers would hold the worker — and the lease it keeps renewing —
	// for as long as the process runs, which is the one stall the sweeper cannot
	// recover from.
	ctx, cancel := context.WithTimeout(ctx, defaultTimeout)
	defer cancel()

	requestURL := fmt.Sprintf("%s/v1/chains/%s/elements/%s",
		c.address, url.PathEscape(chainID), url.PathEscape(elementID))

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, requestURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	response, err := c.httpClient.Do(request)
	if err != nil {
		return nil, fmt.Errorf("failed to get chain element: %w", err)
	}
	defer response.Body.Close()

	responseBody, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response content: %w", err)
	}
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("non 200 response from the runtime catalog to request %q: %d - %s",
			requestURL, response.StatusCode, string(responseBody))
	}

	var chainElement ChainElement
	if err := json.Unmarshal(responseBody, &chainElement); err != nil {
		return nil, fmt.Errorf("failed to parse response content: %w", err)
	}
	return &chainElement, nil
}
