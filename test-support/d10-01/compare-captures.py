"""Explicit comparison for the SAME renderer/platform fixture set. Never updates baselines."""
import argparse, hashlib, json
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--reference', type=Path, required=True)
parser.add_argument('--candidate', type=Path, required=True)
parser.add_argument('--platform', choices=['desktop', 'android'], required=True)
args = parser.parse_args()
metadata = json.loads((args.reference / 'capture-manifest.json').read_text(encoding='utf-8'))
failed = []
captures = {name: digest for name, digest in metadata['sha256'].items() if name.startswith(args.platform + '-')}
if not captures:
    raise SystemExit('No reference captures for selected platform.')
for name, expected in captures.items():
    file = args.candidate / name
    if not file.exists() or hashlib.sha256(file.read_bytes()).hexdigest() != expected:
        failed.append(name)
if failed:
    raise SystemExit('Different/missing candidates (review, never auto-update): ' + ', '.join(failed))
print(f"Identical: {len(captures)} capture candidates awaiting human review. Renderer: {metadata['renderer'][args.platform]}")
