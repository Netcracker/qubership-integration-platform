package doctriage

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"strings"
)

const AuditVersion = "docs-audit-v1"

type AuditIdentity struct {
	Repository            string `json:"repository"`
	Issue                 int    `json:"issue"`
	BaseSHA               string `json:"base_sha"`
	ImplementationHeadSHA string `json:"implementation_head_sha"`
	SourceSHA             string `json:"source_sha"`
	DocsSHA               string `json:"docs_sha"`
}

type AuditSample struct {
	Identity     AuditIdentity `json:"identity"`
	RevisionID   string        `json:"revision_id"`
	SampleSHA256 string        `json:"sample_sha256"`
	Score        float64       `json:"score"`
	Selected     bool          `json:"selected"`
}

// SampleAudit selects a stable subset without changing documentation routing.
func SampleAudit(identity AuditIdentity, seed string, fraction float64) (AuditSample, error) {
	if strings.TrimSpace(seed) == "" || math.IsNaN(fraction) || math.IsInf(fraction, 0) || fraction < 0 || fraction > 1 {
		return AuditSample{}, fmt.Errorf("audit sampling requires a seed and a finite fraction from 0 to 1")
	}
	if strings.TrimSpace(identity.Repository) == "" || identity.Issue <= 0 || identity.BaseSHA == "" || identity.ImplementationHeadSHA == "" || identity.SourceSHA == "" || identity.DocsSHA == "" {
		return AuditSample{}, fmt.Errorf("audit sampling requires repository, issue, and source, implementation, and documentation revisions")
	}
	identity.Repository = strings.ToLower(identity.Repository)
	identity.BaseSHA = strings.ToLower(identity.BaseSHA)
	identity.ImplementationHeadSHA = strings.ToLower(identity.ImplementationHeadSHA)
	identity.SourceSHA = strings.ToLower(identity.SourceSHA)
	identity.DocsSHA = strings.ToLower(identity.DocsSHA)
	encoded, _ := json.Marshal(identity)
	revision := sha256.Sum256(encoded)
	input, _ := json.Marshal(struct {
		Version  string        `json:"version"`
		Seed     string        `json:"seed"`
		Identity AuditIdentity `json:"identity"`
	}{AuditVersion, seed, identity})
	digest := sha256.Sum256(input)
	score := float64(binary.BigEndian.Uint64(digest[:8])>>11) / float64(uint64(1)<<53)
	return AuditSample{Identity: identity, RevisionID: hex.EncodeToString(revision[:]), SampleSHA256: hex.EncodeToString(digest[:]), Score: score, Selected: score < fraction}, nil
}
