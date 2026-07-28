# Contributing

Use Java 11. Before opening or updating a pull request, run:

```bash
bash tools/pre-submit.sh
```

On Windows without Bash, run the component checks and Gradle directly:

```powershell
python tools/reviewer_checks.py
python tools/check_transport_data.py
.\gradlew.bat clean test build --warning-mode all
```

Changes that add a capability must update both of:

1. `PLUGIN_HUB_REVIEW.md`
2. `tools/reviewer_checks.py` or `tools/audit-capabilities.sh`

Do not add reflection, JNI, subprocess execution, synthetic input/game actions,
runtime source/class loading, moving-branch data URLs, or new runtime dependencies.
