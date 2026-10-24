# Building LineageOS for warsaw

Use Linux or WSL2 with a case-sensitive Linux filesystem, the LineageOS build
dependencies, Repo, Python 3.11 or newer, and Git LFS.

## Sync the source set

The manifest pins the platform revisions and selects `warsaw-20261024` for the
device and vendor trees. All three Warsaw repositories are public.

```sh
git clone --branch warsaw-20261024 https://github.com/lrqtech/android_device_xiaomi_warsaw_lineage24.git "$HOME/warsaw-device"
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
`out/target/product/warsaw/`. The default userdebug and eng configurations keep
normal boot and recovery in SELinux permissive mode.

The October 24 clean build used `BUILD_DATETIME=1792804649`. Setting that value
alone does not guarantee identical package bytes: source contents, signing keys,
toolchains, and packaging inputs must also match. The published cleanup was
checked with `m nothing` after that full build; no replacement OTA is attached
to this source update.

## Kernel inputs

The default kernel is `vendor/xiaomi/warsaw/boot/gki-no-kunit/Image`, paired
with its 78 signed GKI modules. Follow the
[kernel build instructions](https://github.com/lrqtech/android_kernel_xiaomi_warsaw_lineage24#build)
in a separate Kleaf workspace to rebuild it.

Replace the Image and complete module set together, regenerate module indexes,
and revalidate the OEM module ABI. The build requires 4 KiB pages for five
retained IMS/data libraries. `WARSAW_USE_OTA_GKI=true` selects the stock fallback;
that fallback is outside the no-KUnit configuration.
