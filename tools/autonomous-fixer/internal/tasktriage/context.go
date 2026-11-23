package tasktriage

import (
	"encoding/json"
	"sort"
	"strings"
	"unicode/utf8"
)

// ConsumerHints records known shared consumers as inspection leads, not edit requirements.
func KnownConsumers(paths []string) []ConsumerHint {
	var ui []string
	for _, path := range paths {
		if strings.HasPrefix(path, "ui/src/") && !testPath(path) {
			ui = appendUnique(ui, path)
		}
	}
	if len(ui) == 0 {
		return nil
	}
	sort.Strings(ui)
	return []ConsumerHint{{Consumer: "vscode-webview", Module: "vscode-extension", Reason: "The VS Code webview embeds the UI library. Check isVsCode branches, available routes, and API implementations for changed shared UI behavior; this does not require editing the extension.", Paths: ui}}
}

func consumerCovered(contract []byte, consumer string) bool {
	// Contract structure is decoded separately from prose to avoid matching a name in a risk description.
	var c struct {
		Scenarios []struct {
			Consumer string `json:"consumer"`
		} `json:"scenarios"`
	}
	if json.Unmarshal(contract, &c) != nil {
		return false
	}
	for _, s := range c.Scenarios {
		if s.Consumer == consumer || consumer == "vscode-webview" && (s.Consumer == "extension" || s.Consumer == "vscode-extension" || s.Consumer == "webview") {
			return true
		}
	}
	return false
}

func productionPath(path string) bool {
	lower := strings.ToLower(path)
	return !testPath(lower) && !strings.HasPrefix(lower, "help/") && !strings.HasSuffix(lower, ".md")
}

// ReviewDiff shares the budget across production files before tests and records omitted bytes.
func ReviewDiff(files map[string]string, budget int) (string, []DiffCoverage) {
	paths := make([]string, 0, len(files))
	for path := range files {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	var production, other []string
	for _, path := range paths {
		if productionPath(path) {
			production = append(production, path)
		} else {
			other = append(other, path)
		}
	}
	selected := map[string]string{}
	included := map[string]int{}
	remaining := budget
	for _, group := range [][]string{production, other} {
		// Small files release their unused allocation to the remaining files.
		pending := append([]string(nil), group...)
		for len(pending) > 0 && remaining > 0 {
			quota := remaining / len(pending)
			if quota < 128 {
				break
			}
			next := []string{}
			for _, path := range pending {
				header := "\nFile: " + path + "\n"
				if len(header)+len(files[path]) <= quota {
					selected[path] = header + files[path]
					included[path] = len(files[path])
					remaining -= len(selected[path])
				} else {
					next = append(next, path)
				}
			}
			if len(next) == len(pending) {
				for _, path := range next {
					header := "\nFile: " + path + "\n"
					if len(header) >= quota {
						continue
					}
					text, count := diffExcerpt(files[path], quota-len(header))
					selected[path] = header + text
					included[path] = count
					remaining -= len(selected[path])
				}
				break
			}
			pending = next
		}
	}
	var out strings.Builder
	coverage := make([]DiffCoverage, 0, len(paths))
	for _, path := range append(production, other...) {
		out.WriteString(selected[path])
		coverage = append(coverage, DiffCoverage{Path: path, IncludedBytes: included[path], TotalBytes: len(files[path])})
	}
	return out.String(), coverage
}

func diffExcerpt(diff string, budget int) (string, int) {
	if budget <= 0 {
		return "", 0
	}
	if len(diff) <= budget {
		return diff, len(diff)
	}
	const marker = "\n[omitted diff bytes]\n"
	parts := strings.Split(diff, "\n@@")
	if len(parts) == 1 {
		s := prefixUTF8(diff, max(0, budget-len(marker)))
		return s + prefixUTF8(marker, budget-len(s)), len(s)
	}
	header := prefixUTF8(parts[0], min(len(parts[0]), budget/4))
	hunks := make([]string, len(parts)-1)
	quotas := make([]int, len(hunks))
	pending := []int{}
	for i, part := range parts[1:] {
		hunks[i] = "\n@@" + part
		pending = append(pending, i)
	}
	left := max(0, budget-len(header)-len(marker)*len(hunks))
	for len(pending) > 0 {
		quota := left / len(pending)
		next := []int{}
		for _, i := range pending {
			if len(hunks[i]) <= quota {
				quotas[i] = len(hunks[i])
				left -= quotas[i]
			} else {
				next = append(next, i)
			}
		}
		if len(next) == len(pending) {
			for _, i := range next {
				quotas[i] = quota
			}
			break
		}
		pending = next
	}
	out, count := header, len(header)
	for i, piece := range hunks {
		excerpt := prefixUTF8(piece, quotas[i])
		out += excerpt
		count += len(excerpt)
		if len(excerpt) < len(piece) {
			out += prefixUTF8(marker, max(0, budget-len(out)))
		}
	}
	return out, count
}
func prefixUTF8(s string, n int) string {
	if n >= len(s) {
		return s
	}
	if n <= 0 {
		return ""
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
}

func testPath(path string) bool {
	lower := strings.ToLower(path)
	if strings.HasPrefix(lower, "e2e/") || strings.Contains(lower, ".test.") || strings.Contains(lower, ".spec.") {
		return true
	}
	for _, part := range strings.Split(lower, "/") {
		if part == "test" || part == "tests" || part == "__tests__" || part == "testdata" || part == "fixtures" {
			return true
		}
	}
	return false
}
