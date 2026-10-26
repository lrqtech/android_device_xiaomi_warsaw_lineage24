# Active framework service lifecycle fault tests

Run from the Android source root:

```sh
python3 device/xiaomi/warsaw/patches/service-lifecycle/tests/run-tests.py --report /tmp/warsaw-service-lifecycle.json
```

The host JDK compiles the selected source files with modeled Android APIs.
The JSON records source hashes and outcomes for 28 cases: thermal callback (8),
biometric registry (10) and camera vibration gain (10). Removed components have
no dependencies in this runner. Optional `--baseline DIR --allow-failures` uses
saved pre-edit source under the production-map keys; unchanged dependencies use
current source when absent from the baseline.
