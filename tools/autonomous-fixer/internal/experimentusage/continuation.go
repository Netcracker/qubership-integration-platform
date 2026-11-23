package experimentusage

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"sort"
	"strconv"

	"github.com/Netcracker/qubership-integration-platform/tools/autonomous-fixer/internal/experimentcost"
)

type SessionLink struct {
	SessionID       string `json:"session_id"`
	Continuation    bool   `json:"continuation"`
	PreviousArchive string `json:"previous_archive"`
}

var authorArchive = regexp.MustCompile(`^step-([0-9]+)-author$`)

func readSessionLink(dir string) (*SessionLink, error) {
	raw, err := os.ReadFile(filepath.Join(dir, "session.json"))
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var flag struct {
		Continuation bool `json:"continuation"`
	}
	if err := json.Unmarshal(raw, &flag); err != nil {
		return nil, fmt.Errorf("decode session metadata: %w", err)
	}
	if !flag.Continuation {
		return nil, nil
	}
	var link SessionLink
	if err := json.Unmarshal(raw, &link); err != nil {
		return nil, fmt.Errorf("decode continuation metadata: %w", err)
	}
	return &link, nil
}

func previousArchive(dir string, link *SessionLink) (string, error) {
	current, err := filepath.EvalSymlinks(dir)
	if err != nil {
		return "", err
	}
	current, err = filepath.Abs(current)
	if err != nil {
		return "", err
	}
	if link.SessionID == "" || !filepath.IsAbs(link.PreviousArchive) {
		return "", fmt.Errorf("continuation requires a session ID and an absolute previous archive")
	}
	first := ""
	for {
		prior, err := filepath.EvalSymlinks(link.PreviousArchive)
		if err != nil {
			return "", fmt.Errorf("open previous archive: %w", err)
		}
		currentMatch, priorMatch := authorArchive.FindStringSubmatch(filepath.Base(current)), authorArchive.FindStringSubmatch(filepath.Base(prior))
		if filepath.Dir(current) != filepath.Dir(prior) || currentMatch == nil || priorMatch == nil {
			return "", fmt.Errorf("previous archive must be an earlier author step in the same run")
		}
		currentNumber, currentErr := strconv.ParseUint(currentMatch[1], 10, 64)
		priorNumber, priorErr := strconv.ParseUint(priorMatch[1], 10, 64)
		if currentErr != nil || priorErr != nil || priorNumber >= currentNumber {
			return "", fmt.Errorf("previous archive must precede the continued author step")
		}
		if first == "" {
			first = prior
		}
		next, err := readSessionLink(prior)
		if err != nil {
			return "", fmt.Errorf("read previous continuation: %w", err)
		}
		if next == nil {
			return first, nil
		}
		if next.SessionID != link.SessionID || !filepath.IsAbs(next.PreviousArchive) {
			return "", fmt.Errorf("previous continuation has a different session ID or an invalid archive path")
		}
		current, link = prior, next
	}
}

func continuedUsage(runtime, expected, dir string, current Report) (Report, error) {
	link, err := readSessionLink(dir)
	if err == nil && link == nil {
		return current, nil
	}
	r := report(current.Source)
	r.Session = link
	for _, warning := range current.Warnings {
		r.warn(warning)
	}
	unknown := func(reason string) (Report, error) {
		r.Tokens = experimentcost.Usage{}
		r.warn(reason)
		return r, nil
	}
	if err != nil {
		return unknown(err.Error())
	}
	priorDir, err := previousArchive(dir, link)
	if err != nil {
		return unknown(err.Error())
	}
	prior, err := readNative(runtime, expected, priorDir)
	if err != nil {
		return unknown("read previous native usage: " + err.Error())
	}
	for _, warning := range prior.Warnings {
		r.warn("previous archive: " + warning)
	}
	if _, ok := current.nativeSessions[link.SessionID]; !ok {
		return unknown("continued native session does not match session_id")
	}
	if _, ok := prior.nativeSessions[link.SessionID]; !ok {
		return unknown("previous archive has no matching native session")
	}
	for id, before := range prior.nativeSessions {
		after, ok := current.nativeSessions[id]
		if !ok || len(after) < len(before) || !reflect.DeepEqual(before, after[:len(before)]) {
			return unknown("session " + id + ": previous requests are not an exact prefix of the continued native history")
		}
	}
	r.Tokens = zero()
	ids := make([]string, 0, len(current.nativeSessions))
	for id := range current.nativeSessions {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	observed := zero()
	for _, id := range ids {
		requests := current.nativeSessions[id][len(prior.nativeSessions[id]):]
		if id == link.SessionID || len(requests) > 0 {
			r.SessionCount++
		}
		before := zero()
		if tokens, ok := prior.nativeTotals[id]; ok {
			before = tokens
		}
		delta, valid := difference(current.nativeTotals[id], before)
		if !valid {
			r.warn("session " + id + ": native totals regress or have unknown token counts")
		}
		r.Tokens = sum(r.Tokens, delta)
		for _, request := range requests {
			r.addRequest(request.Model, request.Usage)
			observed = sum(observed, request.Usage)
			if expected != "" && request.Model != expected {
				r.warn("session " + id + ": request model differs from the expected model")
			}
		}
	}
	remainder, valid := difference(r.Tokens, observed)
	if !valid || nonzero(remainder) {
		r.UnattributedUsage = &remainder
		r.warn("continued native totals cannot be fully attributed to new requests")
	}
	if r.RequestCount == 0 {
		r.warn("continued native session contains no new request usage")
	}
	r.finish()
	return r, nil
}
