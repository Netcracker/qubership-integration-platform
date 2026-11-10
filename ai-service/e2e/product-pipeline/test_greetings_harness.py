import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


class GreetingsHarnessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classpath = os.environ.get("QIP_GROOVY_CLASSPATH")
        if not cls.classpath:
            raise unittest.SkipTest(
                "QIP_GROOVY_CLASSPATH must contain the Camel and Groovy runtime dependencies"
            )
        cls.java = (
            str(Path(os.environ["JAVA_HOME"], "bin", "java"))
            if "JAVA_HOME" in os.environ
            else "java"
        )
        cls.harness = Path(__file__).with_name("runtime-checks") / "greetings.groovy"

    def run_script(self, script, script_count=1):
        with tempfile.TemporaryDirectory() as directory:
            chain = Path(directory, "chain.json")
            chain.write_text(
                json.dumps(
                    {
                        "elements": [
                            {"type": "script", "properties": {"script": script}}
                            for _ in range(script_count)
                        ]
                    }
                ),
                encoding="utf-8",
            )
            return subprocess.run(
                [
                    self.java,
                    "-XX:TieredStopAtLevel=1",
                    "-cp",
                    self.classpath,
                    "groovy.ui.GroovyMain",
                    str(self.harness),
                    str(chain),
                ],
                capture_output=True,
                text=True,
                timeout=60,
                check=False,
            )

    def test_accepts_greeting_without_a_script_content_type(self):
        result = self.run_script("exchange.in.body = 'Hello world'")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        report = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(
            ("Hello world", "PASS", "NOT_RUN"),
            (
                report["actual"],
                report["scriptBody"]["verdict"],
                report["httpResponse"]["verdict"],
            ),
        )

    def test_script_content_type_does_not_establish_an_http_response(self):
        result = self.run_script(
            "exchange.in.body = 'Hello world'; exchange.in.setHeader('Content-Type', 'text/plain')"
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        report = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual("NOT_RUN", report["httpResponse"]["verdict"])

    def test_accepts_plain_text_without_a_fixed_greeting_word(self):
        result = self.run_script("exchange.in.body = 'Greetings from Rocky'")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        report = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(
            ("Greetings from Rocky", "PASS", "NOT_RUN"),
            (
                report["actual"],
                report["scriptBody"]["verdict"],
                report["httpResponse"]["verdict"],
            ),
        )

    def test_rejects_invalid_plain_text_bodies(self):
        for name, script in (
            ("empty string", "exchange.in.body = ''"),
            ("whitespace", "exchange.in.body = '   '"),
            ("null", "exchange.in.body = null"),
            ("object", "exchange.in.body = [greeting: 'Hello world']"),
        ):
            with self.subTest(body=name):
                result = self.run_script(script)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("java.lang.AssertionError", result.stderr)

    def test_preserves_script_execution_failures(self):
        result = self.run_script(
            "throw new IllegalStateException('script execution failed')"
        )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("IllegalStateException", result.stderr)

    def test_rejects_ambiguous_script_selection(self):
        for count in (0, 2):
            with self.subTest(script_count=count):
                result = self.run_script(
                    "exchange.in.body = 'Hello world'", script_count=count
                )
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("scriptCount", result.stderr)


if __name__ == "__main__":
    unittest.main()
