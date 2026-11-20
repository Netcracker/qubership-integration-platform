// Shown failing: returning HTTP 401 for the echo call fails the route test.
import { registerHttpEchoTests } from "../../suites/http-echo.js";
import { test } from "../../support/external-echo.js";

registerHttpEchoTests(test);
