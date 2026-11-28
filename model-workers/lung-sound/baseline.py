"""Development-only grouped cross-validation. Never opens heldout audio for features."""
import argparse
import json
from pathlib import Path
import zipfile
import numpy as np
import scipy
from scipy.signal import spectrogram
from scipy.fft import dct
import sklearn
from sklearn.dummy import DummyClassifier
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, balanced_accuracy_score, classification_report, confusion_matrix
from sklearn.model_selection import StratifiedGroupKFold
from audit import audit, read_audio

LABELS = ['neither', 'adventitious']


def features(raw):
    pcm, rate, _, _ = read_audio(raw)
    x = np.frombuffer(pcm, dtype='<i2').astype(np.float64) / 32768
    x -= x.mean()
    rms = np.sqrt(np.mean(x * x))
    if rms < 1e-8:
        raise ValueError('Silent recording')
    x /= rms
    f, _, power = spectrogram(x, fs=rate, window='hann', nperseg=256, noverlap=128)
    # Fixed mel transform: no statistics fitted to validation or heldout recordings.
    hz_to_mel = lambda h: 2595 * np.log10(1 + h / 700)
    edges = 700 * (10 ** (np.linspace(hz_to_mel(50), hz_to_mel(1900), 34) / 2595) - 1)
    filters = np.array([np.maximum(0, np.minimum((f - a) / (b - a), (c - f) / (c - b)))
                        for a, b, c in zip(edges, edges[1:], edges[2:])])
    logmel = np.log(np.maximum(filters @ power, 1e-12))
    mfcc = dct(logmel, type=2, axis=0, norm='ortho')[1:14]
    data = np.vstack([logmel, mfcc])
    return np.concatenate([data.mean(axis=1), data.std(axis=1),
                           np.quantile(data, .1, axis=1), np.quantile(data, .9, axis=1)])


def metrics(y, pred):
    return dict(accuracy=accuracy_score(y, pred), balanced_accuracy=balanced_accuracy_score(y, pred),
                classification_report=classification_report(y, pred, labels=LABELS, output_dict=True,
                                                            zero_division=0),
                confusion_matrix=confusion_matrix(y, pred, labels=LABELS).tolist(), labels=LABELS)


def run(archive):
    manifest = audit(archive)
    rows = [r for r in manifest['rows'] if r['partition'] == 'development']
    with zipfile.ZipFile(archive) as z:
        X = np.array([features(z.read(r['member'])) for r in rows])
    y = np.array([r['target'] for r in rows]); groups = np.array([r['group'] for r in rows])
    if not np.isfinite(X).all():
        raise ValueError('Non-finite features')
    folds = min(5, min(len(set(groups[y == c])) for c in LABELS))
    if folds < 2:
        raise ValueError('Insufficient development groups for cross-validation')
    predictions = np.empty(len(y), dtype=object); dummy = np.empty(len(y), dtype=object)
    probabilities = np.zeros((len(y), len(LABELS))); seen = np.zeros(len(y), dtype=int)
    for train, valid in StratifiedGroupKFold(folds, shuffle=True, random_state=2026).split(X, y, groups):
        assert not set(groups[train]) & set(groups[valid])
        assert not {rows[i]['pcm_sha256'] for i in train} & {rows[i]['pcm_sha256'] for i in valid}
        model = RandomForestClassifier(n_estimators=200, max_depth=8, min_samples_leaf=2,
                                       class_weight='balanced', random_state=2026, n_jobs=2)
        model.fit(X[train], y[train]); predictions[valid] = model.predict(X[valid])
        for j, label in enumerate(model.classes_):
            probabilities[valid, LABELS.index(label)] = model.predict_proba(X[valid])[:, j]
        reference = DummyClassifier(strategy='most_frequent').fit(X[train], y[train])
        dummy[valid] = reference.predict(X[valid]); seen[valid] += 1
    assert (seen == 1).all()
    unique = sorted(set(groups))
    group_y = [y[np.where(groups == g)[0][0]] for g in unique]
    group_pred = [LABELS[probabilities[groups == g].mean(axis=0).argmax()] for g in unique]
    return dict(archive_sha256=manifest['archive_sha256'], audit=manifest['summary'],
                status='exploratory development CV, not clinical or independent test accuracy',
                grouping='provisional numeric identity, needs source confirmation', folds=folds,
                versions=dict(numpy=np.__version__, scipy=scipy.__version__, sklearn=sklearn.__version__),
                recording_oof=metrics(y, predictions), group_oof=metrics(group_y, group_pred),
                majority_baseline=metrics(y, dummy), heldout_evaluated=False,
                feature_count=X.shape[1])


if __name__ == '__main__':
    p = argparse.ArgumentParser(); p.add_argument('archive', type=Path)
    p.add_argument('--output', type=Path, default=Path('outputs/baseline.json'))
    args = p.parse_args(); result = run(args.archive)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))
