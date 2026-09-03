package matching

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/httpfield"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/model"
)

func exchangeWithContext(t *testing.T, testingContext model.TestingContext) model.Exchange {
	t.Helper()
	data, err := json.Marshal(testingContext)
	require.NoError(t, err)
	return exchangeWithHeader(base64.StdEncoding.EncodeToString(data))
}

func exchangeWithHeader(values ...string) model.Exchange {
	return model.Exchange{Headers: map[string][]string{model.TestingContextHeader: values}}
}

func getData(t *testing.T, entityType, entityName string, exchange model.Exchange) (*[]byte, error) {
	t.Helper()
	getter, err := GetEntityDataGetter(entityType, entityName)
	require.NoError(t, err)
	return getter.GetData(exchange)
}

func requireData(t *testing.T, entityType, entityName string, exchange model.Exchange) string {
	t.Helper()
	data, err := getData(t, entityType, entityName, exchange)
	require.NoError(t, err)
	require.NotNil(t, data)
	return string(*data)
}

func requireNoData(t *testing.T, entityType, entityName string, exchange model.Exchange) {
	t.Helper()
	data, err := getData(t, entityType, entityName, exchange)
	require.NoError(t, err)
	assert.Nil(t, data)
}

func TestGetEntityDataGetterCoversEveryEntityType(t *testing.T) {
	// The list comes off the factory table, so an entity type added to the
	// factory is a type this test has to cover.
	types := entityTypes()
	require.Len(t, types, 5, "a new entity type needs its own data test below")
	for _, entityType := range types {
		t.Run(entityType, func(t *testing.T) {
			getter, err := GetEntityDataGetter(entityType, "name")

			require.NoError(t, err)
			assert.NotNil(t, getter)
		})
	}
}

// A getter built without a name reads nothing out of every exchange, and a
// matcher over nothing holds for every call.
func TestGetEntityDataGetterRejectsANamedEntityTypeWithoutAName(t *testing.T) {
	for _, entityType := range []string{EntityTypeHeader, EntityTypeQueryParameter, EntityTypePathParameter} {
		for _, entityName := range []string{"", " ", "\t"} {
			t.Run(entityType+"/"+strconv.Quote(entityName), func(t *testing.T) {
				getter, err := GetEntityDataGetter(entityType, entityName)

				require.Error(t, err)
				assert.Nil(t, getter)
				assert.Contains(t, err.Error(), entityType)
			})
		}
	}
}

// The body and the status are the message itself and take no name.
func TestGetEntityDataGetterAcceptsAnEntityTypeThatTakesNoName(t *testing.T) {
	for _, entityType := range []string{EntityTypeBody, EntityTypeStatus} {
		t.Run(entityType, func(t *testing.T) {
			getter, err := GetEntityDataGetter(entityType, "")

			require.NoError(t, err)
			assert.NotNil(t, getter)
		})
	}
}

// A header name that is not an RFC 9110 token names no header a request can
// carry: net/http leaves it out of the canonical form, so the getter reads
// nothing and the matcher over it holds for every call — the same shadowing a
// blank name would cause, one space away.
func TestGetEntityDataGetterRejectsAHeaderNameThatIsNotAFieldName(t *testing.T) {
	for _, entityName := range []string{"X Mocked", "X\tMocked", "X:Mocked", "Accept(json)", "X-Mocked\n", "заголовок"} {
		t.Run(strconv.Quote(entityName), func(t *testing.T) {
			getter, err := GetEntityDataGetter(EntityTypeHeader, entityName)

			require.Error(t, err)
			assert.Nil(t, getter)
			assert.Contains(t, err.Error(), EntityTypeHeader)
		})
	}
}

func TestGetEntityDataGetterAcceptsAHeaderFieldName(t *testing.T) {
	for _, entityName := range []string{"Accept", "content-type", "X-Mocked", "x_mocked!", "X-Trace.1"} {
		t.Run(entityName, func(t *testing.T) {
			getter, err := GetEntityDataGetter(EntityTypeHeader, entityName)

			require.NoError(t, err)
			assert.NotNil(t, getter)
		})
	}
}

