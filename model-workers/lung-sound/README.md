# Lung-sound research baseline (not deployed)

This isolated experiment audits the user-supplied 336-WAV BP/DP/EP archive and
runs a fixed binary sound-pattern baseline. It does not diagnose disease, serve
an API, change the existing X-ray worker, or combine audio and X-ray scores.

## Run

Use a separate Python 3.12 environment (the measured run used Python 3.12.4 and
the versions in requirements.txt). From this directory:

```powershell
python -m pip install -r requirements.txt
python audit.py 'C:\path\archive.zip'
python -m unittest test_audit.py
python baseline.py 'C:\path\archive.zip'
```

Outputs are local and ignored. Do not commit recordings, filenames containing
clinical metadata, patient-level manifests, spreadsheets, or model artifacts.
The archive remains private; obtain the original source URL and usage terms
before redistribution or deployment. Run only with trusted local datasets.

## Labels and grouping

- Target `neither`: the source's N sound annotation, not proof of healthy lungs.
- Target `adventitious`: annotated crackles, wheezes, or both. Disease labels,
  age, sex, filenames and recording location are not model features.
- Crep is mapped to crackles using the spreadsheet legend. Bronchial and
  unknown annotations are excluded, not silently mapped to normal.
- BPn, DPn and EPn are kept in one numeric group. All three have matching
  filename annotations in this archive. This is a provisional identity rule,
  NOT independently confirmed patient identity. Verify it with the source.
- Whole groups involved in exact decoded-audio duplicates across numeric IDs
  are quarantined. Within-group duplicates are retained only once.
- Exact hashes cannot detect every near-duplicate; source review is still needed.

The archive has only two numeric groups annotated with both sound types. A
four-class train/validation/test evaluation is not credible with that support.
The binary baseline is a starting point, not fulfillment of the four-class goal.

## Evaluation protocol

Versioned deterministic hash ordering reserves approximately 20% of numeric
groups per binary class before feature extraction. All audio is decoded for
integrity and duplicate checking; heldout audio is never used for features,
fitting, validation, prediction or model selection. Changing the grouping rule
or resolving exclusions invalidates this provisional split and its results.

Development uses five-fold StratifiedGroupKFold (seed 2026); groups and PCM
hashes are checked for disjointness in every fold. Per-recording amplitude
normalization and fixed mel/MFCC summaries require no cross-recording fitted
statistics. A fixed random forest is compared to a majority-class dummy on
the same folds. No tuning or selection on heldout outcomes takes place.

Report both recording-level out-of-fold metrics and group-level metrics.
Group results average class scores over available recordings within each
validation group; they are not single-upload accuracy. No calibrated disease
probabilities, external validation, confidence interval or clinical claim is
provided. The final holdout has not been evaluated.

## First observed run: 2026-11-19

Archive SHA256: `32fbe749108f1a3b81bac960716f6497d19c134ef67a5d1b1b13b42c371ffda7`.

- 336 recordings, 112 provisional groups, all mono 4 kHz.
- Four sets of exact PCM duplicates; seven entire groups quarantined for
  cross-group duplicates or ambiguous sound labels. One additional redundant
  recording removed within a retained group: 22 recordings excluded total.
- Development: 251 recordings / 84 groups. Reserved: 63 recordings / 21 groups.
- Recording-level CV accuracy: 75.70%; balanced accuracy: 70.40%; macro F1: 70.89%.
- Adventitious-sound recall: 84.39%; neither-sound recall: 56.41%.
- Majority baseline accuracy: 68.92%; balanced accuracy: 50.00%.
- Group-level CV accuracy: 79.76%; balanced accuracy: 75.80%.
- Three audit regression tests pass, including cross-group conflicting duplicates
  and disjoint deterministic partitions. These tests do not validate medical performance.

No trained deployment artifact is produced. Before integration: confirm dataset
provenance and numeric grouping, obtain adequate data for intended sound classes,
review near-duplicates, select a model using development data, freeze the model,
evaluate the reserved test set once, and add bounded audio upload validation and
research-only metadata to the worker/API/dashboard. Keep the X-ray path separate.
