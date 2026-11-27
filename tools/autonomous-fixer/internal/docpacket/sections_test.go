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
	if got := selectHelpSections(issue, []string{"ui/src/components/admin_tools/ActionsLog.tsx"}, []doctriage.Document{page}); len(got) == 0 || got[0].Content != section {
		t.Fatalf("expected the exact entity section, got %v", got)
	}
	if got := selectHelpSections(Issue{Title: "Typography"}, []string{"ui/src/components/admin_tools/ActionsLog.tsx"}, []doctriage.Document{page}); len(got) != 0 {
		t.Fatal("an incidental screen match displaced the full-page fallback")
	}
	if !slices.Contains(markdownSections(page.Content), section) || strings.Join(markdownSections(page.Content), "") != page.Content {
		t.Fatal("Markdown section boundaries changed the quoted text")
	}
}