// A query parameter name is not a field name: the URL carries it
// percent-encoded, so `X Mocked` arrives as `X+Mocked` and is found.
func TestGetEntityDataGetterAcceptsAQueryParameterNameOutsideTheFieldNameGrammar(t *testing.T) {
	entityName := "X Mocked"
	exchange := exchangeWithContext(t, model.TestingContext{Path: "/orders?X+Mocked=yes"})

	assert.Equal(t, "yes", requireData(t, EntityTypeQueryParameter, entityName, exchange))
}

// A path parameter name is read out of a {name} placeholder, and a name that
// holds a closing brace, a slash or a percent sign is one no placeholder can
// spell.
func TestGetEntityDataGetterRejectsAPathParameterNameNoPlaceholderCanSpell(t *testing.T) {
	for _, entityName := range []string{"order}Id", "order/Id", "orders/{orderId}", "order%2FId", "100%", "order\nId"} {
		t.Run(strconv.Quote(entityName), func(t *testing.T) {
			getter, err := GetEntityDataGetter(EntityTypePathParameter, entityName)

			require.Error(t, err)
			assert.Nil(t, getter)
			assert.Contains(t, err.Error(), EntityTypePathParameter)
		})
	}
}

func TestGetEntityDataGetterAcceptsAPathParameterNameOutsideTheFieldNameGrammar(t *testing.T) {
	entityName := "order Id"
	exchange := exchangeWithContext(t, model.TestingContext{
		OperationPath: "/orders/{order Id}",
		Path:          "/orders/42",
	})

	assert.Equal(t, "42", requireData(t, EntityTypePathParameter, entityName, exchange))
}

// entityNameProbes walks the name space instead of a handful of examples: every
// byte on its own, inside a name and at the head of one, plus the runes a
// byte-wise rule reads wrong.
func entityNameProbes() []string {
	probes := make([]string, 0, 3*256+16)
	for b := 0; b < 256; b++ {
		character := string([]byte{byte(b)})
		probes = append(probes, character, "order"+character+"Id", character+"orderId")
	}
	return append(probes,
		"orderId", "order Id", "заказ", "注文", "🧾", "é", "a b", "�", "%2F", "{orderId}",
	)
}

// matchableHeader reports whether a response can carry a header under this name.
// The raw response goes through net/http, which is what fills Exchange.Headers
// for a test case's response validation rules. A header of its own precedes the
// probe, so a name starting with a space is read as the folded continuation line
// it is rather than as a malformed first line.
func matchableHeader(t *testing.T, entityName string) bool {
	t.Helper()
	raw := "HTTP/1.1 200 OK\r\nX-Mocked: yes\r\n" + entityName + ": mocked\r\n\r\n"
	response, err := http.ReadResponse(bufio.NewReader(strings.NewReader(raw)), nil)
	if err != nil {
		return false
	}
	defer response.Body.Close()
	data, err := (&headerGetter{Name: entityName}).GetData(model.Exchange{Headers: response.Header})
	return err == nil && data != nil
}

// matchableQueryParameter reports whether a request URL can carry the parameter.
// The name goes into the query string the way a client writes it, percent-encoded.
func matchableQueryParameter(t *testing.T, entityName string) bool {
	t.Helper()
	path := "/orders?" + url.QueryEscape(entityName) + "=mocked"
	data, err := (&queryParameterGetter{Name: entityName}).GetData(
		exchangeWithContext(t, model.TestingContext{Path: path}))
	return err == nil && data != nil
}

// matchablePathParameter reports whether an operation path can carry the
// parameter. A path template spells a placeholder literally — that is the form
// the trigger substitutes into and the form a specification carries — so the
// name goes between braces as it stands.
func matchablePathParameter(t *testing.T, entityName string) bool {
	t.Helper()
	data, err := (&pathParameterGetter{Name: entityName}).GetData(
		exchangeWithContext(t, model.TestingContext{
			OperationPath: "/orders/{" + entityName + "}",
			Path:          "/orders/mocked",
		}))
	return err == nil && data != nil
}

