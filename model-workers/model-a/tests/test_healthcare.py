"""Route-function and adapter checks; model weights are STUBBED, not clinical validation."""

import hashlib
import sys
import types

import numpy as np
import pytest

from test_specialist_tasks import load_worker, png_base64


def test_healthcare_contract_and_invalid_upload(monkeypatch):
    stub = types.ModuleType("healthcare")

    class StubChest:
        revision = "a" * 40

        def __init__(self, *args):
            self.calls = []

        def __call__(self, image, top_k):
            self.calls.append(image)
            return [{"label": "Pneumonia", "score": 0.2}, {"label": "Effusion", "score": 0.4}]

    stub.ChestXrayClassifier = StubChest
    monkeypatch.setitem(sys.modules, "healthcare", stub)
    worker = load_worker("healthcare", monkeypatch)
    health = worker.health()
    assert health["capability"] == "HEALTHCARE"
    assert health["task"] == "chest-xray"
    assert health["model"] == "torchxrayvision/densenet121-res224-all"
    body = worker.predict(worker.PredictRequest(text=png_base64()))
    assert body["researchOnly"] is True
    assert "not calibrated" in body["scoreMeaning"]
    assert body["task"] == "chest-xray" and body["version"] == health["revision"]
    assert body["label"] == "Effusion" and len(body["topK"]) == 2
    invalid = worker.predict(worker.PredictRequest(text="not an image"))
    assert invalid.status_code == 422
    assert len(worker.classifier.calls) == 1
    assert worker.health()["predictionsServed"] == 1


def test_adapter_rejects_other_model_before_loading_dependencies():
    from healthcare import ChestXrayClassifier
    with pytest.raises(RuntimeError, match="MODEL_NAME"):
        ChestXrayClassifier("google/vit-base-patch16-224")


def test_adapter_pins_actual_weights_and_disables_threshold_remap(monkeypatch, tmp_path):
    from healthcare import ChestXrayClassifier
    weights = tmp_path / "weights.pt"
    weights.write_bytes(b"stub checkpoint, not real model weights")
    model = types.SimpleNamespace(weights_filename_local=str(weights), eval=lambda: None)
    xrv = types.ModuleType("torchxrayvision")
    xrv.models = types.SimpleNamespace(DenseNet=lambda **kwargs: model)
    monkeypatch.setitem(sys.modules, "torchxrayvision", xrv)
    monkeypatch.setitem(sys.modules, "torch", types.ModuleType("torch"))
    classifier = ChestXrayClassifier()
    assert classifier.revision == hashlib.sha1(weights.read_bytes()).hexdigest()
    assert model.op_threshs is None and model.apply_sigmoid is True
    with pytest.raises(RuntimeError, match="digest"):
        ChestXrayClassifier(expected_revision="b" * 40)


def test_adapter_preprocessing_and_score_validation(monkeypatch):
    from healthcare import ChestXrayClassifier
    from PIL import Image
    from contextlib import nullcontext

    observed = {}

    def normalize(pixels, maxval):
        observed["pixels"] = pixels.copy()
        assert maxval == 255
        return (pixels / 255 * 2 - 1) * 1024

    def crop(pixels):
        assert pixels.shape == (1, 3, 5)
        return pixels[:, :, 1:4]

    def resize(pixels):
        assert pixels.shape == (1, 3, 3)
        return np.full((1, 224, 224), pixels[0, 0, 0], dtype=np.float32)

    class Tensor:
        def __init__(self, array): self.array = array
        def unsqueeze(self, axis): return Tensor(np.expand_dims(self.array, axis))
        def __getitem__(self, key): return Tensor(self.array[key])
        def cpu(self): return self
        def numpy(self): return self.array

    class Model:
        pathologies = ["Effusion", "Pneumonia"]
        scores = [0.1, 0.8]
        def __call__(self, tensor):
            assert tensor.array.shape == (1, 1, 224, 224)
            assert tensor.array.dtype == np.float32
            assert np.allclose(tensor.array, (20 / 255 * 2 - 1) * 1024)
            return Tensor(np.array([self.scores]))

    classifier = ChestXrayClassifier.__new__(ChestXrayClassifier)
    classifier.np = np
    classifier.torch = types.SimpleNamespace(from_numpy=Tensor, inference_mode=nullcontext)
    classifier.xrv = types.SimpleNamespace(datasets=types.SimpleNamespace(
        normalize=normalize, XRayCenterCrop=lambda: crop, XRayResizer=lambda size: resize))
    classifier.model = Model()
    image = Image.new("RGB", (5, 3), (10, 20, 30))
    assert classifier(image) == [{"label": "Pneumonia", "score": 0.8}, {"label": "Effusion", "score": 0.1}]
    assert (observed["pixels"] == 20).all()
    classifier.model.scores = [float("nan"), 0.5]
    with pytest.raises(RuntimeError, match="invalid scores"):
        classifier(image)

