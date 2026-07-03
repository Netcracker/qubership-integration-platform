import { configure } from "@testing-library/dom";

// Antd v6 defers more work (form-field watch, Select dropdown rendering) to
// macro tasks, so `waitFor`/`findBy*` need a larger budget than the 1s default
// to stay reliable under parallel-run CPU contention.
configure({ asyncUtilTimeout: 5000 });
