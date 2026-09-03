package model

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
)

// TestingContextHeader names the header that carries a base64-encoded
// TestingContext. Both the header name and the field names below are part of
// the contract with the engine and must not be renamed.
const TestingContextHeader = "Testing-Service-Context"

type TestingContext struct {
	ChainID       string `json:"chainId"`
	ElementID     string `json:"elementId"`
	OperationPath string `json:"operationPath"`
	Path          string `json:"path"`
}

// DecodeTestingContext reads the value of TestingContextHeader.
func DecodeTestingContext(encoded string) (*TestingContext, error) {
	data, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, fmt.Errorf("decode %s header: %w", TestingContextHeader, err)
	}
	var testingContext TestingContext
	if err := json.Unmarshal(data, &testingContext); err != nil {
		return nil, fmt.Errorf("parse %s header: %w", TestingContextHeader, err)
	}
	return &testingContext, nil
}
