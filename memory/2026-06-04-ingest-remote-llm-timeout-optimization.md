# 2026-06-04 ingest remote LLM timeout optimization

## Symptom

Ingest often failed during remote LLM calls, especially on long files. The user-visible effect was a long wait in analysis / generation followed by retry or failure.

## Root cause

The hot ingest stages used large, non-streaming remote calls:

- Stage 1 analysis sent up to about 50K source characters in one request before switching to chunking.
- Stage 2 generation sent up to 50K source characters again, plus analysis and wiki context.
- Generation waited for the full non-streaming response before reporting useful token progress.
- Ingest-specific remote attempts were set to 1, so transient 5xx / socket / timeout errors failed the stage immediately.
- Empty SSE streams could be treated like an empty successful result instead of a retryable remote failure.

## Fix

- Added `AiGateway.completeStreamObserved` and `streamJsonObserved` for ingest: streaming accumulation, per-token callback, retry events, and retryable empty-stream handling.
- Switched ingest Stage 1 analysis and long-source chunk analysis to streaming JSON with throttled progress logs.
- Switched Stage 2 generation to streaming text with throttled progress logs.
- Raised ingest remote attempts from 1 to 2.
- Lowered the long-source threshold from 50K to 30K so borderline documents enter checkpointed chunk analysis earlier.
- Capped Stage 2 source excerpt to 24K and current-index prompt context to 20K.

## Verification

Passed:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests 'com.my.knowledge.data.ai.AiGatewayStreamTest' --tests 'com.my.knowledge.data.ingest.*'
```
