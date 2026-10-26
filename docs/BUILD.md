# Building LineageOS for warsaw

Use Linux or WSL2 with a case-sensitive Linux filesystem, the LineageOS build
dependencies, Repo, Python 3.11 or newer, and Git LFS.

## Sync the source set

The manifest pins the platform revisions and selects `warsaw-20261027` for the
device and vendor trees. All three Warsaw repositories are public.

```sh
git clone --branch warsaw-20261027 https://github.com/lrqtech/android_device_xiaomi_warsaw_lineage24.git "$HOME/warsaw-device"
mkdir -p "$HOME/android/lineage-warsaw"
cd "$HOME/android/lineage-warsaw"
repo init --standalone-manifest --git-lfs -u "file://$HOME/warsaw-device/docs/lineage-24.0-pinned.xml"
repo sync -c -j4
git -C vendor/xiaomi/warsaw lfs pull
git -C vendor/xiaomi/warsaw lfs fsck
python3 vendor/xiaomi/warsaw/verify-files.py
python3 device/xiaomi/warsaw/apply-platform-patches.py --source "$PWD" --check
python3 device/xiaomi/warsaw/apply-platform-patches.py --source "$PWD" --apply
python3 device/xiaomi/warsaw/patches/check-patches.py
python3 device/xiaomi/warsaw/setup-makefiles.py --check
```

The patch helper checks hashes, base revisions, and every affected worktree
before applying changes. Already applied patches are left in place. Preserve
the error output if a check fails; do not bypass it or apply the series twice.

`lineage.dependencies` is empty because the manifest supplies the required
projects. The platform patches are part of the source set.

## Build userdebug

```sh
source build/envsetup.sh
lunch lineage_warsaw-cp2a-userdebug
m --no-skip-soong-tests -j24 bacon
```

Adjust the job count to the host's memory and CPU capacity. Keep neverallow,
dependency, and Soong tests enabled. Output is written to
`out/target/product/warsaw/`. Normal boot and recovery use the platform default
SELinux enforcing mode in every build variant.

The October 27 clean build used the `warsaw-20261027` source set and completed
with Soong tests, neverallow, Treble, VINTF and ELF dependency checks enabled.
The OTA contains 45 partitions; the first-install archive pairs it with seven
images from the final target-files. See [build results](BUILD_RESULT_20261027.txt)
for timestamps, signatures and SHA256 values. Reproducing package bytes requires
the same source, toolchain, keys, timestamps and packaging inputs.

## Kernel inputs

The default kernel is `vendor/xiaomi/warsaw/boot/gki-no-kunit/Image`, paired
with its 78 signed GKI modules. Follow the
[kernel build instructions](https://github.com/lrqtech/android_kernel_xiaomi_warsaw_lineage24#build)
in a separate Kleaf workspace to rebuild it.

Replace the Image and complete module set together, regenerate module indexes,
and revalidate the OEM module ABI. The build requires 4 KiB pages for five
retained IMS/data libraries. The unused OEM kernel/image fallback has been
removed; the paired source-built GKI is the only device-selected input set.
