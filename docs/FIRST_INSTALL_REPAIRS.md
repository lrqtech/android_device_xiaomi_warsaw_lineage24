# First-install repairs — 2026-10-25

The full Android build was stopped at the user's request. No new OTA, boot image, recovery image, or desktop installation archive was built by this repair pass. Both normal boot and recovery remain configured for permissive SELinux. The user confirmed that the phone cannot currently be connected, so these changes have source and host validation only.

## Evidence

The supplied first-install diagnostic ZIP has SHA-256 `df60f48956f07a1fc75321d514d99f3f17b21ba0a3d4c61f343590b41543b7b1`. All ZIP members were decompressed with CRC verification; all 391 entries in its evidence hash list were independently checked. Included diagnostic programs were not executed.

The review used raw recovery, kernel and logcat records, the current source, and binaries read directly from official OTA images. The official vendor, ODM, vendor_boot, vendor_dlkm and DTBO image hashes were independently compared with the manifest inside the downloaded OS4.0.0.15.XHPCNXM payload. Earlier audit scripts and their PASS records were not used as proof.

## Source changes

| Problem | Repair and scope |
| --- | --- |
| Missing fan entry on the Settings homepage | Added the same fan preference to Expressive and desktop Expressive layouts, ahead of Apps. The legacy layout, fragment allowlist, resources and search provider were checked. |
| Recovery reports an aborted install after update_engine succeeds | Distinguished a clean minadbd EOF after an acknowledged command from a truncated or invalid command. Readable data is drained before accepting HUP. Socket errors, status-write failures, abnormal child exits and a failed installation still fail. The binary status packet is no longer printed as an unterminated string. |
| Recovery creates read-only vendor-labelled firmware directories at runtime | Precreate `vendor/firmware_mnt` and `vendor/firmware` with the existing `BOARD_RECOVERY_IMAGE_PREPARE` build hook. Removed the runtime mkdir commands. No exception to the immutable-partition neverallow was retained. |
| qseecomd opens an unlabelled UFS LUN 0 device | Added the exact `/dev/0:0:0:0` label `vendor_ufs_lun0_bsg_device` and the observed TEE read/write/SG_IO contract. Generic device access remains denied. LUN 0 is not misidentified as the RPMB LUN. |
| UFS block discovery inherits the memory-node label | Added more specific genfs labels for the block subtree and the LUN's uevent file. The discard limit has the existing MMC-host type and a narrow init write rule. The camera's UFS memory-statistics label remains intact. |
| Missing early mount and alias permissions | Added the observed init mount permissions for firmware, DSP and persistent storage, the two block-alias relabel targets, and vold directory enumeration. Recovery filesystem capability and memory-information reads are included. These grants do not provide file-data access to persist or TEE storage. |
| Module initialization denials | Added the observed nice/scheduler contracts and writes to loader-owned key objects. Writes to kernel-labelled trusted keyrings remain denied. Debug-kmsg writes are limited to userdebug/eng. These rules do not change kernel signature verification, protected exports, namespaces or module dependency validation. |
| SLA loaded from the wrong domain | Replaced vendor_init's direct insmod with the existing vendor_modprobe executable/domain. Module dependency and signature checks remain in the loading path. |
| System properties published from vendor initialization | Moved the QuickCamera default from ODM to system_ext, gave it the existing CameraMind property type, and moved the legacy `ro.ril.oem.ctacode` action to a system_ext init script. Added exact build metadata labeling for `ro.product.vendor_dlkm.cert`; the observed ODM camera/fingerprint/touch defaults use their existing property types. |
| Vendor patch level displays Unknown | Reconfirmed `2026-09-01` directly in the verified official vendor image. The source already sets `VENDOR_SECURITY_PATCH` to that value. The supplied installed image predates that correction; no new image has been delivered. The platform patch date is not substituted for the vendor date. |

The two changed vendor init blobs have updated prepared-input hashes. The recovery platform patch was regenerated, checked against its pinned base, and checked in reverse against the working tree without changing the user's Git index.

## Validation

