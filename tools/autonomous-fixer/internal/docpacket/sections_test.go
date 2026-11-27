package docpacket

import (
	"slices"
	"strings"
	"testing"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/doctriage"
)

func TestHelpSectionsUseCurrentMappedOrChangedPages(t *testing.T) {
	const path = "help/docs/03__Admin_Tools/3__Audit/readme.md"
	const section = "## Entity Name\nClick the entity name to open it.\n\n```sh\n# A shell comment\n```\n"
	page := doctriage.Document{Path: path, Content: "# Audit\n\n" + section + "## Fields\nUnrelated field.\n"}
	issue := Issue{Title: "Audit table", Body: "Open the entity name"}
	if got := selectHelpSections(issue, []string{"ui/src/components/admin_tools/ActionsLog.tsx"}, nil, []doctriage.Document{page}); len(got) == 0 || got[0].Content != section {
		t.Fatalf("expected the exact entity section, got %v", got)
	}
	if got := selectHelpSections(Issue{Title: "Typography"}, []string{"ui/src/components/admin_tools/ActionsLog.tsx"}, nil, []doctriage.Document{page}); len(got) != 0 {
		t.Fatal("an incidental screen match displaced the full-page fallback")
	}
	renamed := []DocMapping{{ChangedPath: "help/docs/old.md", CurrentPath: path}}
	if got := selectHelpSections(Issue{Title: "Rename", Body: "entity name"}, []string{"help/docs/old.md"}, renamed, []doctriage.Document{page}); len(got) == 0 || got[0].Path != path {
		t.Fatal("resolved help page missing from excerpts", got)
	}
	if !slices.Contains(markdownSections(page.Content), section) || strings.Join(markdownSections(page.Content), "") != page.Content {
		t.Fatal("Markdown section boundaries changed the quoted text")
	}
}

func TestServerChangeFindsHelpByContent(t *testing.T) {
	pages := []doctriage.Document{
		{Path: "help/docs/platform/readme.md", Content: "# Platform logging\nAdjust the logging configuration."},
		{Path: "help/docs/chains/readme.md", Content: "# Session settings\nThe engine uses the Consul level for the first session. TraceMe enables DEBUG."},
	}
	sections := selectHelpSections(Issue{Title: "Missing first session", Body: "TraceMe enables DEBUG before Consul delivers the logging level."}, []string{"engine/ConsulService.java"}, nil, pages)
	if len(sections) == 0 || sections[0].Path != pages[1].Path {
		t.Fatalf("missing session logging contract: %v", sections)
	}
}
