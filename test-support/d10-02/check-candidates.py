"""Read-only hash/PNG verification. Never changes or approves review candidates."""
from pathlib import Path
import hashlib
import json
import struct
import sys

root = Path(__file__).resolve().parents[2]
folder = root / "docs/tasks/fixtures/d10-02/screenshots"
manifest = json.loads((folder / "capture-manifest.json").read_text(encoding="utf-8"))
for item in manifest["candidates"]:
    path = folder / item["file"]
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    assert list(struct.unpack(">II", data[16:24])) == item["bitmapPixels"], path
    assert hashlib.sha256(data).hexdigest() == item["sha256"], path
    if "--compare-generated" in sys.argv:
        generated = root / item["generatedFrom"]
        assert hashlib.sha256(generated.read_bytes()).hexdigest() == item["sha256"], generated
print(f"{len(manifest['candidates'])} D10-02 candidate hashes/dimensions PASS; visual approval remains pending")
