package experimentruntime

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type ProcessActivity struct {
	SampledAt        time.Time `json:"sampled_at"`
	Available        bool      `json:"available"`
	ProcessCount     int       `json:"process_count"`
	CPUTicks         uint64    `json:"cpu_ticks"`
	CPUAdvanced      bool      `json:"cpu_advanced_since_previous_observation"`
	RunningProcesses int       `json:"running_processes"`
	WaitingProcesses int       `json:"waiting_processes"`
}

func processActivity(group int, previous ProcessActivity) ProcessActivity {
	activity := ProcessActivity{SampledAt: time.Now().UTC()}
	if group == 0 {
		return activity
	}
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return activity
	}
	activity.Available = true
	for _, entry := range entries {
		if _, err := strconv.Atoi(entry.Name()); err != nil {
			continue
		}
		data, err := os.ReadFile(filepath.Join("/proc", entry.Name(), "stat"))
		if err != nil {
			continue
		}
		closeParen := strings.LastIndexByte(string(data), ')')
		if closeParen < 0 {
			continue
		}
		fields := strings.Fields(string(data[closeParen+1:]))
		if len(fields) < 13 {
			continue
		}
		processGroup, err := strconv.Atoi(fields[2])
		if err != nil || processGroup != group {
			continue
		}
		user, e1 := strconv.ParseUint(fields[11], 10, 64)
		system, e2 := strconv.ParseUint(fields[12], 10, 64)
		if e1 != nil || e2 != nil {
			continue
		}
		activity.ProcessCount++
		activity.CPUTicks += user + system
		if fields[0] == "R" {
			activity.RunningProcesses++
		} else {
			activity.WaitingProcesses++
		}
	}
	activity.CPUAdvanced = previous.Available && activity.CPUTicks > previous.CPUTicks
	return activity
}
