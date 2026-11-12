"""Run from repository root; stdlib oracle, no Java/common implementation imports."""
from pathlib import Path
import hashlib
import hmac
import json
import re

folder = Path(__file__).resolve().parent
repo = folder.parents[6]
audit_doc = (repo / 'digitalbanking-docs/docs-common/design/01. audit-traceability.md').read_text()
appendix = audit_doc.split('## Appendix B.')[1]
canonical = re.search(r'### B.2.*?```text\n(.*?)\n```', appendix, re.S).group(1)
previous = re.search(r'### B.3.*?```text\n(.*?)\n```', appendix, re.S).group(1)
# This source fixture has ASCII property names and integer/string values only.
# Python json is NOT used as a general RFC 8785 implementation.
assert json.dumps(json.loads(canonical), ensure_ascii=False, sort_keys=True, separators=(',', ':')) == canonical
transfer = (repo / 'digitalbanking-backend/src/test/resources/crypto/transfer.json').read_text().strip()
results = {
    'audit_previous_hash': previous,
    'audit_sha256': hashlib.sha256((canonical + '|' + previous).encode()).hexdigest(),
    'transfer_sha256': hashlib.sha256(transfer.encode()).hexdigest(),
    'sha256_abc': hashlib.sha256(b'abc').hexdigest(),
    'sha256_leading_zero': hashlib.sha256(b'c4').hexdigest(),
}
for value in ['00123', '123', ' 00123 ', 'thử nghiệm', 'challenge-a:123456', 'challenge-b:123456']:
    results['hmac_' + value] = hmac.new(bytes(range(32)), value.encode(), hashlib.sha256).hexdigest()
expected = json.loads((folder / 'independent-oracles.json').read_text())
assert results == expected
print('Independent hashlib/hmac oracles match all 11 stored results; audit matches source Appendix B.')