// The name checks exist to refuse a name no request can produce a value for,
// because a matcher over such a name never sees data: `empty` over it holds for
// every call, and the mock carrying it answers calls meant for the mocks it
// outranks. Three rounds of review found three names of that kind — a blank one,
// a header name with a space, a path parameter name with a slash — each of which
// slipped past a check that banned the character the round before had named.
//
// This test asserts the property those checks are for, over the byte space
// rather than over examples: every name the factory accepts is one some request
// carries. The other direction holds too, up to two deliberate refusals that
// assertRefusedOnPurpose pins so they cannot grow.
func TestEntityNameValidationAcceptsExactlyTheMatchableNames(t *testing.T) {
	oracles := map[string]func(*testing.T, string) bool{
		EntityTypeHeader:         matchableHeader,
		EntityTypeQueryParameter: matchableQueryParameter,
		EntityTypePathParameter:  matchablePathParameter,
	}
	require.Len(t, oracles, len(entityNameCheckers), "a named entity type needs its own oracle here")

	for entityType, matchable := range oracles {
		t.Run(entityType, func(t *testing.T) {
			for _, entityName := range entityNameProbes() {
				_, err := GetEntityDataGetter(entityType, entityName)
				switch {
				case err == nil:
					assert.True(t, matchable(t, entityName),
						"accepted %q, which no request produces a value for", entityName)
				case matchable(t, entityName):
					assertRefusedOnPurpose(t, entityType, entityName)
				}
			}
		})
	}
}

// assertRefusedOnPurpose covers the two shapes of name that stay refused even
// though a request can carry one:
//
//   - A blank name. A query string and a path template can both spell one, and
//     it is a typo every time.
//   - A header field name holding a space. RFC 9110 admits no such name, and the
//     mock call path never finds one, since fasthttp folds `X Mocked` into
//     `X mocked` while the getter asks for `X Mocked`. It reaches the oracle only
//     because net/http keeps a name with a space before the colon verbatim
//     (go.dev/issue/34540).
//
// Any other refusal of a name a request carries is this package being wrong.
func assertRefusedOnPurpose(t *testing.T, entityType, entityName string) {
	t.Helper()
	if strings.TrimSpace(entityName) == "" {
		return
	}
	require.Equal(t, EntityTypeHeader, entityType,
		"refused %q, which a request does produce a value for", entityName)
	assert.True(t, httpfield.IsName(strings.ReplaceAll(entityName, " ", "")),
		"refused the header name %q for something other than the space net/http tolerates", entityName)
}

func TestGetEntityDataGetterRejectsAnUnknownEntityType(t *testing.T) {
	getter, err := GetEntityDataGetter("cookie", "session")

	require.Error(t, err)
	assert.Nil(t, getter)
	assert.Contains(t, err.Error(), "cookie")
}

func TestBodyGetterReturnsTheBody(t *testing.T) {
	exchange := model.Exchange{Body: []byte(`{"a":1}`)}

	assert.Equal(t, `{"a":1}`, requireData(t, "body", "", exchange))
}

func TestHeaderGetterJoinsRepeatedValues(t *testing.T) {
	exchange := model.Exchange{Headers: map[string][]string{"Accept": {"text/plain", "text/html"}}}

	assert.Equal(t, "text/plain,text/html", requireData(t, "header", "accept", exchange))
}

func TestHeaderGetterReturnsNothingForAnAbsentHeader(t *testing.T) {
	requireNoData(t, "header", "Accept", model.Exchange{})
}

func TestStatusGetterReturnsTheStatusAsText(t *testing.T) {
	assert.Equal(t, "404", requireData(t, "status", "", model.Exchange{Status: 404}))
}

