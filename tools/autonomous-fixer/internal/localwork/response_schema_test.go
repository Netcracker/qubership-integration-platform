package localwork

import (
	"testing"
)

func TestEngineeringResponseSchemaClosesEveryObject(t *testing.T) {
	var visit func(any)
	visit = func(v any) {
		switch x := v.(type) {
		case map[string]any:
			if x["type"] == "object" {
				p := x["properties"].(map[string]any)
				required := x["required"].([]string)
				if x["additionalProperties"] != false || len(p) != len(required) {
					t.Fatal(x)
				}
				for _, key := range required {
					if _, ok := p[key]; !ok {
						t.Fatal(key)
					}
				}
			}
			for _, child := range x {
				visit(child)
			}
		case []any:
			for _, child := range x {
				visit(child)
			}
		}
	}
	visit(engineeringResponseSchema())
	contract := engineeringResponseSchema()["properties"].(map[string]any)["contract"].(map[string]any)["anyOf"].([]any)[0].(map[string]any)["properties"].(map[string]any)
	if _, ok := contract["defect"]; ok {
		t.Fatal("unrecognized contract field allowed")
	}
	if contract["risks"].(map[string]any)["type"] != "array" {
		t.Fatal("risks are not an array")
	}
}
