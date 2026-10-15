# Building the pinned Warsaw snapshot

Use Linux or WSL2 with a case-sensitive Linux filesystem, Git, Git LFS, Repo,
and the Android/LineageOS build prerequisites. Authenticate Git for access to
the three private repositories before synchronization. Keep credentials in
your credential manager, not in the manifest or remote URLs.

## Platform checkout

The standalone manifest pins the platform to the exact base revisions for
the bundled patches and the device/vendor projects to the snapshot tag.

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

`lineage.dependencies` is intentionally empty: private repositories are
declared in the supplied manifest, not presented as official LineageOS
dependencies. A normal unmodified LineageOS checkout by itself is insufficient.

## Userdebug package

```sh
source build/envsetup.sh
source vendor/lineage/vars/aosp_target_release
lunch "lineage_warsaw-${aosp_target_release}-userdebug"
m --no-skip-soong-tests -j32 bacon
```

Adjust the job count to host resources. The explicit test option is required;
do not suppress Soong tests or disable neverallow checks. Output is under
`out/target/product/warsaw/`. Builds use the current local timestamp unless
`BUILD_DATETIME` is explicitly supplied, so package hashes need not match a
previously delivered ZIP.

## Kernel input

See the kernel repository for the pinned Kleaf workspace and source build.
The Android build defaults to `boot/gki-no-kunit` in the vendor repository,
whose manifest records its Image and module hashes. A newly built Image and
all its signed modules must be integrated together with regenerated module
indexes. Never combine a newly signed Image with an old GKI module set.
The archived source-built input is already paired; rebuilding it is optional
when reproducing this Android snapshot.

`WARSAW_USE_OTA_GKI=true` selects the retained stock GKI fallback. It does not
satisfy the no-KUnit source-kernel target and is not the default publication
configuration. Five retained IMS/data libraries require a 4 KiB kernel;
`configs/boot/boot.mk` enforces this prerequisite.

## Scope of the tooling

The device tree contains build integration helpers only. Publication,
desktop delivery, host process management, captured logs and audit runners
are not part of these repositories. No fresh full ROM build is required just
to publish an unchanged build input snapshot.
