# Harness used for the terminal screenshots

This directory is the transcript script that printed the terminal shots. It is not an acceptance suite, and it was not run as a repeated evaluation.

No policy-neutral HEALTH_LATENCY end-to-end suite was executed for this screenshot job. The product scripts under `scripts/` (`round_robin_e2e_test.py`, `version_dispatch_e2e_test.py`, `routing_decision_e2e_test.py`, `phase1_e2e_test.py`) were not invoked. Those scripts are not copied here.

## `show_policy.py`

One POST `/api/inference` per process, then GET `/api/requests/{id}` and the matching Spring `routing decision` line from the log for that process. The policy name is the first argument. It does not assert alternation, latency, or failure counts.

Dependencies: Python 3 standard library only (`json`, `subprocess`, `sys`, `urllib.request`, `datetime`, `pathlib`). No pip packages. It also needs `git` on `PATH`.

Paths it expects, from the run that produced the screenshots:

- repository: `/tmp/s3-39e59e7` (for `git rev-parse HEAD`)
- backend: `http://127.0.0.1:8080`
- Spring log: `/tmp/s3-39-run/logs/spring-<POLICY>.log`

Invocations, once each, after that policy's Spring process was up:

```
python3 /tmp/s3-39-run/show_policy.py ROUND_ROBIN
python3 /tmp/s3-39-run/show_policy.py LEAST_IN_FLIGHT
python3 /tmp/s3-39-run/show_policy.py HEALTH_LATENCY
```

The copy in this directory is that source. The requests those three invocations created are the API request ids in `RUN_RECORD.md`.
