package matching

import (
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/httpfield"
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

// What part of a message a matcher inspects. These are the values of the
// entity_type enum in the database, and this is the one place they are spelled
// out: the column carries what this table dispatches on.
const (
	EntityTypeBody           = "body"
	EntityTypeHeader         = "header"
	EntityTypeStatus         = "status"
	EntityTypeQueryParameter = "query_parameter"
	EntityTypePathParameter  = "path_parameter"
)

// entityDataGetters is the whole set of entity types. A table rather than a
// switch, so entityTypes names exactly what the factory builds.
var entityDataGetters = map[string]func(entityName string) EntityDataGetter{
	EntityTypeBody:           func(string) EntityDataGetter { return &bodyGetter{} },
	EntityTypeHeader:         func(name string) EntityDataGetter { return &headerGetter{Name: name} },
	EntityTypeStatus:         func(string) EntityDataGetter { return &statusGetter{} },
	EntityTypeQueryParameter: func(name string) EntityDataGetter { return &queryParameterGetter{Name: name} },
	EntityTypePathParameter:  func(name string) EntityDataGetter { return &pathParameterGetter{Name: name} },
}

// entityNameCheckers are the entity types that address one named part of a
// message, each with the grammar its name has to follow. The other two — the
// body and the status — are the message itself and take no name.
//
// Every grammar answers the same question: can any request carry a value under
// this name? Each one says what a name may be rather than which characters it
// may not hold, because a list of banned characters keeps missing one.
//
// The three differ because the three names travel differently. A header field
// name is an RFC 9110 token, the only spelling a header line can carry, so
// `X Mocked` names no header. A query parameter name is any non-blank string:
// the URL carries it percent-encoded, so `X Mocked` arrives as `X+Mocked` and is
// found again. A path parameter name is read back out of a literal `{name}`
// placeholder in the operation path, which is a far narrower opening — see
// checkPathParameterName.
var entityNameCheckers = map[string]func(name string) error{
	EntityTypeHeader:         checkHeaderName,
	EntityTypeQueryParameter: checkParameterName,
	EntityTypePathParameter:  checkPathParameterName,
}

// GetEntityDataGetter refuses a named entity type whose name addresses nothing.
// Such a getter reads nothing from every exchange, so an `empty` matcher over it
// holds for every call, and an endpoint mock carrying one answers calls meant for
// the more specific mocks it outranks on creation time. A blank name and a name
// the grammar of its entity type rejects are refused for the same reason: no
// header or parameter is ever found under either.
func GetEntityDataGetter(entityType string, entityName string) (EntityDataGetter, error) {
	build, ok := entityDataGetters[entityType]
	if !ok {
		return nil, fmt.Errorf("unsupported entity type: %v", entityType)
	}
	if check, ok := entityNameCheckers[entityType]; ok {
		if err := check(entityName); err != nil {
			return nil, fmt.Errorf("entity type %v %w", entityType, err)
		}
	}
	return build(entityName), nil
}

// errNoEntityName keeps the wording of the three checkers the same for the case
// they share.
var errNoEntityName = errors.New("needs an entity name")

func checkHeaderName(name string) error {
	if strings.TrimSpace(name) == "" {
		return errNoEntityName
	}
	if !httpfield.IsName(name) {
		return fmt.Errorf("needs an HTTP field name, and %q is not one", name)
	}
	return nil
}

// checkParameterName holds a query parameter name to the blank rule alone. The
// query string carries the name percent-encoded, so every other name is one some
// request can carry: a space, a slash, an ampersand and a control character all
// survive the round trip through url.Values.
func checkParameterName(name string) error {
	if strings.TrimSpace(name) == "" {
		return errNoEntityName
	}
	return nil
}

// checkPathParameterName holds the name to the one spelling that can carry it: a
// literal `{name}` placeholder taking up a whole segment of an operation path.
// Rather than name the characters that break that spelling, the check writes the
// placeholder and reads it back through the steps the getter takes. Only a name
// that comes back unchanged is one a request can produce a value for; a slash
// (two segments), a closing brace (the placeholder ends early), a question mark
// or a hash (the path ends there), a percent sign (the segment is decoded, so
// the name returns as something else) and a control character (url.Parse refuses
// the path) all fail the same one check.
func checkPathParameterName(name string) error {
	if strings.TrimSpace(name) == "" {
		return errNoEntityName
	}
	// The operation path reaches this service as a JSON string, and JSON is
	// UTF-8: an invalid byte arrives as U+FFFD, under which the name is never
	// found. The round trip below runs on Go strings and would not catch that.
	if !utf8.ValidString(name) {
		return fmt.Errorf("cannot address %q: an operation path carries no invalid UTF-8", name)
	}
	if read, ok := placeholderName("/{" + name + "}"); !ok || read != name {
		return fmt.Errorf("cannot address %q: no path template segment spells this name", name)
	}
	return nil
}

// placeholderName reads the parameter name back out of a one-segment path built
// around a placeholder, taking the steps pathParameterGetter takes.
func placeholderName(path string) (string, bool) {
	u, err := url.Parse(path)
	if err != nil {
		return "", false
	}
	segments, err := splitPathSegments(u)
	if err != nil {
		return "", false
	}
	if len(segments) != 2 || !pathTemplateSegment.MatchString(segments[1]) {
		return "", false
	}
	return segments[1][1 : len(segments[1])-1], true
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
	requestURL, err := url.Parse(testingContext.Path)
	if err != nil {
		return nil, err
	}
	query := requestURL.Query()
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
	requestURL, err := url.Parse(testingContext.Path)
	if err != nil {
		return nil, err
	}
	operationURL, err := url.Parse(testingContext.OperationPath)
	if err != nil {
		return nil, err
	}

	// The operation path is the template; the request path may carry extra
	// leading segments, so the two are aligned from the right.
	pathSegments, err := splitPathSegments(requestURL)
	if err != nil {
		return nil, err
	}
	operationSegments, err := splitPathSegments(operationURL)
	if err != nil {
		return nil, err
	}

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

// splitPathSegments cuts the path into segments before decoding them, so a
// percent-encoded slash stays inside the segment carrying it. Splitting the
// decoded path instead would turn a value of `a/b` into two segments and hand
// the matcher `b`. The trigger escapes substituted path values with
// url.PathEscape, so this is the other half of that encoding.
func splitPathSegments(u *url.URL) ([]string, error) {
	raw := strings.Split(u.EscapedPath(), "/")
	segments := make([]string, len(raw))
	for i, segment := range raw {
		decoded, err := url.PathUnescape(segment)
		if err != nil {
			return nil, err
		}
		segments[i] = decoded
	}
	return segments, nil
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
