package triggers

import (
	"bytes"
	"context"
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
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/qip"
)

const (
	// httpTriggerPathProperty names the chain element property holding the path
	// the trigger is published under.
	httpTriggerPathProperty = "contextPath"
	// sessionHeader is part of the engine contract and must keep its spelling.
	sessionHeader = "external-session-cip-id"
)

// pathParameterPattern matches a `{name}` placeholder in a trigger path.
var pathParameterPattern = regexp.MustCompile(`\{[^}]+}`)

type httpTrigger struct {
	engine     qip.EngineClient
	httpClient *http.Client
	path       string
}

// NewHTTPTrigger reads the trigger path out of the chain element properties.
func NewHTTPTrigger(engine qip.EngineClient, httpClient *http.Client, parameters map[string]any) (Trigger, error) {
	path, ok := parameters[httpTriggerPathProperty]
	if !ok {
		return nil, fmt.Errorf("missing %s property", httpTriggerPathProperty)
	}
	return &httpTrigger{engine: engine, httpClient: httpClient, path: fmt.Sprintf("%v", path)}, nil
}

func (t *httpTrigger) Activate(ctx context.Context, requestSettings *dao.RequestSettings) (*model.Exchange, error) {
	// The client is shared, so the per-case timeout has to ride on the context.
	if requestSettings.Timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, time.Duration(requestSettings.Timeout)*time.Millisecond)
		defer cancel()
	}
	request, err := t.buildRequest(ctx, requestSettings)
	if err != nil {
		return nil, fmt.Errorf("failed to build request: %w", err)
	}
	response, err := t.httpClient.Do(request)
	if err != nil {
		return nil, fmt.Errorf("failed to activate trigger: %w", err)
	}
	defer response.Body.Close()
	exchange, err := convertResponseToExchange(response)
	if err != nil {
		return nil, fmt.Errorf("failed to convert HTTP response to trigger response: %w", err)
	}
	return exchange, nil
}

func (t *httpTrigger) buildRequest(ctx context.Context, requestSettings *dao.RequestSettings) (*http.Request, error) {
	requestURL, err := t.buildURL(requestSettings)
	if err != nil {
		return nil, err
	}
	sessionID, err := SessionID(ctx)
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
	address, err := t.engine.HTTPTriggersURL()
	if err != nil {
		return "", err
	}
	path, err := resolvePathParameters(t.path, requestSettings.PathParameters)
	if err != nil {
		return "", err
	}
	address, err = url.JoinPath(address, path)
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
	return result.String(), nil
}
