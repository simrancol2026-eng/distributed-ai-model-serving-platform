"""Worker HTTP-contract tests.

By default the Hugging Face model is replaced by a small STUB, so these tests check
the API contract only (status codes, fields, validation). They do NOT prove the
model works.

To run the same tests against the REAL model (needs internet the first time):
    REAL_MODEL=1 pytest            (PowerShell: $env:REAL_MODEL=1; pytest)
"""

import os
import sys
import types

import pytest

REAL = os.getenv("REAL_MODEL") == "1"

if not REAL:
    stub = types.ModuleType("transformers")
    stub.__version__ = "stub"

    class _StubPipeline:
        model = types.SimpleNamespace(config=types.SimpleNamespace(_commit_hash="stub-rev"))

        def __call__(self, text, truncation=False):
            return [{"label": "POSITIVE" if "good" in text else "NEGATIVE", "score": 0.5}]

    stub.pipeline = lambda task, model=None: _StubPipeline()
    sys.modules["transformers"] = stub

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from fastapi.testclient import TestClient  # noqa: E402

import worker  # noqa: E402

client = TestClient(worker.app)


def test_health_reports_model_and_capability():
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "healthy"
    assert body["capability"] == "TEXT"
    assert body["model"] == worker.MODEL_NAME


def test_predict_returns_label_score_and_metadata():
    r = client.post("/predict", json={"text": "This movie was really good"})
    assert r.status_code == 200
    body = r.json()
    assert body["label"] in ("POSITIVE", "NEGATIVE")
    assert 0.0 <= body["score"] <= 1.0
    assert body["model"] == worker.MODEL_NAME
    assert body["worker"] == worker.WORKER_ID
    assert body["inferenceMs"] >= 0


@pytest.mark.skipif(not REAL, reason="sentiment correctness needs the real model")
def test_real_model_sentiment_direction():
    pos = client.post("/predict", json={"text": "I absolutely love this, it is wonderful"}).json()
    neg = client.post("/predict", json={"text": "This is terrible, I hate it"}).json()
    assert pos["label"] == "POSITIVE"
    assert neg["label"] == "NEGATIVE"


@pytest.mark.skipif(not REAL, reason="truncation behaviour needs the real tokenizer")
def test_real_model_handles_text_longer_than_512_tokens():
    r = client.post("/predict", json={"text": "good " * 2000})
    assert r.status_code == 200


def test_oversized_input_error_is_concise_and_does_not_echo_text():
    # Grok TEST REPORT round 1, bug 6: the 422 used to echo the whole ~20 KB input back.
    marker = "SECRET-PAYLOAD-"
    text = marker + "y" * 20_000
    r = client.post("/predict", json={"text": text})
    assert r.status_code == 422
    assert len(r.content) < 1000, f"422 body is {len(r.content)} bytes"
    assert marker not in r.text
    detail = r.json()["detail"]
    assert detail[0]["loc"] == ["body", "text"]
    assert "10000" in detail[0]["msg"]  # still says why it was rejected
    assert set(detail[0]) == {"loc", "msg", "type"}


def test_missing_field_error_names_the_field():
    detail = client.post("/predict", json={"wrong": "x"}).json()["detail"]
    assert detail[0]["loc"] == ["body", "text"]
    assert detail[0]["type"] == "missing"


@pytest.mark.parametrize(
    "payload",
    [{}, {"text": ""}, {"text": "x" * (worker.MAX_TEXT_CHARS + 1)}, {"wrong": "field"}],
)
def test_invalid_input_is_rejected_with_422(payload):
    assert client.post("/predict", json=payload).status_code == 422