func TestQueryParameterGetterReadsTheParameterFromTheContextPath(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{Path: "/orders?limit=10&offset=20"})

	assert.Equal(t, "10", requireData(t, "query_parameter", "limit", exchange))
	assert.Equal(t, "20", requireData(t, "query_parameter", "offset", exchange))
}

func TestQueryParameterGetterReturnsTheFirstOfRepeatedParameters(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{Path: "/orders?status=new&status=paid"})

	assert.Equal(t, "new", requireData(t, "query_parameter", "status", exchange))
}

func TestQueryParameterGetterKeepsAnEmptyValueDistinctFromAnAbsentOne(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{Path: "/orders?status="})

	assert.Equal(t, "", requireData(t, "query_parameter", "status", exchange))
	requireNoData(t, "query_parameter", "limit", exchange)
}

func TestQueryParameterGetterReturnsNothingWithoutAQueryString(t *testing.T) {
	requireNoData(t, "query_parameter", "limit", exchangeWithContext(t, model.TestingContext{Path: "/orders"}))
}

func TestPathParameterGetterSubstitutesASingleParameter(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{
		OperationPath: "/orders/{orderId}",
		Path:          "/orders/42",
	})

	assert.Equal(t, "42", requireData(t, "path_parameter", "orderId", exchange))
}

func TestPathParameterGetterSubstitutesEveryParameter(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{
		OperationPath: "/orders/{orderId}/items/{itemId}",
		Path:          "/orders/42/items/7",
	})

	assert.Equal(t, "42", requireData(t, "path_parameter", "orderId", exchange))
	assert.Equal(t, "7", requireData(t, "path_parameter", "itemId", exchange))
}

func TestPathParameterGetterAlignsTheTemplateWithTheEndOfTheRequestPath(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{
		OperationPath: "/orders/{orderId}",
		Path:          "/routes/chain-1/orders/42",
	})

	assert.Equal(t, "42", requireData(t, "path_parameter", "orderId", exchange))
}

func TestPathParameterGetterIgnoresTheQueryString(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{
		OperationPath: "/orders/{orderId}",
		Path:          "/orders/42?full=true",
	})

	assert.Equal(t, "42", requireData(t, "path_parameter", "orderId", exchange))
}

// The trigger escapes a substituted path value with url.PathEscape, so a value
// carrying a slash arrives percent-encoded. Splitting the decoded path made it
// two segments and handed the matcher the tail of the value.
func TestPathParameterGetterKeepsAnEncodedSlashInsideTheSegment(t *testing.T) {
	value := "2026/08/13"
	exchange := exchangeWithContext(t, model.TestingContext{
		OperationPath: "/reports/{period}",
		Path:          "/reports/" + url.PathEscape(value),
	})

	assert.Equal(t, value, requireData(t, "path_parameter", "period", exchange))
}

func TestPathParameterGetterDecodesTheSegmentItReads(t *testing.T) {
	for name, value := range map[string]string{
		"space":       "two words",
		"percent":     "100%",
		"question":    "a?b",
		"hash":        "a#b",
		"non-latin":   "заказ",
		"plus":        "a+b",
		"encoded dot": "..",
	} {
		t.Run(name, func(t *testing.T) {
			exchange := exchangeWithContext(t, model.TestingContext{
				OperationPath: "/orders/{orderId}",
				Path:          "/orders/" + url.PathEscape(value),
			})

			assert.Equal(t, value, requireData(t, "path_parameter", "orderId", exchange))
		})
	}
}

// An encoded slash may not shift the alignment of the template either.
func TestPathParameterGetterAlignsTheTemplateAcrossAnEncodedSlash(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{
		OperationPath: "/orders/{orderId}/items/{itemId}",
		Path:          "/routes/chain-1/orders/" + url.PathEscape("a/b") + "/items/7",
	})

	assert.Equal(t, "a/b", requireData(t, "path_parameter", "orderId", exchange))
	assert.Equal(t, "7", requireData(t, "path_parameter", "itemId", exchange))
}

