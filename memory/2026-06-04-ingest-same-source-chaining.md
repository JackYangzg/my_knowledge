# 2026-06-04 ingest same-source chaining

## Symptom

After importing files, parse tasks could remain visibly queued while the ingest runner was busy with other queued parse work. The expected behavior is per-file flow: once one source finishes parse, it should immediately continue to analysis and generation instead of waiting for every other source's parse stage to finish.

## Root cause

`IngestOrchestrator.runUntilIdle` claimed pending tasks from a global priority queue:

- parse priority 10
- analysis priority 9
- generation priority 8

That global ordering optimized stage priority, but it created a batch-stage behavior during multiple imports: all parse tasks could be claimed before any analysis/generation tasks. This made a single source feel stuck in "queued" even though the runner was active.

## Fix

After a successful `parse`, `analysis`, or `generation`, the same worker lane now tries to atomically claim the next pending task for the same `sourceId` before returning to the global queue. Different sources still run concurrently. Wiki writes remain protected by page-level write locks, so only final same-page write/merge work is serialized.

Files changed:

- `ProcessingTaskDao`: added `claimNextPendingTaskForSource`.
- `IngestOrchestrator`: chains same-source next stages after successful chainable tasks.
- `IngestQueuePolicy`: centralizes which task types may continue same-source chaining.
- `IngestQueuePolicyTest`: regression test for the chaining policy.

## Verification

Passed:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests 'com.my.knowledge.data.ingest.*'
```
