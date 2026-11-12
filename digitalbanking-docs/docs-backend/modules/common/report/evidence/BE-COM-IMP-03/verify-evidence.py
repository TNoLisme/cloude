#!/usr/bin/env python3
"""Read-only verification of IMP-03 evidence, source identity and report links."""
from pathlib import Path
import hashlib
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET

here = Path(__file__).resolve().parent
root = next(p for p in here.parents if (p / 'digitalbanking-backend').is_dir())
report = here.parent.parent / 'BE-COM-IMP-03-verification.md'
text = report.read_text()
manifest = {}
for line in (here / 'source.sha256').read_text().splitlines():
    digest, name = line.split('  ', 1)
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, name
    manifest[name] = digest
with tarfile.open(here / 'source-snapshot.tar.gz') as archive:
    assert set(archive.getnames()) == set(manifest)
    for name, digest in manifest.items():
        assert hashlib.sha256(archive.extractfile(name).read()).hexdigest() == digest, name
expected = (here / 'snapshot.sha256').read_text().split()[0]
assert hashlib.sha256((here / 'source-snapshot.tar.gz').read_bytes()).hexdigest() == expected
print(f'PASS current source and archive hashes: {len(manifest)} files')
for link in re.findall(r'\]\(([^)]+)\)', text):
    assert (report.parent / link.split('#')[0].replace('%20', ' ')).exists(), link
for ac in ['COM-G01', 'COM-G02', 'COM-G03', 'COM-G04'] + [f'COM-IMP-03-AC{i:02}' for i in range(1, 6)]:
    rows = [line for line in text.splitlines() if line.startswith(f'| {ac} |')]
    assert len(rows) == 1 and rows[0].endswith('| Verified |'), ac
print('PASS report links and all 9 AC rows')
totals = [0, 0, 0, 0]
for xml in here.glob('TEST-*.xml'):
    tree = ET.parse(xml).getroot()
    totals = [a + int(tree.get(k, '0')) for a, k in zip(totals, ['tests', 'failures', 'errors', 'skipped'])]
    for password in re.findall(r'Using generated security password: ([^\r\n<]+)', xml.read_text()):
        assert password == '[REDACTED]', xml.name
assert totals == [70, 0, 0, 0], totals
print(f'PASS XML totals tests/failures/errors/skipped={totals}; generated passwords redacted')
result = subprocess.run(['git', 'diff', '--check'], cwd=root, capture_output=True, text=True)
assert result.returncode == 0, result.stdout + result.stderr
print('PASS git diff --check')
print('Evidence verification exit=0; no runtime source changed after E04')
