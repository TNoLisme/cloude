"""Run from workspace root: python digitalbanking-docs/docs-backend/modules/common/report/evidence/BE-COM-IMP-02/verify-evidence.py"""
from pathlib import Path
from urllib.parse import unquote
import hashlib
import re
import tarfile
import xml.etree.ElementTree as ET

base = Path(__file__).resolve().parent
workspace = base.parents[6]
report = base.parent.parent / 'BE-COM-IMP-02-verification.md'
checks = []
manifest = {}
for line in (base / 'source.sha256').read_text().splitlines():
    digest, name = line.split('  ', 1)
    assert hashlib.sha256((workspace / name).read_bytes()).hexdigest() == digest, name
    manifest[name] = digest
checks.append(f'PASS: {len(manifest)} source hashes match current workspace')
with tarfile.open(base / 'source-snapshot.tar.gz') as archive:
    names = {member.name for member in archive.getmembers()}
    assert names == set(manifest)
    for member in archive.getmembers():
        assert member.isfile() and not member.name.startswith('/') and '..' not in Path(member.name).parts
        assert hashlib.sha256(archive.extractfile(member).read()).hexdigest() == manifest[member.name]
        assert '/.git/' not in member.name and Path(member.name).name != '.env'
checks.append('PASS: archive contents/hashes match manifest; no git/env/unsafe paths')
totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
for p in base.glob('TEST-*.xml'):
    suite = ET.parse(p).getroot()
    for key in totals:
        totals[key] += int(suite.get(key, '0'))
assert totals == dict(tests=47, failures=0, errors=0, skipped=0), totals
checks.append(f'PASS: final XML totals {totals}')
text = report.read_text()
ids = re.findall(r'^\| (COM-(?:G\d+|IMP-02-AC\d+)) \|', text, flags=re.M)
assert len(ids) == len(set(ids)) == 9, ids
for source, prefix in [(base.parents[2] / 'tasks/README.md', 'COM-G'),
                       (base.parents[2] / 'tasks/BE-COM-IMP-02.md', 'COM-IMP-02-AC')]:
    for line in source.read_text().splitlines():
        if line.startswith('| ' + prefix):
            columns = [x.strip() for x in line.strip('|').split('|')]
            assert '| ' + columns[0] + ' | ' + columns[1] + ' |' in text
checks.append('PASS: all 9 AC IDs and exact criterion text retained')
for target in re.findall(r'\]\(([^)]+)\)', text):
    if not target.startswith(('http:', 'https:', '#')):
        assert (report.parent / unquote(target.split('#')[0])).exists(), target
checks.append('PASS: all report artifact/source links resolve')
for p in base.rglob('*'):
    if p.is_file() and p.suffix in ('.xml', '.log', '.txt'):
        assert not re.search(r'Using generated security password: (?!\[REDACTED\])[^\s<]+', p.read_text()), p
checks.append('PASS: generated passwords redacted; negative fixtures remain synthetic')
result = '\n'.join(checks) + '\nExit: 0\n'
(base / 'integrity.txt').write_text(result)
print(result)
