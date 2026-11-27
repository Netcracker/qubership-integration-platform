package docpacket

import (
	"slices"
	"sort"
	"strings"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
)

// selectHelpSections searches pages tied to changed screens or edited by the implementing PR.
func selectHelpSections(issue Issue, paths []string, pages []doctriage.Document) []doctriage.Document {
	relevant := make(map[string]bool)
	for _, mapping := range autoDocumentationCandidates(paths) {
		relevant[mapping.CurrentPath] = true
	}
	for _, path := range paths {
		if strings.HasPrefix(path, "help/docs/") {
			relevant[path] = true
		}
	}
	type passage struct {
		doc   doctriage.Document
		score int
	}
	title, body := terms(issue.Title), terms(issue.Body)
	var matches []passage
	for _, page := range pages {
		if !relevant[page.Path] {
			continue
		}
		pathWords := terms(page.Path)
		named := false
		for _, term := range title {
			if slices.Contains(pathWords, term) {
				named = true
				break
			}
		}
		if !named && !slices.Contains(paths, page.Path) {
			continue
		}
		for _, text := range markdownSections(page.Content) {
			heading, sectionBody, _ := strings.Cut(text, "\n")
			if len(terms(sectionBody)) == 0 {
				continue
			}
			heading = strings.ToLower(heading)
			lower := strings.ToLower(text)
			score := 0
			for _, term := range title {
				if strings.Contains(lower, term) {
					score += 2
				}
				if strings.Contains(heading, term) {
					score += 4
				}
				if strings.Contains(strings.ToLower(page.Path), term) {
					score++
				}
			}
			for _, term := range body {
				if strings.Contains(lower, term) {
					score++
				}
			}
			if score > 0 && len(text) <= classifierSectionBytes {
				matches = append(matches, passage{doctriage.Document{Path: page.Path, Content: text}, score})
			}
		}
	}
	sort.SliceStable(matches, func(i, j int) bool { return matches[i].score > matches[j].score })
	var selected []doctriage.Document
	for _, m := range matches[:min(3, len(matches))] {
		selected = append(selected, m.doc)
	}
	return selected
}

const classifierSectionBytes = 16 * 1024

func markdownSections(content string) []string {
	var sections []string
	start, offset := 0, 0
	var fence string
	for line := range strings.SplitAfterSeq(content, "\n") {
		trimmed := strings.TrimSpace(line)
		if fence != "" {
			if strings.HasPrefix(trimmed, fence) && strings.Trim(trimmed, fence[:1]) == "" {
				fence = ""
			}
		} else if strings.HasPrefix(trimmed, "```") || strings.HasPrefix(trimmed, "~~~") {
			fence = trimmed[:len(trimmed)-len(strings.TrimLeft(trimmed, trimmed[:1]))]
		} else {
			rest := strings.TrimLeft(line, "#")
			level := len(line) - len(rest)
			if level >= 1 && level <= 6 && strings.HasPrefix(rest, " ") && offset > start {
				sections = append(sections, content[start:offset])
				start = offset
			}
		}
		offset += len(line)
	}
	return append(sections, content[start:])
}
