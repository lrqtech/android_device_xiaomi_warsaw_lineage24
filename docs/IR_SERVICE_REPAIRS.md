# Infrared framework recovery — October 26, 2026

The framework service now reconnects after an IR HAL restart and holds its
existing partial wake lock during each synchronous transmission. These are
source-confirmed lifecycle defects; they are not established causes of the
appliance non-response reported in the supplied diagnostic archive.

## Source repair

`frameworks/base/services/core/java/com/android/server/ConsumerIrService.java`
previously saved its AIDL proxy only at construction. A dead proxy was never
cleared or replaced, so subsequent sends and frequency queries kept using it.
The current code checks cached Binder liveness while holding the existing HAL
lock, then uses the non-blocking `ServiceManager.checkService` to obtain a
replacement. A death racing the liveness check clears the proxy for the next
request. The Warsaw init service is persistent and is restarted by init.

An uncertain transmission is never retried automatically: the HAL could have
already emitted the command before the Binder error. A missing replacement
returns without waiting for registration. `hasIrEmitter()` remains a hardware
capability flag; a temporary service outage does not remove that capability.
Non-death remote errors do not discard a live connection, and service-specific
HAL exceptions keep their existing propagation behavior.

The service also created a wake lock without ever acquiring it. Transmit now
acquires that lock immediately before the synchronous HAL call and releases it
in `finally`, including remote-death and runtime-exception paths. Permission and
pattern checks happen before lock acquisition. The existing HAL mutex continues
to serialize sends. Carrier frequencies, pulse durations, GPIO/SPI setup, native
HAL and kernel bytes, and SELinux policy are unchanged.

The complete source delta is recorded in `patches/platform/frameworks__base.patch`
and its `series.json` SHA-256 entry, preserving the earlier platform changes.

## Host verification

Run from the device tree:

```sh
python3 patches/infrared/tests/run-framework-tests.py --report /tmp/ir-service.json
```

The harness compiles the complete production Java class using the pinned JDK
and host Android API doubles. All 20 cases pass; the pre-repair class fails 10.
Coverage includes death before and during a transaction, send and frequency
query recovery, temporary absence, two successive restarts, no automatic replay,
live-proxy reuse, remote/runtime failures, permission denial, duration bounds,
feature consistency, and serialized sends with balanced wake locks.

All 36 companion patch hash and reverse-application checks pass. Applying the
IR portion to its recorded framework base reproduces the edited source bytes.
This is host state-flow validation, not an Android Binder, suspend, SPI, optical,
or appliance test. Test class files are temporary; no platform build, APK/JAR,
image, OTA, installation archive, or device operation is performed.

## Diagnostic attribution and remaining work

The supplied ZIP hashes to
`ebab53f79280ac8928bbc8a94fb2e3cb06a26b48d39e58e8ac8a5ebd668e825d`.
The previously extracted OEM and phone `consumerir.qcom.so` copies were freshly
hashed and both equal
`8d5fa948c1c50673add56a5b88c2c358085a5f7f27522bb13abc9310246535f1`;
the corresponding `ir-spi.ko` copies both equal
`52c0fd1e4436cd759a1a3ec84192d56de8ea1910f0adc94c7275b070bba61ccc`.
These comparisons do not re-extract or independently certify the original OTA.

The archived successful SPI writes do not establish correct optical output.
They also do not show that a HAL death or suspend caused the reported failure.
Same-command stock/custom waveform, effective clock, pin state and optical
measurements remain necessary when device access is available. The strict
cold-boot, recovery, camera crop/AWB and other documented runtime acceptance
gates also remain open. Source and host work continues under the user's current
instruction to keep platform compilation and packaging stopped.
