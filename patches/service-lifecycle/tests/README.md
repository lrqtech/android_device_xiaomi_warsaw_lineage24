# Active framework service lifecycle fault tests

Run from the Android source root:

```sh
python3 device/xiaomi/warsaw/patches/service-lifecycle/tests/run-tests.py --report /tmp/warsaw-service-lifecycle.json
```

The host JDK compiles complete selected source files with modeled Android APIs.
No Android build, device connection or hardware write occurs. The JSON includes
source hashes and individual outcomes for 28 active-framework cases: thermal
callback (8), biometric registry (10) and camera vibration gain (10).
Removed components have no test cases or source dependencies in this runner.
Passing these tests does not establish Android linkage, HAL ABI or device
behavior. Optional `--baseline DIR --allow-failures` uses saved pre-edit source
under the relative keys in the production map; unchanged dependencies may use
current source when absent from the baseline.
