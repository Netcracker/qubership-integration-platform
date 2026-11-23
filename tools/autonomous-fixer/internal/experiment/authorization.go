package experiment

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

func CheckContinuation(root string) error {
	data, err := os.ReadFile(filepath.Join(root, "queue-policy.json"))
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	var policy struct {
		Status       string `json:"status"`
		Continuation *bool  `json:"continuation_authorized"`
	}
	if err := json.Unmarshal(data, &policy); err != nil {
		return fmt.Errorf("read campaign authorization: %w", err)
	}
	if (policy.Continuation != nil && !*policy.Continuation) || strings.Contains(policy.Status, "cancelled") {
		return fmt.Errorf("remaining campaign executions were cancelled; no model request is authorized")
	}
	return nil
}
