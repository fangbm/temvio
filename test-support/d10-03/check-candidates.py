"""Verify immutable candidate evidence. Does not modify or approve visuals."""
from pathlib import Path
import hashlib,json,struct,sys
root=Path(__file__).resolve().parents[2]
folder=root/'docs/tasks/fixtures/d10-03/screenshots'
manifest=json.loads((folder/'capture-manifest.json').read_text(encoding='utf-8'))
for item in manifest['candidates']:
    p=folder/item['file'];data=p.read_bytes()
    assert data[:8]==b'\x89PNG\r\n\x1a\n',p
    assert list(struct.unpack('>II',data[16:24]))==item['bitmapPixels'],p
    assert hashlib.sha256(data).hexdigest()==item['sha256'],p
    if '--compare-generated' in sys.argv:
        assert hashlib.sha256((root/item['generatedFrom']).read_bytes()).hexdigest()==item['sha256'],p
for name,expected in manifest['sourceHashes'].items():
    content=(root/name).read_text(encoding='utf-8-sig').replace('\r\n','\n')
    assert hashlib.sha256(content.encode()).hexdigest()==expected,name
assert manifest['baseSha']=='74e20e555c9037ff62dda95ab67e3e0e455b0aa1'
assert manifest['containsRealSecrets'] is False
print(f"{len(manifest['candidates'])} D10-03 real Compose candidate hashes/dimensions + source hashes PASS; visual maintainer approval remains pending")
