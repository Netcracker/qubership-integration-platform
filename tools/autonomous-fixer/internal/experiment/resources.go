package experiment

import (
	"encoding/json"
	"os"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type ResourceSample struct {
	At                 time.Time `json:"at"`
	Scope              string    `json:"scope"`
	CPUCount           int       `json:"cpu_count"`
	MemoryTotalKiB     *uint64   `json:"memory_total_kib"`
	MemoryAvailableKiB *uint64   `json:"memory_available_kib"`
	SwapFreeKiB        *uint64   `json:"swap_free_kib"`
	DiskAvailableBytes *uint64   `json:"disk_available_bytes"`
	LoadAverage        string    `json:"load_average,omitempty"`
}

func SampleResources(path string) ResourceSample {
	s := ResourceSample{At: time.Now().UTC(), Scope: "controller host; includes unrelated processes and the Docker VM", CPUCount: runtime.NumCPU()}
	if b, e := os.ReadFile("/proc/meminfo"); e == nil {
		for _, line := range strings.Split(string(b), "\n") {
			f := strings.Fields(line)
			if len(f) != 3 || f[2] != "kB" {
				continue
			}
			n, e := strconv.ParseUint(f[1], 10, 64)
			if e != nil {
				continue
			}
			switch f[0] {
			case "MemTotal:":
				s.MemoryTotalKiB = &n
			case "MemAvailable:":
				s.MemoryAvailableKiB = &n
			case "SwapFree:":
				s.SwapFreeKiB = &n
			}
		}
	}
	var stat syscall.Statfs_t
	if e := syscall.Statfs(path, &stat); e == nil {
		n := stat.Bavail * uint64(stat.Bsize)
		s.DiskAvailableBytes = &n
	}
	if b, e := os.ReadFile("/proc/loadavg"); e == nil {
		s.LoadAverage = strings.TrimSpace(string(b))
	}
	return s
}

func RecordResources(path, root string) (func() error, error) {
	f, e := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if e != nil {
		return nil, e
	}
	stop, done := make(chan struct{}), make(chan error, 1)
	go func() {
		encoder := json.NewEncoder(f)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		var err error
		write := func() {
			if err == nil {
				err = encoder.Encode(SampleResources(root))
			}
		}
		write()
		for {
			select {
			case <-ticker.C:
				write()
			case <-stop:
				write()
				done <- firstError(err, f.Close())
				return
			}
		}
	}()
	return func() error { close(stop); return <-done }, nil
}
