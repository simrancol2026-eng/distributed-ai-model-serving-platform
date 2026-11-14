"""TorchXRayVision adapter for research chest radiographs, never clinical diagnosis.

The only accepted model is the published all-dataset DenseNet checkpoint. Its
actual weight-file SHA-1 is the version identity used by the existing registry
(40 hexadecimal characters). This digest identifies bytes, not clinical validity.
"""

import hashlib


MODEL = "torchxrayvision/densenet121-res224-all"
WEIGHTS = "densenet121-res224-all"


class ChestXrayClassifier:
    def __init__(self, model_name=MODEL, expected_revision=None):
        if model_name != MODEL:
            raise RuntimeError(f"Healthcare worker requires MODEL_NAME={MODEL}")
        # Optional dependencies are loaded only for WORKER_TASK=healthcare.
        import numpy as np
        import torch
        import torchxrayvision as xrv

        self.np, self.torch, self.xrv = np, torch, xrv
        self.model = xrv.models.DenseNet(weights=WEIGHTS)
        # Use raw sigmoid scores: disable the library's default operating-point
        # remapping. These are independent scores, not a softmax distribution.
        self.model.op_threshs = None
        self.model.apply_sigmoid = True
        self.model.eval()
        digest = hashlib.sha1()
        with open(self.model.weights_filename_local, "rb") as weights_file:
            for chunk in iter(lambda: weights_file.read(1024 * 1024), b""):
                digest.update(chunk)
        self.revision = digest.hexdigest()
        if expected_revision and expected_revision.lower() != self.revision:
            raise RuntimeError("Healthcare weight digest does not match MODEL_REVISION")

    def __call__(self, image, top_k=18):
        # Follow the upstream preprocessing: channel mean, [-1024, 1024],
        # center crop and resize to the checkpoint's 224-pixel resolution.
        pixels = self.np.asarray(image, dtype=self.np.float32).mean(axis=2)
        pixels = self.xrv.datasets.normalize(pixels, 255)
        pixels = self.xrv.datasets.XRayCenterCrop()(pixels[None, ...])
        pixels = self.xrv.datasets.XRayResizer(224)(pixels)
        tensor = self.torch.from_numpy(pixels).unsqueeze(0)
        with self.torch.inference_mode():
            scores = self.model(tensor)[0].cpu().numpy()
        if len(scores) != len(self.model.pathologies) or not self.np.isfinite(scores).all():
            raise RuntimeError("Chest X-ray model returned invalid scores")
        ranked = [{"label": label, "score": float(score)}
                  for label, score in zip(self.model.pathologies, scores)]
        if any(not 0 <= item["score"] <= 1 for item in ranked):
            raise RuntimeError("Chest X-ray model returned scores outside [0, 1]")
        return sorted(ranked, key=lambda item: item["score"], reverse=True)[:top_k]