- Five socket-reading unit tests passed.
- Nine listener regression tests passed using the actual source functions with socketpair, epoll and child processes. The pre-fix source fails the three clean-completion cases; the repaired source passes all nine. Invalid, partial and unsupported commands, failed status writes, truncation after success, and unsuccessful installation results are covered. This harness stubs the recovery UI and basic host wrappers; it is not a complete recovery-image execution test.
- Full policy preprocessing and checkpolicy compilation passed for normal userdebug, normal user, and recovery userdebug, with neverallow assertions enabled. The existing upstream warning about an empty `fd ~use` permission set is retained in the logs.
- Thirty-six positive and negative policy contracts passed, including denial of generic TEE device access and kernel-keyring writes by vendor_modprobe.
- Twelve checks against the compiled genfs labels, five device-path label lookups, three property-writer contracts, and file/property context parsing passed.
- All three fan homepage XML resources compiled with aapt2. References, ordering, fragment registration and search support passed static checks.
- All four edited init scripts passed host_init_verifier. The recovery directory-creation hook passed an isolated make test.
- Prepared blob hashes and generated vendor build rules passed their reproduction check.

These checks are targeted source validation. They do not establish that a newly flashed phone boots or that its post-boot labels, storage, radios and peripherals all work.

## Infrared remains unresolved

The official OTA, source inputs and diagnostic copies match byte-for-byte for:

| File | SHA-256 |
| --- | --- |
| `consumerir.qcom.so` | `8d5fa948c1c50673add56a5b88c2c358085a5f7f27522bb13abc9310246535f1` |
| `android.hardware.ir-service.example` | `2d0146e68f000cfdeb3a6474be8d6fbd976864ddcdb5abfbd04f313a57f59241` |
| `ir-spi.ko` | `52c0fd1e4436cd759a1a3ec84192d56de8ea1910f0adc94c7275b070bba61ccc` |

The retained vendor_dlkm image and DTBO also exactly match the official payload. The provided trace shows successful device opens, length ioctls and 52,224-byte writes; it does not capture the outgoing waveform. The HAL and driver disassembly agree on a 1.92 MHz sample clock. The DT's 19.2 MHz maximum SPI frequency is not the infrared carrier frequency.

No evidence currently identifies a safe static change that would restore optical transmission. Successful SPI writes do not establish an emitted waveform, correct carrier timing, LED polarity or appliance decoding. No speculative HAL replacement, frequency patch, binary patch or SELinux expansion was made to claim a fix.

When hardware is available, compare the same remote command's carrier and pulse pattern, the SPI controller's effective clock/pin state, and the optical output. A stock-versus-custom-kernel comparison must be separately planned and authorized; no flashing or temporary boot was performed in this pass.

## TEE, RPMB and remaining AVCs

The supplied logs contain successful TEE RSA creation, characteristics, signing, verification and deletion, and a successful nonce-matched RPMB READ_COUNTER response. The RPMB MAC was not verified. Encrypted QSEE/TZ logs are not readable success evidence. Negative QTEE/ioctl responses remain preserved; they have not been rewritten as successful operations.

The independent raw-log grouping produced 1,031 source/target/class groups, not 1,031 required allow rules. Against the repaired source policy: 29 groups are already allowed; 447 camera-property groups have an enumeration-like open/map/getattr pattern without a read denial; 444 belong to app or diagnostic contexts; 111 require path or call-site review, including original labels and scripts changed by this repair. These buckets are triage categories, not a claim that the remaining AVCs are harmless or fixed.

Bionic's property enumeration calls CheckAccess before opening each context. In permissive mode an access check that would fail in enforcing mode may continue into open/map operations. This explains why a large enumeration-like group must not be converted into blanket access to private system properties. Direct property reads and other camera, graphics, logging and storage AVCs still require individual runtime follow-up. No blanket rules or new dontaudit rules were added. SELinux is not declared ready for enforcing mode.

## Build and delivery state

No full Android or kernel build is running. Automatic build restart remains disabled. The original installation ZIP on the desktop has not been replaced. Rebuilding and re-running final image, OTA, signature, actual-label and device checks remain outstanding after the user requests a new build.

The local diagnostic review contains the raw evidence, source hashes, policy queries and regression logs. This document records source status; it does not announce a new release.

## Camera follow-up after this report

The dedicated camera repair is documented in [CAMERA_AVC_REPAIRS.md](CAMERA_AVC_REPAIRS.md). Its 469-group camera classification supersedes the earlier camera triage counts above. Source policy and DSP integration checks pass; new-image and device validation remain outstanding. Builds remain stopped.
