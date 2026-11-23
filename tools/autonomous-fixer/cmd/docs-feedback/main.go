package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/docfeedback"
)

type references []string

func (r *references) String() string     { return fmt.Sprint([]string(*r)) }
func (r *references) Set(s string) error { *r = append(*r, s); return nil }
func readRecord(path string) (docfeedback.Record, error) {
	var r docfeedback.Record
	b, e := os.ReadFile(path)
	if e != nil {
		return r, e
	}
	e = json.Unmarshal(b, &r)
	if e == nil {
		e = docfeedback.Validate(r)
	}
	return r, e
}
func run(args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("usage: docs-feedback import|adjudicate|summary|audit [options]")
	}
	if args[0] == "audit" {
		return runAudit(args[1:])
	}
	if args[0] == "summary" {
		if len(args) < 2 {
			return fmt.Errorf("summary requires feedback record paths")
		}
		records := []docfeedback.Record{}
		for _, path := range args[1:] {
			r, e := readRecord(path)
			if e != nil {
				return e
			}
			records = append(records, r)
		}
		summary, e := docfeedback.Summarize(records)
		if e != nil {
			return e
		}
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(summary)
	}
	if args[0] != "import" && args[0] != "adjudicate" {
		return fmt.Errorf("unknown feedback command %q", args[0])
	}
	f := flag.NewFlagSet(args[0], flag.ContinueOnError)
	directory := f.String("run", "", "completed local documentation run")
	source := f.String("record", "", "existing feedback record to adjudicate")
	output := f.String("output", "", "new immutable JSON file")
	kind := f.String("kind", "", "explicit judgment: missed_needed_change, unnecessary_change, context_gap, editorial_disagreement, or confirmed_correct")
	actor := f.String("actor", "", "person or model that made the judgment")
	actorType := f.String("actor-type", "", "human or model; identities are caller-declared")
	basis := f.String("basis", "", "explanation of the judgment")
	at := f.String("at", "", "judgment timestamp in RFC3339 format")
	var refs references
	f.Var(&refs, "evidence", "absolute evidence file or HTTPS reference; repeat as needed")
	if e := f.Parse(args[1:]); e != nil {
		return e
	}
	if f.NArg() != 0 || *output == "" {
		return fmt.Errorf("output is required and positional arguments are not accepted")
	}
	var judgment *docfeedback.Adjudication
	if *kind != "" {
		stamp, e := time.Parse(time.RFC3339, *at)
		if e != nil {
			return fmt.Errorf("at must be an explicit RFC3339 timestamp")
		}
		judgment = &docfeedback.Adjudication{Kind: *kind, Actor: *actor, ActorType: *actorType, Basis: *basis, Timestamp: stamp, Evidence: []docfeedback.Reference{}}
		for _, ref := range refs {
			evidence, e := docfeedback.Evidence(ref)
			if e != nil {
				return e
			}
			judgment.Evidence = append(judgment.Evidence, evidence)
		}
	} else if *actor != "" || *actorType != "" || *basis != "" || *at != "" || len(refs) > 0 {
		return fmt.Errorf("judgment options require kind")
	}
	var record docfeedback.Record
	var e error
	if args[0] == "import" {
		if *directory == "" || *source != "" {
			return fmt.Errorf("import requires run and does not accept record")
		}
		record, e = docfeedback.Import(*directory, judgment)
	} else {
		if *source == "" || *directory != "" || judgment == nil {
			return fmt.Errorf("adjudicate requires record and a complete explicit judgment")
		}
		record, e = readRecord(*source)
		if e == nil {
			record, e = docfeedback.Adjudicate(record, *judgment)
		}
	}
	if e != nil {
		return e
	}
	if e = docfeedback.WriteNew(*output, record); e != nil {
		return e
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]string{"record_id": record.ID, "case_id": record.CaseID, "observation_id": record.ObservationID, "assessment_status": record.AssessmentStatus, "output": *output})
}
func main() {
	if e := run(os.Args[1:]); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}
