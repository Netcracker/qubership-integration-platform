package matching

import (
	"encoding/base64"
	"encoding/json"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

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
	for _, entityType := range []string{"body", "header", "status", "query_parameter", "path_parameter"} {
		t.Run(entityType, func(t *testing.T) {
			getter, err := GetEntityDataGetter(entityType, "name")

			require.NoError(t, err)
			assert.NotNil(t, getter)
		})
	}
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
