package model

import (
	"encoding/base64"
	"encoding/json"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// goldenTestingContext and goldenTestingContextHeader are one encoding of the
// same value. The engine tests in plan 2 encode against this literal, so a
// change here means the engine and this service no longer agree.
var goldenTestingContext = TestingContext{
	ChainID:       "chain-1",
	ElementID:     "element-1",
	OperationPath: "/orders/{orderId}",
	Path:          "/orders/42",
}

const goldenTestingContextHeader = "eyJjaGFpbklkIjoiY2hhaW4tMSIsImVsZW1lbnRJZCI6ImVsZW1lbnQtMSIsIm9wZXJhdGlvblBhdGgiOiIvb3JkZXJzL3tvcmRlcklkfSIsInBhdGgiOiIvb3JkZXJzLzQyIn0="

func TestDecodeTestingContextReadsTheGoldenHeader(t *testing.T) {
	got, err := DecodeTestingContext(goldenTestingContextHeader)

	require.NoError(t, err)
	assert.Equal(t, &goldenTestingContext, got)
}

func TestTestingContextEncodesToTheGoldenHeader(t *testing.T) {
	data, err := json.Marshal(goldenTestingContext)
	require.NoError(t, err)

	assert.Equal(t, goldenTestingContextHeader, base64.StdEncoding.EncodeToString(data))
}

func TestTestingContextHeaderNameIsFixed(t *testing.T) {
	assert.Equal(t, "Testing-Service-Context", TestingContextHeader)
}

func TestDecodeTestingContextRoundTrips(t *testing.T) {
	cases := map[string]TestingContext{
		"empty":               {},
		"path only":           {Path: "/orders"},
		"non-ascii path":      {Path: "/заказы/42"},
		"template characters": {OperationPath: "/a/{b}/c/{d}", Path: "/a/1/c/2"},
	}

	for name, want := range cases {
		t.Run(name, func(t *testing.T) {
			data, err := json.Marshal(want)
			require.NoError(t, err)

			got, err := DecodeTestingContext(base64.StdEncoding.EncodeToString(data))

			require.NoError(t, err)
			assert.Equal(t, &want, got)
		})
	}
}

func TestDecodeTestingContextIgnoresUnknownFields(t *testing.T) {
	encoded := base64.StdEncoding.EncodeToString([]byte(`{"chainId":"chain-1","extra":true}`))

	got, err := DecodeTestingContext(encoded)

	require.NoError(t, err)
	assert.Equal(t, &TestingContext{ChainID: "chain-1"}, got)
}

func TestDecodeTestingContextRejectsMalformedBase64(t *testing.T) {
	for name, encoded := range map[string]string{
		"illegal character": "not base64!",
		"wrong padding":     "eyJjaGFpbklkIjoi",
		"plain json":        `{"chainId":"chain-1"}`,
	} {
		t.Run(name, func(t *testing.T) {
			got, err := DecodeTestingContext(encoded)

			require.Error(t, err)
			assert.Nil(t, got)
			assert.Contains(t, err.Error(), TestingContextHeader)
		})
	}
}

func TestDecodeTestingContextRejectsMalformedJSON(t *testing.T) {
	for name, decoded := range map[string]string{
		"empty":         "",
		"truncated":     `{"chainId":`,
		"not an object": `["chain-1"]`,
		"wrong type":    `{"chainId":42}`,
	} {
		t.Run(name, func(t *testing.T) {
			got, err := DecodeTestingContext(base64.StdEncoding.EncodeToString([]byte(decoded)))

			require.Error(t, err)
			assert.Nil(t, got)
			assert.Contains(t, err.Error(), TestingContextHeader)
		})
	}
}
