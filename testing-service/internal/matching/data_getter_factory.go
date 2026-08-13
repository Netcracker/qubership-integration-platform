package matching

import (
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

// pathTemplateSegment matches a whole path segment that is a template
// placeholder, such as {orderId}.
var pathTemplateSegment = regexp.MustCompile(`^\{[^}]+\}$`)

type bodyGetter struct{}

type headerGetter struct {
	Name string
}

type statusGetter struct{}

type queryParameterGetter struct {
	Name string
}

type pathParameterGetter struct {
	Name string
}

func GetEntityDataGetter(entityType string, entityName string) (EntityDataGetter, error) {
	switch entityType {
	case "body":
		return &bodyGetter{}, nil
	case "header":
		return &headerGetter{Name: entityName}, nil
	case "status":
		return &statusGetter{}, nil
	case "query_parameter":
		return &queryParameterGetter{Name: entityName}, nil
	case "path_parameter":
		return &pathParameterGetter{Name: entityName}, nil
	default:
		return nil, fmt.Errorf("unsupported entity type: %v", entityType)
	}
}

func (g *bodyGetter) GetData(exchange model.Exchange) (*[]byte, error) {
	return &exchange.Body, nil
}

func (g *headerGetter) GetData(exchange model.Exchange) (*[]byte, error) {
	values, ok := exchange.Headers[http.CanonicalHeaderKey(g.Name)]
	if !ok {
		return nil, nil
	}
	value := []byte(strings.Join(values, ","))
	return &value, nil
}

func (g *statusGetter) GetData(exchange model.Exchange) (*[]byte, error) {
	value := []byte(strconv.Itoa(exchange.Status))
	return &value, nil
}

func (g *queryParameterGetter) GetData(exchange model.Exchange) (*[]byte, error) {
	testingContext, err := testingContextOf(exchange)
	if err != nil || testingContext == nil {
		return nil, err
	}
	requestUrl, err := url.Parse(testingContext.Path)
	if err != nil {
		return nil, err
	}
	query := requestUrl.Query()
	if !query.Has(g.Name) {
		return nil, nil
	}
	value := []byte(query.Get(g.Name))
	return &value, nil
}

func (g *pathParameterGetter) GetData(exchange model.Exchange) (*[]byte, error) {
	testingContext, err := testingContextOf(exchange)
	if err != nil || testingContext == nil {
		return nil, err
	}
	requestUrl, err := url.Parse(testingContext.Path)
	if err != nil {
		return nil, err
	}
	operationUrl, err := url.Parse(testingContext.OperationPath)
	if err != nil {
		return nil, err
	}

	// The operation path is the template; the request path may carry extra
	// leading segments, so the two are aligned from the right.
	pathSegments := strings.Split(requestUrl.Path, "/")
	operationSegments := strings.Split(operationUrl.Path, "/")

	parameters := make(map[string]string)
	for i, segment := range operationSegments {
		if !pathTemplateSegment.MatchString(segment) {
			continue
		}
		name := segment[1 : len(segment)-1]
		idx := len(pathSegments) - len(operationSegments) + i
		if idx >= 0 && idx < len(pathSegments) {
			parameters[name] = pathSegments[idx]
		}
	}

	v, ok := parameters[g.Name]
	if !ok {
		return nil, nil
	}
	value := []byte(v)
	return &value, nil
}

// testingContextOf returns nil without an error when the exchange carries no
// testing context header, which is the case for exchanges that did not come
// through a tested chain.
func testingContextOf(exchange model.Exchange) (*model.TestingContext, error) {
	values, ok := exchange.Headers[http.CanonicalHeaderKey(model.TestingContextHeader)]
	if !ok {
		return nil, nil
	}
	if len(values) != 1 {
		return nil, fmt.Errorf("wrong %v header value: %v", model.TestingContextHeader, strings.Join(values, ","))
	}
	return model.DecodeTestingContext(values[0])
}
