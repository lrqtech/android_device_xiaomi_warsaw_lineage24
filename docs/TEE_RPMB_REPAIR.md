# UFS geometry compatibility in tee-supplicant

The restored boot logs contain `Unofficial Geometry desc size, len = 0x69`
followed by `get dev wr sec count failed! use default value: [64]`. This is a
userspace descriptor-format rejection. It is not evidence of a denied UFS open,
a failed RPMB authentication, or lost keys.

The pinned executable opens `/dev/ufs-bsg0` and issues a read-descriptor query.
The geometry helper at `0x7e7c` requests the first `0x57` bytes and reads
`bRPMB_ReadWriteSize` at offset `0x17`. The query routine at `0x7f20` previously
accepted geometry lengths `0x48` and `0x57` only. The captured length `0x69` was
rejected before the helper could return that field. The separate `mult size=16`
log is the RPMB region-size multiplier, not the reliable write sector count.
The captured logs do not contain the raw geometry field value.

The [upstream UFS utility geometry layout](https://github.com/SanDisk-Open-Source/ufs-utils/blob/dev/ufs_cmds.c)
keeps `bRPMB_ReadWriteSize` at `0x17` and includes extended fields through `0x68`.
The checkout's `common/include/ufs/ufs.h` also defines the RPMB field at `0x17`.
The existing read buffer covers this common field; no read size is increased.

## Prepared input repair

`configs/tee/prepare-tee-supplicant.py` accepts only the reviewed input or its
exact repaired output. It changes the 24-byte geometry length case at `0x81b4`
and updates the GNU build ID. The case accepts exactly `0x48`, `0x57` and `0x69`.
Unit and string descriptor parsing, query status and transport checks, retry
handling, the 1..64 sector-count check, and existing error reporting are unchanged.
Unknown lengths still fail. No RPMB read/write command path, TA, key, firmware,
SELinux rule, ELF layout, dependency, or signature validation is changed.
The existing fallback value 64 is retained for actual failures; this change does
not claim that fallback is suitable for every storage device.

Input SHA256: `afeb3881659771428692b259c64249c573370b83bac90f06007e2a278a9ffb62`.
Prepared SHA256: `59069d394272caa2f9f3cd1fc0eedd512a61378131de40981c47dac67ae65fc9`.
The prepared-input manifest pins the repaired executable.

```sh
python3 device/xiaomi/warsaw/configs/tee/prepare-tee-supplicant.py     --check vendor/xiaomi/warsaw/proprietary/odm/bin/tee-supplicant
python3 device/xiaomi/warsaw/configs/tee/tests/test-descriptor-parser.py     --original /path/to/matching/original/tee-supplicant     --prepared vendor/xiaomi/warsaw/proprietary/odm/bin/tee-supplicant
```

The regression requires pyelftools and Unicorn; `--python-deps` can point to an
existing dependency directory. It runs actual ARM instructions with modeled
libc and UFS read-descriptor replies. It checks 3,584 length branches, 32 complete
capability flows across both versions, reproducibility, idempotence, rejected
unknown inputs, and unchanged ELF layout/dependencies. It reproduces the old
0x69 rejection and default substitution. The repaired path returns the modeled
device value, while transport/status failures, unknown lengths and invalid
counts retain their error paths.

The prepared input and its pinned transformation are included in the
October 27 full build. See [build results](BUILD_RESULT_20261027.txt).
