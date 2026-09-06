---
name: local-api-load-test
description: Run safe, evidence-based local API load tests with JMeter, including endpoint discovery, synthetic fixtures, smoke/baseline/load phases, asynchronous consistency checks, metrics, and exact cleanup. Use for requests to benchmark, pressure-test, or capacity-test a local service API; do not use for production load generation or ordinary unit tests.
---

# Local API Load Test

Use this skill when the user asks to run, benchmark, pressure-test, or compare the performance of a local or test-environment API. The goal is a reproducible result with verified cleanup, not just a JMeter process exit code.

## Read before acting

Read the project guide [LOCAL_API_LOAD_TEST_GUIDE.md](../../../docs/LOCAL_API_LOAD_TEST_GUIDE.md) before the first test action. It contains the project-specific JMeter path, environment conventions, result format, and previous examples. Read [VMWARE_LOCAL_ACCESS.md](../../../docs/VMWARE_LOCAL_ACCESS.md) first when the target uses VMware, Docker, Nginx, a gateway, or a remote dependency.

## Required workflow

1. **Discover the real endpoint.** Inspect the relevant Controller, DTO, Service, configuration, and route definitions. Confirm method, path, request shape, authentication, success response, and downstream effects. Do not infer an endpoint from a performance claim alone.
2. **Classify the risk.** Separate read/calculation APIs from write or asynchronous APIs. A write test requires synthetic data, an explicit rollback/cleanup plan, and a safe concurrency level. Never treat a production endpoint, real user, real order, payment, inventory, or irreversible operation as a local fixture.
3. **Choose the measurement boundary.** Prefer direct service access for service latency. Test the gateway separately when end-to-end latency is requested. Do not compare direct and gateway results as if they were one series, and never use an invalid token as a performance sample.
4. **Build isolated fixtures.** Use a unique `runTag`, synthetic IDs, a transaction, and a recorded list of every created row/key/file/message. Check expected match counts before creating or deleting anything. Use official test APIs when they are idempotent and reliably reversible; use direct test-database fixtures only when the schema and authorization are clear.
5. **Smoke before load.** Send one real request and verify authentication, payload, data visibility, HTTP status, and a stable business assertion. If the API legitimately returns an empty result, distinguish that from a failed request.
6. **Run in phases.** Start with a low-risk baseline, then a short load, then step up only when requested and when the previous phase passes. Default starting profile is 1 thread for 10 seconds, followed by 5 threads with a 5-second ramp-up for 30 seconds; tune it for the API's side effects and downstream capacity.
7. **Verify asynchronous behavior.** For queues, delayed writes, caches, batch aggregation, or eventual consistency, define the completion signal before loading. After the load, wait longer than the observed delay window and verify the final database/cache/message state. For a write-back API, do not validate only HTTP 200.
8. **Measure and report.** From the same JTL, report samples, success/error counts and rate, throughput, average, minimum, P50, P90, P95, P99, P99.9, maximum, and standard deviation. Include status/assertion error types and the direct/gateway boundary.
9. **Clean in a finally path.** Delete child records and side effects before parent records, using the exact `runTag` or recorded IDs. Verify zero residual rows/keys and remove temporary JMX/JTL/log files. Never use a full-table delete, Redis flush, auto-increment reset, or service restart as cleanup.

## JMeter and PowerShell invariants

- Use non-GUI JMeter and do not save response bodies unless the user explicitly needs a redacted sample.
- Keep credentials, JWTs, private keys, and connection strings out of JMX files, scripts, logs, result files, and command output.
- When invoking the Windows `jmeter.bat` from PowerShell, construct an argument array and pass expanded `-J` values as quoted array elements. Do not write an unquoted form such as `-JuserId=$userId`; PowerShell can pass the literal variable name to the batch file.
- The helper temporarily puts a valid `JAVA_HOME\bin` first in `PATH` for JMeter, then restores `PATH`, so the batch file does not silently select an incompatible or blocked Java executable from an earlier PATH entry.
- Keep the JMX request and assertion specific to the current endpoint. A successful HTTP status is not automatically a successful business operation.
- Use the bundled helpers for repeatable mechanics:
  - [run-jmeter.ps1](scripts/run-jmeter.ps1) runs a JMX with validated `-J` properties, CSV JTL output, and response-data suppression.
  - [summarize-jtl.ps1](scripts/summarize-jtl.ps1) reads a JTL and emits consistent latency and throughput metrics.
- These helpers do not create or delete business fixtures. Keep domain-specific preparation and cleanup in the test procedure, with a preflight count and a failure-safe cleanup path.

Example helper invocation from PowerShell:

```powershell
$properties = @{ threads = 1; rampUp = 1; duration = 10; userId = $testUserId }
& .\.agents\skills\local-api-load-test\scripts\run-jmeter.ps1 -Jmx .\api-load-test.jmx -ResultFile .\api-load-test.jtl -LogFile .\api-load-test.log -Properties $properties
& .\.agents\skills\local-api-load-test\scripts\summarize-jtl.ps1 -Jtl .\api-load-test.jtl
```

## Stop conditions

Stop before load and report the blocker if:

- the real method/path or success assertion is unknown;
- authentication would require guessing or exposing a credential;
- the fixture cannot be isolated and precisely removed;
- a write endpoint has irreversible side effects without an approved rollback;
- smoke requests fail;
- an asynchronous completion signal or data-loss check is undefined.

A single short run is not proof of production capacity, a percentage reduction in database load, or an optimization claim. State the test boundary and limitations explicitly, and compare versions only with the same code/data/environment/profile/statistics.

## Report shape

Return:

1. target method/path and measurement boundary;
2. environment and code version when known;
3. fixture scope and concurrency profile;
4. JTL metrics and error breakdown;
5. asynchronous or persistence verification;
6. cleanup verification;
7. limitations and recommended next test.

