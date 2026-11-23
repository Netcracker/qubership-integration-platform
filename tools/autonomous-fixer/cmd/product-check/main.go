package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/checkrunner"
)

type names []string

func (n *names) String() string         { return fmt.Sprint([]string(*n)) }
func (n *names) Set(value string) error { *n = append(*n, value); return nil }
func run(ctx context.Context, args []string) error {
	f := flag.NewFlagSet("product-check", flag.ContinueOnError)
	cwd := f.String("cwd", "", "absolute test working directory")
	output := f.String("output", "", "new absolute evidence directory")
	report := f.String("report", "", "new absolute report path; default output/test-report.json")
	format := f.String("report-format", "", "playwright or jest")
	expect := f.String("expect", "pass", "pass or tests-fail")
	min := f.Int("min-tests", 1, "minimum executed tests")
	shell := f.String("shell", "", "explicit Bash script, executed with pipefail; mutually exclusive with command argv")
	var healthy, failed names
	f.Var(&healthy, "require-test", "exact unique title/full name of a healthy test; repeat as needed")
	f.Var(&failed, "expect-failed-test", "exact unique title/full name of an expected assertion failure; repeat as needed")
	if e := f.Parse(args); e != nil {
		return e
	}
	command := f.Args()
	if *shell != "" {
		if len(command) > 0 {
			return fmt.Errorf("shell and command argv are mutually exclusive")
		}
		command = checkrunner.BashProductCommand(*shell)
	}
	record, e := checkrunner.RunProduct(ctx, checkrunner.ProductOptions{CWD: *cwd, OutputDirectory: *output, Command: command, ReportPath: *report, ReportFormat: *format, Expect: *expect, MinTests: *min, RequiredTests: healthy, ExpectedFailedTests: failed}, os.Environ())
	b, encodeErr := checkrunner.ProductResultJSON(record)
	if encodeErr != nil {
		return encodeErr
	}
	fmt.Println(string(b))
	return e
}
func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if e := run(ctx, os.Args[1:]); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}
