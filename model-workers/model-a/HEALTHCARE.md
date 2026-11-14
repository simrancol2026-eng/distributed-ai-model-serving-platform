# Chest X-ray research worker

This dedicated worker runs TorchXRayVision's pretrained `densenet121-res224-all`
DenseNet. It does not use the generic ImageNet classifier for healthcare.
TorchXRayVision explicitly restricts this software to research/software development,
not diagnosis or clinical decisions. No accuracy or clinical validation is claimed
for this integration.

## Run

Use the project's Python environment, install the optional dependency set, then
start a separate process (first startup downloads the upstream checkpoint):

```powershell
python -m pip install -r requirements-healthcare.txt
$env:WORKER_TASK = 'healthcare'
$env:WORKER_ID = 'healthcare-a'
python -m uvicorn worker:app --host 0.0.0.0 --port 8301
```

Configure Spring Boot with `PLATFORM_HEALTHCARE_WORKER_URLS=http://localhost:8301`.
The direct worker request is `POST /predict` with `{"text":"<base64 PNG/JPEG>"}`.
Use explicit `HEALTHCARE`, or `AUTO` with task `chest-xray`, at the unified API.
An unconfigured/unreachable healthcare pool must return unavailable; it must
never substitute the IMAGE pool.

## Input and results

Use de-identified frontal chest-radiograph PNG/JPEG exports, with correct
orientation and conventional grayscale polarity, at most 4096 x 4096 pixels
and 7,000,000 encoded characters. DICOM and CT volumes are not supported.
The worker validates file format and dimensions, not whether an image is
actually a radiograph. Other pictures can produce meaningless scores.

Preprocessing follows upstream: average RGB channels, scale 8-bit intensities to
[-1024, 1024], center crop, resize to 224 pixels, and add channel/batch dimensions.
The model produces 18 independent sigmoid scores. Operating-point remapping is
disabled. `topK` returns all findings ranked by score, while `label`/`score` retain
the existing API's highest-scoring item. This is not a single disease diagnosis;
scores are not calibrated disease probabilities and need not sum to one.
The direct response includes `researchOnly=true` and `scoreMeaning`.

## Provenance and reproducibility

- Adapter dependency: `torchxrayvision==1.4.0`.
- Model: `torchxrayvision/densenet121-res224-all` (all 18 outputs trained).
- Upstream checkpoint: `nih-pc-chex-mimic_ch-google-openi-kaggle-densenet121-d121-tw-lr001-rot45-tr15-sc15-seed0-best.pt`
  from the official TorchXRayVision GitHub `v1` release.
- `/health.revision` and `/predict.version` are the SHA-1 of the actual loaded
  checkpoint bytes, fitting the registry's 40-hex revision field. This is a file
  identity, not a Hugging Face commit or a security guarantee. To require identical
  bytes on another worker, set `MODEL_REVISION` to the observed digest; mismatch
  fails startup. No synthetic checkpoint hash is committed.
- The default model name is enforced. Missing dependencies or failed checkpoint
  downloads fail startup; health is never reported for an unloaded model.

## Verification scope

`tests/test_healthcare.py` exercises route functions, image rejection, version identity,
preprocessing shape/scaling, score ranking, invalid outputs, and revision mismatch
with a stub model. These tests do not validate real model predictions or the HTTP
transport. Four tests passed locally; the existing HTTP tests could not run in the
sandbox because Windows asyncio socketpair creation stalled. A separate real-weight
startup and HTTP inference run is required before claiming a live deployment.

Primary references:

- https://mlmed.org/torchxrayvision/index.html
- https://github.com/mlmed/torchxrayvision/blob/1.4.0/torchxrayvision/models.py
- https://github.com/mlmed/torchxrayvision/blob/1.4.0/torchxrayvision/datasets.py
- https://huggingface.co/torchxrayvision/densenet121-res224-all
- https://pypi.org/project/torchxrayvision/1.4.0/

