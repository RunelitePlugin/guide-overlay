#!/usr/bin/env bash
# One-command local/CI gate for the Guide Overlay Plugin Hub source repository.
set -euo pipefail
cd "$(dirname "$0")/.."

printf '\n== static capability audit ==\n'
bash tools/audit-capabilities.sh

printf '\n== repository safety audit ==\n'
bash tools/audit.sh

printf '\n== reviewer assertions ==\n'
python3 tools/reviewer_checks.py

printf '\n== bundled transport data ==\n'
python3 tools/check_transport_data.py

printf '\n== source syntax ==\n'
bash -n tools/*.sh
python3 - <<'PY'
from pathlib import Path
for path in Path('tools').glob('*.py'):
    compile(path.read_text(encoding='utf-8'), str(path), 'exec')
print('Python syntax PASS')
PY

printf '\n== Java 11 tests and build ==\n'
./gradlew clean test build --warning-mode all

printf '\nPRE-SUBMISSION CHECKS PASS\n'
