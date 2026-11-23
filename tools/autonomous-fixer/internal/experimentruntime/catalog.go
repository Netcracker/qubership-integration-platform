package experimentruntime

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
)

type CatalogSelection struct {
	Path             string `json:"path"`
	SHA256           string `json:"sha256"`
	Model            string `json:"model"`
	ContextWindow    int    `json:"context_window"`
	EffectivePercent *int   `json:"effective_percent"`
}

func PinCatalog(command *Command, profile Profile, source, home string) (CatalogSelection, error) {
	var selection CatalogSelection
	if profile.Runtime != "codex" {
		return selection, fmt.Errorf("catalog pinning requires Codex")
	}
	if !filepath.IsAbs(source) {
		return selection, fmt.Errorf("Codex requires an explicit model catalog path")
	}
	b, e := os.ReadFile(source)
	if e != nil {
		return selection, e
	}
	var catalog struct {
		Models []struct {
			Slug             string `json:"slug"`
			ContextWindow    int    `json:"context_window"`
			EffectivePercent *int   `json:"effective_context_window_percent"`
			Efforts          []struct {
				Effort string `json:"effort"`
			} `json:"supported_reasoning_levels"`
		} `json:"models"`
	}
	if e = json.Unmarshal(b, &catalog); e != nil {
		return selection, e
	}
	found := 0
	for _, m := range catalog.Models {
		if m.Slug == profile.Model {
			found++
			allowed := false
			for _, effort := range m.Efforts {
				if effort.Effort == profile.Effort {
					allowed = true
				}
			}
			if !allowed || m.ContextWindow <= 0 || (m.EffectivePercent != nil && (*m.EffectivePercent <= 0 || *m.EffectivePercent > 100)) {
				return selection, fmt.Errorf("catalog does not describe the requested model, effort, and context")
			}
			selection.Model = m.Slug
			selection.ContextWindow = m.ContextWindow
			selection.EffectivePercent = m.EffectivePercent
		}
	}
	if found != 1 {
		return selection, fmt.Errorf("catalog must contain exactly one entry for %s", profile.Model)
	}
	if len(command.Args) == 0 || command.Args[len(command.Args)-1] != "-" {
		return selection, fmt.Errorf("catalog requires a Codex stdin command")
	}
	dir := filepath.Join(home, ".codex")
	if e = os.MkdirAll(dir, 0700); e != nil {
		return selection, e
	}
	selection.Path = filepath.Join(dir, "model-catalog.json")
	if e = os.WriteFile(selection.Path, b, 0600); e != nil {
		return selection, e
	}
	hash := sha256.Sum256(b)
	selection.SHA256 = hex.EncodeToString(hash[:])
	command.Args = append(command.Args[:len(command.Args)-1], "-c", "model_catalog_json="+strconv.Quote(selection.Path), "-")
	return selection, nil
}
