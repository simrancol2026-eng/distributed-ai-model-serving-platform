"""Model worker "model-a": serves one Hugging Face text-classification model over HTTP.

Contract (used by the Spring Boot control plane):
  GET  /health   -> {"status": "healthy", "worker": ..., "model": ..., "revision": ..., "capability": ..., "task": ...,
                     "predictionsServed": <successful /predict calls since this process started>,
                     "pid": <process id>}
  POST /predict  JSON {"text": "..."}
              -> {"worker", "model", "version", "task", "label", "score", "inferenceMs"}

The model loads once at startup. If loading fails, the process fails to start,
so a worker that answers /health always has a loaded model.

Several workers = several processes of this file, each with its own port and WORKER_ID, e.g.
  WORKER_ID=worker-a uvicorn worker:app --port 8001
  WORKER_ID=worker-b uvicorn worker:app --port 8002
"""

import os
import threading
import time

import transformers
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from transformers import pipeline

WORKER_ID = os.getenv("WORKER_ID", "model-a")
MODEL_NAME = os.getenv("MODEL_NAME", "distilbert-base-uncased-finetuned-sst-2-english")
# Optional: load a specific Hugging Face revision (commit hash, tag or branch). Default: the latest.
# The revision actually loaded is what /health and /predict report, not this setting.
MODEL_REVISION = os.getenv("MODEL_REVISION") or None
TASK = "sentiment-analysis"
CAPABILITY = "TEXT"
MAX_TEXT_CHARS = 10_000

classifier = pipeline(TASK, model=MODEL_NAME, revision=MODEL_REVISION) if MODEL_REVISION \
    else pipeline(TASK, model=MODEL_NAME)

# Exact model revision (Hugging Face git commit) when the library exposes it; otherwise None.
# We report None rather than inventing a version string.
MODEL_VERSION = getattr(classifier.model.config, "_commit_hash", None)

app = FastAPI(title=f"Model worker {WORKER_ID}")

# Count of successful predictions by THIS process. Evidence that a request really reached this
# worker (e.g. round-robin tests). In memory only: resets when the process restarts.
_served_lock = threading.Lock()
_predictions_served = 0


@app.exception_handler(RequestValidationError)
async def concise_validation_error(request: Request, exc: RequestValidationError):
    """422 with the field and the reason only.

    FastAPI's default 422 echoes the rejected input back, so a 20 KB text was
    returned in full. We keep `loc`, `msg` and `type`, and drop `input` and `ctx`.
    """
    errors = [{"loc": list(e.get("loc", [])), "msg": e.get("msg", ""), "type": e.get("type", "")}
              for e in exc.errors()]
    return JSONResponse(status_code=422, content={"detail": errors})


class PredictRequest(BaseModel):
    text: str = Field(min_length=1, max_length=MAX_TEXT_CHARS)


@app.get("/health")
def health():
    return {
        "status": "healthy",
        "worker": WORKER_ID,
        "model": MODEL_NAME,
        "revision": MODEL_VERSION,  # commit actually loaded; the backend routes versioned requests by it
        "capability": CAPABILITY,
        "task": TASK,
        "transformers": transformers.__version__,
        "predictionsServed": _predictions_served,
        "pid": os.getpid(),  # different processes have different pids
    }


@app.post("/predict")
def predict(request: PredictRequest):
    started = time.perf_counter()
    # truncation=True: DistilBERT accepts at most 512 tokens; longer input would raise an error.
    result = classifier(request.text, truncation=True)[0]
    inference_ms = (time.perf_counter() - started) * 1000
    global _predictions_served
    with _served_lock:  # FastAPI runs sync endpoints on a thread pool
        _predictions_served += 1
    return {
        "worker": WORKER_ID,
        "model": MODEL_NAME,
        "version": MODEL_VERSION,
        "task": TASK,
        "label": result["label"],
        "score": float(result["score"]),
        "inferenceMs": round(inference_ms, 2),
    }
