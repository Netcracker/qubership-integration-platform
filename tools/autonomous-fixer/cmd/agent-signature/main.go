// Command agent-signature prints the GitHub footer for an archived run.
package main

import (
	"fmt"
	"os"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/agentsignature"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "Usage: agent-signature RUN_DIRECTORY")
		os.Exit(2)
	}
	signature, err := agentsignature.ReadRun(os.Args[1])
	if err == nil {
		err = signature.Validate()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	line := signature.Line()
	if line == "" {
		fmt.Fprintln(os.Stderr, "The archive has no model identity for the run result.")
		os.Exit(1)
	}
	fmt.Println(line)
}
