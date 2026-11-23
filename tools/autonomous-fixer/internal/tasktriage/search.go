package tasktriage

import (
	"path/filepath"
	"regexp"
	"strings"
)

var searchDirective = regexp.MustCompile(`(?i)^\s*((attention|system|instruction)\b.*\b(classifier|model|assistant)\b|(ignore|disregard|override)\b.*\b(instructions|classification|the problem)\b|(classifier|model|assistant)\s*[:>,].*\b(always|choose|ignore)\b)`)

var identifierPattern = regexp.MustCompile(`[A-Za-z][A-Za-z0-9]*[-_][A-Za-z0-9_-]{2,}|[A-Za-z][a-z]+(?:[A-Z][A-Za-z0-9]+)+`)
var camelBoundary = regexp.MustCompile(`([a-z0-9])([A-Z])`)
var searchNoise = map[string]bool{"end-to-end": true, "read-only": true, "cross-module": true, "third-party": true, "built-in": true, "before-and-after": true, "github": true, "openapi": true, "asyncapi": true, "trial-instructions": true, "qip-wt-autonomous-fixer": true}

// Filtering selects classification evidence; the report retains the original task.
func searchEvidence(task string) (string, []int) {
	lines := strings.Split(task, "\n")
	ignored := []int{}
	for i, line := range lines {
		if searchDirective.MatchString(line) {
			lines[i] = ""
			ignored = append(ignored, i+1)
		}
	}
	return strings.Join(lines, "\n"), ignored
}
func searchIdentifiers(task string) []string {
	terms := []string{}
	pathPattern := regexp.MustCompile(`(?:[A-Za-z0-9_.-]+/)+([A-Za-z][A-Za-z0-9_-]*)\.(?:java|tsx|ts|go|py|jsx|js)\b`)
	for _, match := range pathPattern.FindAllStringSubmatch(task, -1) {
		if !strings.Contains(match[0], "http") && match[1] != "index" {
			terms = appendUnique(terms, match[1])
			if strings.HasSuffix(match[1], "Test") {
				terms = appendUnique(terms, strings.TrimSuffix(match[1], "Test"))
			}
		}
		if len(terms) >= 8 {
			return terms[:8]
		}
	}
	task = regexp.MustCompile(`https?://[^\s)>]+`).ReplaceAllString(task, "")
	for _, term := range identifierPattern.FindAllString(task, -1) {
		if _, module := Modules[term]; module || searchNoise[strings.ToLower(term)] {
			continue
		}
		terms = appendUnique(terms, term)
		if len(terms) == 8 {
			break
		}
	}
	return terms
}
func identifierExpression(term string) string {
	parts := strings.FieldsFunc(camelBoundary.ReplaceAllString(term, "${1}_${2}"), func(r rune) bool { return r == '_' || r == '-' })
	for i := range parts {
		parts[i] = regexp.QuoteMeta(parts[i])
	}
	return strings.Join(parts, `[_-]?`)
}
func relevance(task, path, line string) int {
	score := 0
	name := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
	for _, term := range searchIdentifiers(task) {
		if strings.EqualFold(name, term) {
			score += 12
		}
	}
	if strings.HasSuffix(path, "pom.xml") || strings.Contains(line, "http://") || strings.Contains(line, "https://") {
		score -= 12
	}
	if strings.Contains(strings.ToLower(line), "log.") || strings.Contains(strings.ToLower(line), "logger.") {
		score -= 4
	}
	if strings.HasPrefix(path, "ui/src/components/ai/") && !regexp.MustCompile(`(?i)\b(ai|assistant|chat)\b`).MatchString(task) {
		score -= 6
	}
	if strings.Contains(path, "/src/main/") || strings.Contains(path, "/internal/") || strings.HasPrefix(path, "ui/src/") || strings.HasPrefix(path, "vscode-extension/src/") {
		score += 4
	}
	if testPath(path) {
		score -= 4
	}
	if strings.HasPrefix(path, "help/") || strings.HasSuffix(path, ".md") {
		score -= 2
	}
	lower := strings.ToLower(line)
	if strings.Contains(lower, "import ") {
		score -= 3
	}
	if strings.Contains(lower, "enum ") || strings.Contains(lower, "class ") || strings.Contains(lower, "func ") || strings.Contains(lower, "function ") || strings.Contains(lower, "const ") && strings.Contains(lower, "=>") {
		score += 3
	}
	task = strings.ToLower(task)
	for _, stem := range []string{"delet", "remov", "sav", "updat", "import", "export", "log", "deploy", "validat", "retry"} {
		if strings.Contains(task, stem) && (strings.Contains(lower, stem) || strings.Contains(strings.ToLower(path), stem)) {
			score += 2
		}
	}
	return score
}
