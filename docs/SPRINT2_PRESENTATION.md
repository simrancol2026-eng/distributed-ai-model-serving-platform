# Sprint 2 presentation: speaker script and demo guide

Prepared October 15, 2026. This is a presentation script, not a claim that a new demo has already run.

## What is pushed

The five Sprint 2 implementation items are merged into main at 1c1d932eba0dd75a8920186d4571cce0eeaba470. Its source tree matches tested commit 2f731ba2991cbcfbb68191e0c620f3b89da1b238. The presentation and closeout documentation are pushed on codex/sprint2-docs-demo in PR #5; documentation review and rehearsal are pending.

Repository: https://github.com/simrancol2026-eng/distributed-ai-model-serving-platform
Documentation PR: https://github.com/simrancol2026-eng/distributed-ai-model-serving-platform/pull/5
Startup and commands: [DEMO.md](DEMO.md)
Evidence and limitations: [SPRINT2_CLOSEOUT.md](SPRINT2_CLOSEOUT.md)

**Docker: WORK IN PROGRESS — a worker Dockerfile exists; image build/run validation and the full multi-service container setup are not complete. Today's demo uses native processes.**

## Before presenting

Use the startup instructions in DEMO.md. Have the model downloaded, PostgreSQL running, two workers running on ports 8001 and 8002, Spring on 8080, and the frontend on 5173. Verify both workers report different IDs/PIDs and the expected revision. Seed the registry and test a real request before the audience arrives.

Keep these ready:
- Browser: frontend Models and inference workspace.
- Terminal: worker health, model versions, inference responses and request lookup.
- Backend terminal: routing decision log lines.
- GitHub: repository, tested commit and evidence index.

Use a dedicated demo database. Keep credentials off screen. Record the actual checkout with git rev-parse HEAD. Two worker processes on one machine demonstrate request distribution, not deployment across two physical machines.

## Full speaking script (about 8–10 minutes including interactions)

### 1. Introduce the problem — 45 seconds

**Show:** project title and architecture below.

**Say:**
“Hello everyone. Our capstone is the Distributed AI Model Serving Platform.

Running an AI model once is only part of the problem. When an application sends many requests, we also need to know which model version to use, which worker should handle the request, and what happened afterward.

Our project builds that serving layer. The application uses one common API, and the platform manages model metadata, worker selection and request tracking.

Today I will show the Sprint 2 implementation using a real text-sentiment model. The broader design is intended to support other model types through a common worker interface.”

React frontend → Spring Boot API → worker A or worker B → model prediction
                                  ↕
                              PostgreSQL

### 2. Give a simple example — 45 seconds

**Show:** the two worker terminals.

**Say:**
“Imagine a service desk with two staff members who can do the same job. A receptionist receives requests and gives them to the staff in turn.

Our backend is the receptionist. The two model workers are the staff. The model registry is the list of jobs and approved versions they can perform. The request ID is the ticket number.

For example, a customer sends ‘This movie is wonderful.’ The platform sends that text to a worker running the sentiment model and returns its prediction. The next eligible request goes to the other worker. We can then use the ticket number to see which worker handled the request and why.”

### 3. Show the registry and version — 1 minute

**Show:** Models page, then GET /api/models/sentiment-distilbert/versions.

**Say:**
“This is our model registry. It stores the model identifier, capability and registered versions in PostgreSQL.

Here is the sentiment model and its registered version. Each version records an artifact and an exact revision. This matters because two versions of a model can behave differently, so an application should be able to request the intended version.

Registering a model records its metadata. It does not automatically download or start that model. A worker must already be running the matching artifact and revision.”

**Point out:** the actual version and revision returned. Do not present example catalog entries as registered models.

### 4. Run real inference — 1 minute

**Show:** inference workspace. Submit “This movie is wonderful.”

**Say before sending:**
“I will now send a real inference request from the frontend. React sends it to Spring Boot, and Spring forwards it to a Python worker that has loaded the model.”

**Say after a successful response:**
“The result on screen shows the prediction and confidence returned by the model. It also identifies the worker. The API response includes a request ID, although the current workspace does not display that ID. The latency shown is the measurement for this request.”

**Point out:** actual label, score and worker. Do not preannounce a confidence score or latency. Confidence is not a measured accuracy score.

### 5. Demonstrate two-worker routing — 1 minute

**Show:** the six-request PowerShell sequence in DEMO.md with other clients idle.

**Say:**
“Both workers in this demonstration run the same model revision. I will send six sequential requests and show the worker endpoint for each one.

The current policy is round-robin: it takes turns across eligible workers. The first worker in this sequence depends on requests already sent, but the endpoints should alternate.

This establishes our baseline routing policy. It does not yet choose workers based on CPU usage or predicted response time. That is future work.”

**Point out:** actual alternating endpoints and chosen fields. If they do not alternate, investigate rather than claiming success.

