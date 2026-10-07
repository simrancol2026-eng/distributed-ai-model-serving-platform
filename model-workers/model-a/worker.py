"""Model worker "model-a": serves one Hugging Face text-classification model over HTTP.

Contract (used by the Spring Boot control plane):
  GET  /health   -> {"status": "healthy", "worker": ..., "model": ..., "capability": ..., "task": ...}
  POST /predict  JSON {"text": "..."}
              -> {"worker", "model", "version", "task", "label", "score", "inferenceMs"}

The model loads once at startup. If loading fails, the process fails to start,
so a worker that answers /health always has a loaded model.
"""

import os
import time

import transformers
from fastapi import FastAPI
from pydantic import BaseModel, Field
from transformers import pipeline

WORKER_ID = os.getenv("WORKER_ID", "model-a")
MODEL_NAME = os.getenv("MODEL_NAME", "distilbert-base-uncased-finetuned-sst-2-english")
TASK = "sentiment-analysis"
CAPABILITY = "TEXT"
MAX_TEXT_CHARS = 10_000

classifier = pipeline(TASK, model=MODEL_NAME)

# Exact model revision (Hugging Face git commit) when the library exposes it; otherwise None.
# We report None rather than inventing a version string.
MODEL_VERSION = getattr(classifier.model.config, "_commit_hash", None)

app = FastAPI(title=f"Model worker {WORKER_ID}")


class PredictRequest(BaseModel):
    text: str = Field(min_length=1, max_length=MAX_TEXT_CHARS)


@app.get("/health")
def health():
    return {
        "status": "healthy",
        "worker": WORKER_ID,
        "model": MODEL_NAME,
        "capability": CAPABILITY,
        "task": TASK,
        "transformers": transformers.__version__,
    }


@app.post("/predict")
def predict(request: PredictRequest):
    started = time.perf_counter()
    # truncation=True: DistilBERT accepts at most 512 tokens; longer input would raise an error.
    result = classifier(request.text, truncation=True)[0]
    inference_ms = (time.perf_counter() - started) * 1000
    return {
        "worker": WORKER_ID,
        "model": MODEL_NAME,
        "version": MODEL_VERSION,
        "task": TASK,
        "label": result["label"],
        "score": float(result["score"]),
        "inferenceMs": round(inference_ms, 2),
    }
