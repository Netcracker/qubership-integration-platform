package localwork

func engineeringResponseSchema() map[string]any {
	text := map[string]any{"type": "string"}
	array := func(item any) map[string]any { return map[string]any{"type": "array", "items": item} }
	object := func(properties map[string]any) map[string]any {
		required := []string{}
		for _, key := range []string{"candidate_id", "contract", "findings", "question", "scenarios", "checks", "environment_id", "data_id", "evidence", "id", "criterion_id", "consumer", "behavior", "procedure", "baseline", "scope", "risks", "location", "scenario_id", "description", "kind", "text", "needed", "outcome", "observation_ids", "product_tests", "compatibility", "scenario", "baseline_result", "candidate_result", "handoff", "summary", "commands", "deployment", "recipes", "purpose", "argv", "working_directory", "environment_keys"} {
			if _, ok := properties[key]; ok {
				required = append(required, key)
			}
		}
		return map[string]any{"type": "object", "properties": properties, "required": required, "additionalProperties": false}
	}
	nullable := func(schema any) map[string]any {
		return map[string]any{"anyOf": []any{schema, map[string]any{"type": "null"}}}
	}
	enum := func(values ...string) map[string]any { return map[string]any{"type": "string", "enum": values} }
	evidence := array(text)
	scenario := object(map[string]any{"id": text, "criterion_id": text, "consumer": text, "behavior": text, "procedure": text, "baseline": enum("pass", "fail")})
	check := object(map[string]any{"id": text, "scope": text})
	result := object(map[string]any{"id": text, "outcome": enum("pass", "fail", "measured"), "evidence": evidence, "observation_ids": array(text)})
	contract := object(map[string]any{"scenarios": array(scenario), "checks": array(check), "risks": array(enum("compatibility", "data", "security", "concurrency"))})
	compatibility := object(map[string]any{"scenario": scenario, "baseline_result": result, "candidate_result": result})
	finding := object(map[string]any{"id": text, "location": text, "scenario_id": text, "description": text, "evidence": evidence, "compatibility": nullable(compatibility)})
	question := object(map[string]any{"kind": enum("needs-human", "verification-blocked"), "text": text, "needed": text, "evidence": evidence})
	recipe := object(map[string]any{"purpose": text, "argv": array(text), "working_directory": text, "environment_keys": array(text)})
	handoff := object(map[string]any{"summary": text, "commands": array(text), "recipes": array(recipe), "deployment": text, "evidence": evidence})
	return object(map[string]any{"candidate_id": text, "contract": nullable(contract), "findings": array(finding), "question": nullable(question), "scenarios": array(result), "checks": array(result), "environment_id": text, "data_id": text, "evidence": evidence, "handoff": nullable(handoff)})
}
