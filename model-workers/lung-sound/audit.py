"""Audit the BP/DP/EP research archive without extracting or executing its contents."""
import argparse
import collections
import hashlib
import io
import json
from pathlib import Path
import re
import wave
import zipfile


def sound_label(value):
    tokens = value.strip().upper().split()
    if tokens == ['N']:
        return 'neither'
    if tokens == ['CREP']:
        return 'crackles'
    if not tokens or not set(tokens) <= {'I', 'E', 'C', 'W'}:
        return None
    c, w = 'C' in tokens, 'W' in tokens
    return 'both' if c and w else 'crackles' if c else 'wheezes' if w else None


def read_audio(raw):
    with wave.open(io.BytesIO(raw)) as w:
        rate, channels, width, frames = (w.getframerate(), w.getnchannels(),
                                         w.getsampwidth(), w.getnframes())
        pcm = w.readframes(frames)
    if channels != 1 or width != 2 or rate != 4000 or frames < rate:
        raise ValueError('Expected mono 16-bit 4 kHz WAV of at least one second')
    if len(pcm) != frames * channels * width:
        raise ValueError('Truncated WAV')
    fingerprint = hashlib.sha256(f'{rate}:{channels}:{width}:'.encode() + pcm).hexdigest()
    return pcm, rate, frames / rate, fingerprint


def assign_partitions(rows):
    """Freeze ~20% of each class's numeric groups; never select by model scores."""
    classes = collections.defaultdict(set)
    for r in rows:
        if not r['excluded']:
            classes[r['target']].add(r['group'])
    heldout = set()
    for label, groups in sorted(classes.items()):
        ordered = sorted(groups, key=lambda g: hashlib.sha256(
            ('nexus-lung-v1:' + g).encode()).hexdigest())
        if len(ordered) < 4:
            raise ValueError(f'Too few groups for {label}')
        heldout.update(ordered[:max(1, round(len(ordered) * .2))])
    for r in rows:
        r['partition'] = ('excluded' if r['excluded'] else
                          'heldout' if r['group'] in heldout else 'development')
    for key in ('group', 'pcm_sha256'):
        a = {r[key] for r in rows if r['partition'] == 'development'}
        b = {r[key] for r in rows if r['partition'] == 'heldout'}
        if a & b:
            raise ValueError(f'Partition leakage: {key}')


def audit(archive):
    rows = []
    with zipfile.ZipFile(archive) as z:
        if z.testzip() is not None:
            raise ValueError('ZIP CRC check failed')
        names = sorted(n for n in z.namelist() if n.lower().endswith('.wav'))
        if len(names) != len(set(names)):
            raise ValueError('Duplicate archive member names')
        for name in names:
            m = re.fullmatch(r'([BDE])P(\d+)_(.+)\.wav', Path(name).name)
            if not m:
                raise ValueError('Unexpected recording naming scheme')
            fields = m[3].split(',')
            if len(fields) != 5:
                raise ValueError('Unexpected annotation fields')
            _, rate, duration, fingerprint = read_audio(z.read(name))
            label = sound_label(fields[1])
            target = None if label is None else 'neither' if label == 'neither' else 'adventitious'
            rows.append(dict(member=name, group=m[2], variant=m[1], label=label, target=target,
                             pcm_sha256=fingerprint, sample_rate=rate,
                             duration_seconds=duration, excluded=[]))
    if not rows:
        raise ValueError('No WAV recordings found')
    groups, fingerprints = collections.defaultdict(list), collections.defaultdict(list)
    for r in rows:
        groups[r['group']].append(r)
        fingerprints[r['pcm_sha256']].append(r)
    quarantined = set()
    for group, members in groups.items():
        if len(members) != 3 or {r['variant'] for r in members} != {'B', 'D', 'E'}:
            quarantined.add(group)
        if len({r['label'] for r in members}) != 1 or any(r['label'] is None for r in members):
            quarantined.add(group)
    duplicates = [rs for rs in fingerprints.values() if len(rs) > 1]
    for rs in duplicates:
        if len({r['group'] for r in rs}) > 1:
            quarantined.update(r['group'] for r in rs)
    for r in rows:
        if r['group'] in quarantined:
            r['excluded'].append('ambiguous_group_or_cross_group_duplicate')
    for rs in duplicates:
        for r in rs[1:]:
            if not r['excluded']:
                r['excluded'].append('within_group_exact_duplicate')
    assign_partitions(rows)
    summary = dict(recordings=len(rows), numeric_groups=len(groups),
                   grouping_status='provisional: numeric BP/DP/EP identity; source confirmation required',
                   duplicate_pcm_sets=len(duplicates), quarantined_groups=len(quarantined),
                   partitions={p: dict(recordings=sum(r['partition'] == p for r in rows),
                       groups=len({r['group'] for r in rows if r['partition'] == p}),
                       labels=dict(collections.Counter(r['label'] for r in rows if r['partition'] == p)))
                       for p in ('development', 'heldout', 'excluded')})
    return dict(archive_sha256=hashlib.sha256(Path(archive).read_bytes()).hexdigest(),
                summary=summary, rows=rows)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('archive', type=Path)
    p.add_argument('--output', type=Path, default=Path('outputs/audit.json'))
    args = p.parse_args()
    result = audit(args.archive)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result['summary'], indent=2))
