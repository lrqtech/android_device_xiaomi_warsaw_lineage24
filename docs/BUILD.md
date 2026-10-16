# Building LineageOS for warsaw

Use Linux or WSL2 with a case-sensitive Linux filesystem. Install the
LineageOS build dependencies, Repo and Git LFS, and configure Git access to
the three private repositories.

## Get the sources

The supplied manifest selects the platform revisions required by the patch
series and the `warsaw-20261015` tag for the device and vendor trees.

```sh
git clone --branch lineage-24.0 https://github.com/lrqtech/android_device_xiaomi_warsaw_lineage24.git "$HOME/warsaw-device"
mkdir -p "$HOME/android/lineage-24.0"
cd "$HOME/android/lineage-24.0"
repo init --standalone-manifest --git-lfs -u "file://$HOME/warsaw-device/docs/lineage-24.0-pinned.xml"
repo sync -c -j4
git -C vendor/xiaomi/warsaw lfs pull
git -C vendor/xiaomi/warsaw lfs fsck
python3 device/xiaomi/warsaw/apply-platform-patches.py --source "$PWD" --check
python3 device/xiaomi/warsaw/apply-platform-patches.py --source "$PWD" --apply
```

The patch helper checks every affected project before applying changes.
`lineage.dependencies` is empty because the manifest already includes the
private repositories. The platform patches are required; an unmodified
LineageOS checkout is not sufficient.

## Build userdebug

```sh
source build/envsetup.sh
source vendor/lineage/vars/aosp_target_release
lunch "lineage_warsaw-${aosp_target_release}-userdebug"
m --no-skip-soong-tests -j32 bacon
```

Adjust `-j32` for the build host. Keep `--no-skip-soong-tests` and neverallow
checks enabled. The output directory is `out/target/product/warsaw/`.

`BUILD_DATETIME` defaults to the time of the build. Set it explicitly if
reproducing an earlier build; a different timestamp changes the package hash.

## Kernel

The default input is `vendor/xiaomi/warsaw/boot/gki-no-kunit`. It contains the
kernel Image, matching modules and their hash manifest. To rebuild it, follow
the [kernel instructions](https://github.com/lrqtech/android_kernel_xiaomi_warsaw_lineage24#build)
in a separate Kleaf workspace.

Replace the Image and all 78 signed GKI modules together, then regenerate
the module indexes. Mixing modules from different kernel builds can prevent
boot. The supplied set can be used directly when building Android.

`WARSAW_USE_OTA_GKI=true` selects the stock GKI fallback. This is not the
default and does not meet the no-KUnit configuration. Five IMS/data libraries
require 4 KiB pages; `configs/boot/boot.mk` checks the kernel page size.
