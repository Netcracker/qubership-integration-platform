package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
	"os"
)

func main() {
	recipe := flag.String("recipe", "", "Check recipe JSON file")
	workspace := flag.String("workspace", "", "Workspace directory")
	candidate := flag.String("candidate", "", "Source snapshot identity")
	evidence := flag.String("evidence", "", "Evidence directory")
	flag.Parse()
	if *recipe == "" || *workspace == "" || *candidate == "" || *evidence == "" {
		fmt.Fprintln(os.Stderr, "recipe, workspace, candidate, and evidence are required")
		os.Exit(2)
	}
	b, e := os.ReadFile(*recipe)
	if e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	var r checkrunner.Recipe
	if e = json.Unmarshal(b, &r); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	result, e := checkrunner.Run(context.Background(), *workspace, *candidate, *evidence, r, os.Environ())
	if e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	json.NewEncoder(os.Stdout).Encode(result)
	if result.ExitCode != 0 {
		os.Exit(1)
	}
}
