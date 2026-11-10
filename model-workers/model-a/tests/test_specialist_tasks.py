"""Contract tests for the CODE and IMAGE worker tasks (WORKER_TASK=code / image).

The Hugging Face pipelines are replaced by STUBS, so these tests check the HTTP contract,
input decoding and topK shape only. They do NOT prove that either model works; that needs
the real models (fresh-clone run with internet access).
"""

import base64
import importlib.util
import io
import os
import sys
import types

import pytest
from fastapi.testclient import TestClient
from PIL import Image

WORKER_PY = os.path.join(os.path.dirname(__file__), "..", "worker.py")


class _StubPipeline:
    model = types.SimpleNamespace(config=types.SimpleNamespace(_commit_hash="stub-rev"))

    def __init__(self, task):
        self.task = task
        self.calls = []

    def __call__(self, inputs, **kwargs):
        self.calls.append((inputs, kwargs))
        if self.task == "image-classification":
            assert isinstance(inputs, Image.Image)
            return [{"label": "tabby cat", "score": 0.7}, {"label": "tiger cat", "score": 0.2},
                    {"label": "lynx", "score": 0.05}]
        # Unsorted on purpose: the worker must rank best first.
        return [{"label": "javascript", "score": 0.1}, {"label": "python", "score": 0.85},
                {"label": "go", "score": 0.05}]


def load_worker(task, monkeypatch):
    stub = types.ModuleType("transformers")
    stub.__version__ = "stub"
    stub.pipeline = lambda task_name, model=None, revision=None: _StubPipeline(task_name)
    monkeypatch.setitem(sys.modules, "transformers", stub)
    monkeypatch.setenv("WORKER_TASK", task)
    monkeypatch.delenv("MODEL_NAME", raising=False)
    spec = importlib.util.spec_from_file_location(f"worker_{task}", WORKER_PY)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def png_base64(color=(200, 120, 40)):
    buffer = io.BytesIO()
    Image.new("RGB", (8, 8), color).save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode()


def test_code_task_reports_capability_and_language(monkeypatch):
    worker = load_worker("code", monkeypatch)
    client = TestClient(worker.app)
    health = client.get("/health").json()
    assert (health["capability"], health["task"]) == ("CODE", "text-classification")
    assert health["model"] == "huggingface/CodeBERTa-language-id"
    body = client.post("/predict", json={"text": "def add(a, b):\n    return a + b"}).json()
    assert (body["label"], body["score"]) == ("python", 0.85)
    assert [r["label"] for r in body["topK"]] == ["python", "javascript", "go"]
    assert worker.classifier.calls[0][1] == {"truncation": True, "top_k": 3}


def test_image_task_accepts_base64_and_data_url(monkeypatch):
    worker = load_worker("image", monkeypatch)
    client = TestClient(worker.app)
    assert client.get("/health").json()["capability"] == "IMAGE"
    for text in (png_base64(), "data:image/png;base64," + png_base64()):
        r = client.post("/predict", json={"text": text})
        assert r.status_code == 200
        body = r.json()
        assert body["label"] == "tabby cat"
        assert len(body["topK"]) == 3
        assert body["model"] == "google/vit-base-patch16-224"


@pytest.mark.parametrize("text", ["not base64 !!", base64.b64encode(b"plain text, not an image").decode()])
def test_image_task_rejects_non_images_concisely(monkeypatch, text):
    worker = load_worker("image", monkeypatch)
    client = TestClient(worker.app)
    before = client.get("/health").json()["predictionsServed"]
    r = client.post("/predict", json={"text": text})
    assert r.status_code == 422
    assert r.json()["detail"][0]["type"] == "invalid_image"
    assert text not in r.text
    assert client.get("/health").json()["predictionsServed"] == before


def test_image_task_allows_large_inputs(monkeypatch):
    worker = load_worker("image", monkeypatch)
    assert worker.MAX_TEXT_CHARS >= 5_000_000


def test_text_task_has_no_topk(monkeypatch):
    worker = load_worker("text", monkeypatch)
    worker.classifier = lambda text, truncation=False: [{"label": "POSITIVE", "score": 0.9}]
    body = TestClient(worker.app).post("/predict", json={"text": "good"}).json()
    assert "topK" not in body and body["task"] == "sentiment-analysis"


def test_unknown_task_fails_at_startup(monkeypatch):
    with pytest.raises(RuntimeError, match="WORKER_TASK"):
        load_worker("audio", monkeypatch)
