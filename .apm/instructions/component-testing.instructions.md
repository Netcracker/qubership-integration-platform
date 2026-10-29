---
description: Extend coverage with generated snapshot component tests.
applyTo: "{integration-build-pipeline/src/{test/resources/testConfigurations,test-support}/**,runtime-catalog/src/test/java/**/snapshotbundle/**,**/SnapshotBundleProducerIntegrationTest.java,micro-engine/src/test/java/**/engine/routes/**,micro-engine/src/test/resources/{testspecifications,snapshot-fixtures}/**,micro-engine/src/test/resources/testspecifications.schema.json,micro-engine/{TESTING,SNAPSHOT-COVERAGE}.md,scripts/*micro-engine-snapshots*}"
---

When adding or extending generated snapshot component tests across
`integration-build-pipeline`, `runtime-catalog`, and `micro-engine`, apply the
`component-testing` skill.
