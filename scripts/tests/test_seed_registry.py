"""Tests for scripts/seed_registry.py against an in-memory fake of the registry API.

The fake mimics the documented API responses only; it is not the Spring backend.
Run: python -m pytest scripts/tests/test_seed_registry.py
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import seed_registry  # noqa: E402

REV = "714eb0fa89d2f80546fda750413ed43d93601a13"
OTHER = "0123456789abcdef0123456789abcdef01234567"
ART = "distilbert-base-uncased-finetuned-sst-2-english"


class FakeApi:
    def __init__(self, revision=REV, inference_status=200):
        self.revision, self.inference_status = revision, inference_status
        self.models, self.versions, self.fail_model_create = {}, {}, None

    def __call__(self, method, path, body=None):
        if path == "/api/inference":
            if self.inference_status != 200:
                return self.inference_status, {"status": "error", "error": "WORKER_UNAVAILABLE"}
            return 200, {"status": "success", "worker": "model-a", "model": ART, "version": self.revision,
                         "task": "sentiment-analysis"}
        parts = path.strip("/").split("/")  # api models [id [versions [v]]]
        if method == "POST" and len(parts) == 2:
            if self.fail_model_create:
                return self.fail_model_create, {"status": "error", "error": "INTERNAL"}
            if body["id"] in self.models:
                return 409, {"error": "MODEL_ALREADY_EXISTS"}
            self.models[body["id"]] = dict(body)
            return 201, dict(body)
        if method == "GET" and len(parts) == 3:
            return (200, self.models[parts[2]]) if parts[2] in self.models else (404, {"error": "MODEL_NOT_FOUND"})
        if method == "POST" and len(parts) == 4:
            vs = self.versions.setdefault(parts[2], {})
            if body["version"] in vs:
                return 409, {"error": "VERSION_ALREADY_EXISTS"}
            if any(v["artifact"] == body["artifact"] and v["artifactRevision"] == body["artifactRevision"]
                   for v in vs.values()):
                return 409, {"error": "DUPLICATE_ARTIFACT", "message": "alias"}
            vs[body["version"]] = dict(body)
            return 201, dict(body)
        if method == "GET" and len(parts) == 5:
            v = self.versions.get(parts[2], {}).get(parts[4])
            return (200, v) if v else (404, {"error": "VERSION_NOT_FOUND"})
        return 500, {}


def run(api):
    lines = []
    return seed_registry.seed(api, lines.append), lines


def test_fresh_registry_creates_model_and_version():
    api = FakeApi()
    code, _ = run(api)
    assert code == 0
    assert api.versions["sentiment-distilbert"]["v1"]["artifactRevision"] == REV


def test_exact_rerun_succeeds_without_changes():
    api = FakeApi()
    run(api)
    before = repr((api.models, api.versions))
    code, lines = run(api)
    assert code == 0
    assert repr((api.models, api.versions)) == before
    assert any("already registered with the same artifact and revision" in l for l in lines)


def test_existing_v1_with_different_revision_fails_and_is_not_overwritten():
    api = FakeApi(revision=OTHER)
    run(api)  # v1 -> OTHER
    api.revision = REV  # the worker now serves a different revision
    code, lines = run(api)
    assert code == 1
    assert api.versions["sentiment-distilbert"]["v1"]["artifactRevision"] == OTHER
    assert any("Not overwritten" in l for l in lines)


def test_existing_model_with_different_values_fails():
    api = FakeApi()
    api.models["sentiment-distilbert"] = {"id": "sentiment-distilbert", "capability": "IMAGE", "task": "x"}
    code, _ = run(api)
    assert code == 1
    assert "sentiment-distilbert" not in api.versions


def test_same_revision_under_another_label_fails():
    api = FakeApi()
    run(api)
    api.versions["sentiment-distilbert"]["v0"] = api.versions["sentiment-distilbert"].pop("v1")
    code, _ = run(api)
    assert code == 1


def test_unexpected_model_create_error_fails():
    api = FakeApi()
    api.fail_model_create = 500
    code, _ = run(api)
    assert code == 1
    assert not api.versions


def test_abbreviated_or_missing_revision_registers_nothing():
    for rev in ("714eb0f", None):
        api = FakeApi(revision=rev)
        code, _ = run(api)
        assert code == 1
        assert not api.models and not api.versions


def test_inference_failure_registers_nothing():
    api = FakeApi(inference_status=503)
    code, _ = run(api)
    assert code == 1
    assert not api.models
