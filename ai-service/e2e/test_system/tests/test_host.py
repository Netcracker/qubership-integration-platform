import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_system.host import lock_file, process_alive


class HostTest(unittest.TestCase):
    def test_probe_keeps_a_live_worker_running(self):
        child = subprocess.Popen(
            [sys.executable, "-u", "-c", "print('ready'); input(); print('completed')"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            text=True,
        )
        try:
            self.assertEqual(child.stdout.readline().strip(), "ready")
            self.assertTrue(process_alive(child.pid))
            output, _ = child.communicate("\n", timeout=5)
            self.assertEqual(output.strip(), "completed")
            self.assertFalse(process_alive(child.pid))
        finally:
            if child.poll() is None:
                child.terminate()
            child.wait()

    def test_lock_excludes_another_process_and_releases_on_close(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "run.lock"
            source = (
                "import sys; from test_system.host import lock_file; "
                "f=open(sys.argv[1], 'a+b'); lock_file(f); print('locked', flush=True); input()"
            )
            child = subprocess.Popen(
                [sys.executable, "-u", "-c", source, str(file)],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                text=True,
            )
            try:
                self.assertEqual(child.stdout.readline().strip(), "locked")
                with file.open("a+b") as lock:
                    with self.assertRaises(BlockingIOError):
                        lock_file(lock, blocking=False)
                child.communicate("\n", timeout=5)
                with file.open("a+b") as lock:
                    lock_file(lock, blocking=False)
            finally:
                if child.poll() is None:
                    child.terminate()
                child.wait()
