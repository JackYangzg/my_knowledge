# Ingest background socket abort and AI timeout

Date: 2026-06-04

## Symptom

- When the phone screen turns off, ingest can fail with `Software caused connection abort`.
- Ingest analysis / generation requests still timed out at 180 seconds instead of the requested 5 minutes.

## Root Cause

- `IngestRuntime` is process-scoped, but it did not hold a CPU wake lock or Wi-Fi lock while draining the queue. When Android puts the device into a low-power screen-off state, a long `HttpURLConnection` read can be interrupted by system network/power management and surface as a socket abort.
- `AiGateway.AI_READ_TIMEOUT_MS` and `IngestOrchestrator.AI_READ_TIMEOUT_MS` were both still `180_000`.

## Fix

- Added `android.permission.WAKE_LOCK`.
- `IngestRuntime` now holds a non-reference-counted `PARTIAL_WAKE_LOCK` plus a high-performance `WifiLock` while `runUntilIdle()` drains work, then releases both in `finally`.
- Increased AI read timeout constants to `300_000` ms and updated timeout messages/logs to 5 minutes.
- Added a regression test that proves `chatJson` retries a socket disconnect and succeeds on the next response.

## Verification

- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew compileDebugKotlin compileDebugUnitTestKotlin testDebugUnitTest`
- Result: pass.

## Residual Risk

Android can still kill the whole app process under severe memory/battery pressure. The fix guarantees the ingest loop is protected while the app process remains alive, matching the current product requirement.
