package triggers

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"time"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

const (
	// httpTriggerPathProperty names the chain element property holding the path
	// the trigger is published under.
	httpTriggerPathProperty = "contextPath"
	// sessionHeader is part of the engine contract and must keep its spelling.
	sessionHeader = "external-session-cip-id"
)

// defaultTimeout bounds a test case that set none. The client is shared and
// carries no timeout of its own, so without a ceiling a hung engine would pin
// the worker and its lease renewal until the process shuts down. It is a
// variable so a test can shorten it.
var defaultTimeout = 15 * time.Minute

// pathParameterPattern matches a `{name}` placeholder in a trigger path.
var pathParameterPattern = regexp.MustCompile(`\{[^}]+}`)

type httpTrigger struct {
	triggersURL string
	httpClient  *http.Client
	path        string
}

// NewHTTPTrigger reads the trigger path out of the chain element properties.
func NewHTTPTrigger(triggersURL string, httpClient *http.Client, parameters map[string]any) (Trigger, error) {
	path, ok := parameters[httpTriggerPathProperty]
	if !ok {
		return nil, fmt.Errorf("missing %s property", httpTriggerPathProperty)
	}
	return &httpTrigger{triggersURL: triggersURL, httpClient: httpClient, path: fmt.Sprintf("%v", path)}, nil
}

func (t *httpTrigger) Activate(
	ctx context.Context,
	sessionID string,
	requestSettings *dao.RequestSettings,
) (*model.Exchange, error) {
	// Request settings are optional on a test case, so an enabled case may reach
	// the executor without them. There is no request to send then, and this runs
	// in a worker goroutine where a dereference would take the process down.
	if requestSettings == nil {
		return nil, errors.New("the test case has no request settings")
	}
	// The client is shared, so the per-case timeout has to ride on the context.
	timeout := defaultTimeout
	if requestSettings.Timeout > 0 {
		timeout = time.Duration(requestSettings.Timeout) * time.Millisecond
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	request, err := t.buildRequest(ctx, sessionID, requestSettings)
	if err != nil {
		return nil, fmt.Errorf("failed to build request: %w", err)
	}
	response, err := t.httpClient.Do(request)
	if err != nil {
		// The client error already names the method and the URL, and the caller
		// prefixes what it was doing, so wrapping here only repeats one of them.
		return nil, err
	}
	defer response.Body.Close()
	exchange, err := convertResponseToExchange(response)
	if err != nil {
		return nil, fmt.Errorf("failed to convert HTTP response to trigger response: %w", err)
	}
	return exchange, nil
}

func (t *httpTrigger) buildRequest(
	ctx context.Context,
	sessionID string,
	requestSettings *dao.RequestSettings,
) (*http.Request, error) {
	requestURL, err := t.buildURL(requestSettings)
	if err != nil {
		return nil, err
	}
	var body bytes.Buffer
	if requestSettings.Message != nil && requestSettings.Message.Body != nil {
		body.WriteString(*requestSettings.Message.Body)
	}
	request, err := http.NewRequestWithContext(ctx, requestSettings.Method, requestURL, &body)
	if err != nil {
		return nil, err
	}
	request.Header.Add(sessionHeader, sessionID)
	if requestSettings.Message != nil {
		for _, header := range requestSettings.Message.Headers {
			if header == nil {
				continue
			}
			request.Header.Add(header.Name, header.Value)
		}
	}
	return request, nil
}

func (t *httpTrigger) buildURL(requestSettings *dao.RequestSettings) (string, error) {
	path, err := resolvePathParameters(t.path, requestSettings.PathParameters)
	if err != nil {
		return "", err
	}
	address, err := url.JoinPath(t.triggersURL, path)
	if err != nil {
		return "", err
	}
	u, err := url.Parse(address)
	if err != nil {
		return "", err
	}
	values := u.Query()
	for _, parameter := range requestSettings.QueryParameters {
		if parameter == nil {
			continue
		}
		values.Add(parameter.Name, parameter.Value)
	}
	u.RawQuery = values.Encode()
	return u.String(), nil
}

func convertResponseToExchange(response *http.Response) (*model.Exchange, error) {
	responseBody, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response content: %w", err)
	}
	return &model.Exchange{Body: responseBody, Headers: response.Header, Status: response.StatusCode}, nil
}

// resolvePathParameters replaces every `{name}` placeholder in path with the
// value of the matching parameter.
func resolvePathParameters(path string, parameters []*dao.PathParameter) (string, error) {
	var result strings.Builder
	cursor := 0
	for _, match := range pathParameterPattern.FindAllStringIndex(path, -1) {
		name := path[match[0]+1 : match[1]-1]
		index := slices.IndexFunc(parameters, func(parameter *dao.PathParameter) bool {
			return parameter != nil && parameter.Name == name
		})
		if index < 0 {
			return "", fmt.Errorf("path parameter %q is not defined", name)
		}
		result.WriteString(path[cursor:match[0]])
		// Escape the value so it cannot add path segments or a query string.
		result.WriteString(url.PathEscape(parameters[index].Value))
		cursor = match[1]
	}
	result.WriteString(path[cursor:])
	resolved := result.String()
	// url.JoinPath collapses dot segments, and PathEscape leaves dots alone, so a
	// value of ".." would walk the request out of the engine's trigger prefix.
	for _, segment := range strings.Split(resolved, "/") {
		if segment == "." || segment == ".." {
			return "", fmt.Errorf("trigger path %q walks out of the engine prefix", resolved)
		}
	}
	return resolved, nil
}