### 6. Explain version-aware selection — 1 minute

**Show:** explicit model and v1 request from DEMO.md; then the version-dispatch check if rehearsed.

**Say:**
“When a request names a model and version, the platform first resolves the registry entry. It then considers only workers reporting the matching artifact and revision.

If no worker serves the requested version, the platform rejects the request rather than silently using a different model.

The optional rejection demonstration uses a deliberately unserved synthetic revision. That revision is test metadata; it is not another model that we have executed.”

**If skipping the optional check:** show the documented 422 behavior and identify it as previously tested evidence, not a live result.

### 7. Trace the request and explain the decision — 90 seconds

**Show:** obtain the UI request ID from its POST response in browser developer tools (Network), then GET /api/requests/{requestId} and its backend log line. Alternatively, run the terminal request in DEMO.md and trace that separate request; do not imply it is the UI request.

**Say:**
“This request ID connects the prediction to its stored record. The lifecycle records receipt, dispatch and completion. For this successful request, the final status should be SUCCEEDED.

The routing decision explains which strategy was used, which workers were eligible and which endpoint was chosen. We can compare the decision in the response, the database-backed request record and the backend log.

This helps us debug the serving system. We can distinguish a wrong version selection from a worker failure and trace an individual request.

Tracking is best effort. If the database becomes unavailable, some tracking records can be missing or incomplete even when an unversioned inference succeeds. Also, eligibility currently reflects model identity, not a guarantee that a worker is healthy.”

**Point out:** matching request ID, status, worker endpoint and routing fields. A final record does not prove that the audience saw every intermediate state live.

### 8. Explain testing and work in progress — 1 minute

**Show:** closeout evidence document, then the WIP list.

**Say:**
“The merged implementation has been reviewed against independent test evidence. That evidence includes real PostgreSQL behavior and real-model requests, alongside separate unit and frontend tests.

For the latest routing change, the supplied report records 86 Maven tests and 12 frontend tests passing. The Maven tests use H2 and test doubles, so we do not treat them as proof of real PostgreSQL execution. Separate integration evidence covers the real stack.

Docker is still work in progress. A worker Dockerfile exists, but container build and runtime validation and the full multi-service setup are not complete. Today's demonstration uses native processes.

Intelligent routing, automatic failover, monitoring and scaling remain future work. We are not claiming measured performance improvements yet.”

### 9. Close — 30 seconds

**Show:** repository and next steps.

**Say:**
“Sprint 2 gives us five implemented capabilities: a model registry with versions, two-worker round-robin distribution, version-aware dispatch, persistent request lifecycle tracking, and explainable routing decisions.

Together, these give us a working serving baseline that we can demonstrate and evaluate. The next step after review is to build on this baseline and compare more intelligent routing strategies using reproducible measurements.

Thank you. I can now walk through a request or answer questions about the architecture.”

## STAR explanation (about 90 seconds)

**Situation:** “AI applications need more than a model that returns a prediction. As requests and model versions increase, they need a consistent way to select the correct model, distribute requests and understand failures.”

**Task:** “Our capstone task is to build a distributed model-serving platform. For Sprint 2, our goal was a working baseline with registered versions, multiple workers and traceable request handling.”

**Action:** “We connected a React frontend to a Spring Boot API and Python model workers. We stored registry and request data in PostgreSQL, implemented round-robin routing and exact-version matching, and added routing decisions to responses, logs and stored records. We reviewed code and independent test evidence, separating test doubles from real-model and real-database execution.”

**Result:** “The five Sprint 2 implementation items are merged, and the supplied integration evidence demonstrates the serving path with a real sentiment model. We can identify the requested version, selected worker and recorded outcome. Demo rehearsal and sprint closeout remain pending. Docker is work in progress, and improved latency, scalability and failover are goals for later evaluation rather than results we have already proven.”

## Quick answers for questions

- **Are you training a new model?** The current work is serving and coordinating an existing model; model training is not the Sprint 2 deliverable.
- **Why two workers?** To demonstrate distributing requests across separate model-serving processes and establish a baseline for later evaluation.
- **Does this make inference faster?** It enables distribution. We have not yet established a performance improvement through controlled comparative evaluation.
- **What happens if a worker fails?** The selected request can fail with a worker-unavailable response. Automatic failover is not implemented.
- **Does registered mean deployed?** No. Registration records metadata; a matching worker must already be running.
- **Is it running in Docker?** Not in this demo. Docker is work in progress and unverified.
- **Can it serve image models today?** The current executable workload is text sentiment. Image workloads are planned and should not be demonstrated as completed.

## If the live demo fails

Say: “This step did not complete successfully in this run. I will show the recorded evidence separately and explain what it demonstrates.”

Use only actual previously captured screenshots/logs, identify their tested commit and date, and label them as recorded evidence. Do not pass a saved response off as a live result.
