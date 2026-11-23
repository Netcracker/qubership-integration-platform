package workcontext

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
)

type Selection struct {
	AttachmentsAsFiles bool     `json:"attachments_as_files,omitempty"`
	Instructions       []string `json:"instructions"`
	Attachments        []string `json:"attachments"`
}

type File struct {
	ReferenceOnly bool   `json:"reference_only,omitempty"`
	Root          string `json:"root"`
	Path          string `json:"path"`
	SHA256        string `json:"sha256"`
}

type Packet struct {
	Kind   string `json:"kind,omitempty"`
	Text   string `json:"text"`
	Files  []File `json:"files"`
	SHA256 string `json:"sha256"`
}

func digest(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func read(root, name string) ([]byte, error) {
	if name == "" || filepath.IsAbs(name) || filepath.Clean(name) != name || name == "." || name == ".." || strings.HasPrefix(name, ".."+string(filepath.Separator)) {
		return nil, fmt.Errorf("invalid context path %q", name)
	}
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		return nil, err
	}
	path, err := filepath.EvalSymlinks(filepath.Join(resolvedRoot, name))
	if err != nil {
		return nil, fmt.Errorf("read context %s: %w", name, err)
	}
	rel, err := filepath.Rel(resolvedRoot, path)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return nil, fmt.Errorf("context path %q escapes its root", name)
	}
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("context path %q is not a regular file", name)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read context %s: %w", name, err)
	}
	return data, nil
}

func Build(workspace, taskDir string, selection Selection) (Packet, error) {
	return BuildForKind(workspace, taskDir, selection, "")
}

func BuildForKind(workspace, taskDir string, selection Selection, kind string) (Packet, error) {
	skill, err := requiredSkill(kind)
	if err != nil {
		return Packet{}, err
	}
	files := []File{{Root: "workspace", Path: "AGENTS.md"}, {Root: "workspace", Path: skill}, {Root: "task", Path: "task.md"}}
	for _, path := range selection.Instructions {
		files = append(files, File{Root: "workspace", Path: path})
	}
	for _, path := range selection.Attachments {
		files = append(files, File{Root: "task", Path: path, ReferenceOnly: selection.AttachmentsAsFiles})
	}
	return build(workspace, taskDir, files, kind)
}

func requiredSkill(kind string) (string, error) {
	switch kind {
	case "", "implementation":
		return ".agents/skills/fix-issue/SKILL.md", nil
	case "documentation":
		return ".agents/skills/docs-authoring/SKILL.md", nil
	default:
		return "", fmt.Errorf("unknown work kind %q", kind)
	}
}

func build(workspace, taskDir string, files []File, kind string) (Packet, error) {
	skill, err := requiredSkill(kind)
	if err != nil {
		return Packet{}, err
	}
	packet := Packet{Kind: kind, Files: make([]File, 0, len(files))}
	seen := map[string]bool{}
	var text strings.Builder
	for _, file := range files {
		key := file.Root + ":" + file.Path
		if seen[key] {
			return Packet{}, fmt.Errorf("duplicate context file %s", key)
		}
		seen[key] = true
		var root string
		switch file.Root {
		case "workspace":
			root = workspace
		case "task":
			root = taskDir
		default:
			return Packet{}, fmt.Errorf("unknown context root %q", file.Root)
		}
		data, err := read(root, file.Path)
		if err != nil {
			return Packet{}, err
		}
		if len(strings.TrimSpace(string(data))) == 0 {
			return Packet{}, fmt.Errorf("context file %s is empty", key)
		}
		file.SHA256 = digest(data)
		if file.ReferenceOnly && (file.Root != "task" || file.Path == "task.md") {
			return Packet{}, fmt.Errorf("instructions and task text must remain inline")
		}
		packet.Files = append(packet.Files, file)
		fmt.Fprintf(&text, "\n--- BEGIN CONTEXT %s SHA256 %s ---\n", key, file.SHA256)
		if file.ReferenceOnly {
			fmt.Fprintf(&text, "Read the complete attachment %q from the task directory. Size: %d bytes. Its full contents are bound by the SHA256 above.\n", file.Path, len(data))
		} else {
			text.Write(data)
		}
		fmt.Fprintf(&text, "\n--- END CONTEXT %s ---\n", key)
	}
	for _, required := range []string{"workspace:AGENTS.md", "workspace:" + skill, "task:task.md"} {
		if !seen[required] {
			return Packet{}, fmt.Errorf("required context file %s is missing", required)
		}
	}
	packet.Text = text.String()
	packet.SHA256 = packetDigest(packet)
	return packet, nil
}

func packetDigest(packet Packet) string {
	packet.SHA256 = ""
	data, _ := json.Marshal(packet)
	return digest(data)
}

func Verify(workspace, taskDir string, packet Packet) error {
	current, err := build(workspace, taskDir, packet.Files, packet.Kind)
	if err != nil {
		return err
	}
	if !reflect.DeepEqual(current, packet) {
		return fmt.Errorf("context packet or its source files changed")
	}
	return nil
}

func ReadBoundFile(root string, file File) ([]byte, error) {
	data, err := read(root, file.Path)
	if err != nil {
		return nil, err
	}
	if digest(data) != file.SHA256 {
		return nil, fmt.Errorf("context file %q changed after preparation", file.Path)
	}
	return data, nil
}

func VerifyTaskFiles(taskDir string, packet Packet) error {
	for _, file := range packet.Files {
		if file.Root == "task" {
			if _, err := ReadBoundFile(taskDir, file); err != nil {
				return err
			}
		}
	}
	return nil
}