func TestPathParameterGetterReturnsNothingForAnUnknownParameter(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{
		OperationPath: "/orders/{orderId}",
		Path:          "/orders/42",
	})

	requireNoData(t, "path_parameter", "itemId", exchange)
}

func TestPathParameterGetterReturnsNothingWhenTheTemplateHasNoPlaceholder(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{
		OperationPath: "/orders",
		Path:          "/orders",
	})

	requireNoData(t, "path_parameter", "orderId", exchange)
}

func TestPathParameterGetterReturnsNothingWhenTheRequestPathIsShorter(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{
		OperationPath: "/orders/{orderId}/items/{itemId}",
		Path:          "/42",
	})

	requireNoData(t, "path_parameter", "orderId", exchange)
}

// Both context-backed getters share the header handling, so the malformed-input
// cases run against each of them.
func forEachContextGetter(t *testing.T, run func(t *testing.T, entityType, entityName string)) {
	t.Helper()
	for entityType, entityName := range map[string]string{
		"query_parameter": "limit",
		"path_parameter":  "orderId",
	} {
		t.Run(entityType, func(t *testing.T) { run(t, entityType, entityName) })
	}
}

func TestContextGettersReturnNothingWithoutTheContextHeader(t *testing.T) {
	forEachContextGetter(t, func(t *testing.T, entityType, entityName string) {
		requireNoData(t, entityType, entityName, model.Exchange{Headers: map[string][]string{"Accept": {"*/*"}}})
	})
}

func TestContextGettersRejectARepeatedContextHeader(t *testing.T) {
	forEachContextGetter(t, func(t *testing.T, entityType, entityName string) {
		data, err := getData(t, entityType, entityName, exchangeWithHeader("first", "second"))

		require.Error(t, err)
		assert.Nil(t, data)
		assert.Contains(t, err.Error(), model.TestingContextHeader)
	})
}

func TestContextGettersRejectAnEmptyContextHeader(t *testing.T) {
	forEachContextGetter(t, func(t *testing.T, entityType, entityName string) {
		data, err := getData(t, entityType, entityName, exchangeWithHeader())

		require.Error(t, err)
		assert.Nil(t, data)
	})
}

// The path-parameter getter used to ignore this error and dereference the nil
// context, so these cases panicked instead of failing.
func TestContextGettersRejectAMalformedContextHeader(t *testing.T) {
	forEachContextGetter(t, func(t *testing.T, entityType, entityName string) {
		for name, header := range map[string]string{
			"not base64":     "not base64!",
			"not json":       base64.StdEncoding.EncodeToString([]byte("{")),
			"not an object":  base64.StdEncoding.EncodeToString([]byte(`["orderId"]`)),
			"empty document": base64.StdEncoding.EncodeToString(nil),
		} {
			t.Run(name, func(t *testing.T) {
				data, err := getData(t, entityType, entityName, exchangeWithHeader(header))

				require.Error(t, err)
				assert.Nil(t, data)
				assert.Contains(t, err.Error(), model.TestingContextHeader)
			})
		}
	})
}

func TestContextGettersRejectAMalformedRequestPath(t *testing.T) {
	forEachContextGetter(t, func(t *testing.T, entityType, entityName string) {
		exchange := exchangeWithContext(t, model.TestingContext{
			OperationPath: "/orders/{orderId}",
			Path:          "/orders/%zz",
		})

		data, err := getData(t, entityType, entityName, exchange)

		require.Error(t, err)
		assert.Nil(t, data)
	})
}

func TestPathParameterGetterRejectsAMalformedOperationPath(t *testing.T) {
	exchange := exchangeWithContext(t, model.TestingContext{
		OperationPath: "/orders/%zz/{orderId}",
		Path:          "/orders/1/42",
	})

	data, err := getData(t, "path_parameter", "orderId", exchange)

	require.Error(t, err)
	assert.Nil(t, data)
}
