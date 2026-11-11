"""Model worker "model-a": serves one Hugging Face text-classification model over HTTP.

Contract (used by the Spring Boot control plane):
  GET  /health   -> {"status": "healthy", "worker": ..., "model": ..., "revision": ..., "capability": ..., "task": ...,
                     "predictionsServed": <successful /predict calls since this process started>,
                     "pid": <process id>}
  POST /predict  JSON {"text": "..."}
              -> {"worker", "model", "version", "task", "label", "score", "inferenceMs"[, "topK"]}

Specialist models (WORKER_TASK, default "text"). Every task is a real classifier, so label/score is its
honest output; "topK" (best first) is added for code and image only:
  text   TEXT   sentiment-analysis     distilbert-base-uncased-finetuned-sst-2-english   (unchanged)
  code   CODE   text-classification    huggingface/CodeBERTa-language-id  -> programming language of a snippet
  image  IMAGE  image-classification   google/vit-base-patch16-224        -> ImageNet class of a picture;
                "text" is the image as base64 (optionally a data: URL), at most MAX_TEXT_CHARS characters;
                only PNG/JPEG content of at most MAX_IMAGE_SIDE x MAX_IMAGE_SIDE pixels, else 422 invalid_image

The model loads once at startup. If loading fails, the process fails to start,
so a worker that answers /health always has a loaded model.

Several workers = several processes of this file, each with its own port and WORKER_ID, e.g.
  WORKER_ID=worker-a uvicorn worker:app --port 8001
  WORKER_ID=worker-b uvicorn worker:app --port 8002
"""

import base64
import binascii
import io
import os
import threading
import time

import transformers
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from transformers import pipeline

# task -> (pipeline task, capability, default model, max input characters, top-k reported or None)
TASKS = {
    "text": ("sentiment-analysis", "TEXT", "distilbert-base-uncased-finetuned-sst-2-english", 10_000, None),
    "code": ("text-classification", "CODE", "huggingface/CodeBERTa-language-id", 10_000, 3),
    # ~5 MB of image bytes once base64 encoded.
    "image": ("image-classification", "IMAGE", "google/vit-base-patch16-224", 7_000_000, 3),
}
WORKER_TASK = os.getenv("WORKER_TASK", "text").strip().lower()
if WORKER_TASK not in TASKS:
    raise RuntimeError(f"WORKER_TASK must be one of {sorted(TASKS)}, not {WORKER_TASK!r}")
TASK, CAPABILITY, _DEFAULT_MODEL, MAX_TEXT_CHARS, TOP_K = TASKS[WORKER_TASK]

WORKER_ID = os.getenv("WORKER_ID", "model-a")
MODEL_NAME = os.getenv("MODEL_NAME", _DEFAULT_MODEL)
# Optional: load a specific Hugging Face revision (commit hash, tag or branch). Default: the latest.
# The revision actually loaded is what /health and /predict report, not this setting.
MODEL_REVISION = os.getenv("MODEL_REVISION") or None

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


# Image input limits (D-049). Only real PNG/JPEG content is decoded (the header decides, not a file name or
# data: URL type), and the size is checked from the header BEFORE any pixel data is decoded.
IMAGE_FORMATS = ("PNG", "JPEG")
MAX_IMAGE_SIDE = 4096
MAX_IMAGE_PIXELS = MAX_IMAGE_SIDE * MAX_IMAGE_SIDE


def _decode_image(text):
    """base64 (or a data: URL) -> RGB PIL image; None if it is not an acceptable PNG/JPEG image."""
    from PIL import Image, UnidentifiedImageError  # only image workers need Pillow
    if text.startswith("data:"):
        text = text.partition(",")[2]
    try:
        image = Image.open(io.BytesIO(base64.b64decode(text, validate=True)), formats=IMAGE_FORMATS)
        width, height = image.size
        if width < 1 or height < 1 or width > MAX_IMAGE_SIDE or height > MAX_IMAGE_SIDE \
                or width * height > MAX_IMAGE_PIXELS:
            return None
        image.load()  # decodes fully now: truncated files fail here, not inside the model
        return image.convert("RGB")
    except (binascii.Error, ValueError, UnidentifiedImageError, OSError, Image.DecompressionBombError):
        return None


def _invalid_image():
    return JSONResponse(status_code=422, content={"detail": [{
        "loc": ["body", "text"],
        "msg": f"Field 'text' must be a base64-encoded PNG or JPEG image of at most "
               f"{MAX_IMAGE_SIDE} x {MAX_IMAGE_SIDE} pixels",
        "type": "invalid_image"}]})


def _ranked(output):
    """Pipeline output for ONE input -> list of {"label", "score"}, best first."""
    if output and isinstance(output[0], list):  # some versions nest one list per input
        output = output[0]
    ranked = sorted(output, key=lambda r: r["score"], reverse=True)
    return [{"label": r["label"], "score": float(r["score"])} for r in ranked]


@app.post("/predict")
def predict(request: PredictRequest):
    started = time.perf_counter()
    top_k = None
    if WORKER_TASK == "text":
        # truncation=True: DistilBERT accepts at most 512 tokens; longer input would raise an error.
        result = classifier(request.text, truncation=True)[0]
    elif WORKER_TASK == "code":
        top_k = _ranked(classifier(request.text, truncation=True, top_k=TOP_K))
        result = top_k[0]
    else:
        image = _decode_image(request.text)
        if image is None:
            return _invalid_image()
        top_k = _ranked(classifier(image, top_k=TOP_K))
        result = top_k[0]
    inference_ms = (time.perf_counter() - started) * 1000
    global _predictions_served
    with _served_lock:  # FastAPI runs sync endpoints on a thread pool
        _predictions_served += 1
    body = {
        "worker": WORKER_ID,
        "model": MODEL_NAME,
        "version": MODEL_VERSION,
        "task": TASK,
        "label": result["label"],
        "score": float(result["score"]),
        "inferenceMs": round(inference_ms, 2),
    }
    if top_k is not None:
        body["topK"] = top_k
    return body
