# Source provenance

The reference firmware is Xiaomi Warsaw OS4.0.0.15.XHPCNXM, Android 17,
build `17OS4.0.260920.220420124.QCPECN.S`.

The device configuration derives from the owner's Warsaw tree at
`66154488ee9bf839bfcdde6122defe1078213b4a` plus the current integration changes.
The proprietary tree derives from the owner's vendor tree at
`263854fe36b8ce5bbfa3e4cbff7499a7d32fba0f` plus documented OTA extraction and
integration changes. These identifiers describe provenance only; historical
Git objects are not imported into the new repositories.

`docs/lineage-24.0-pinned.xml` records each platform base revision.
`patches/stability-20261012/series.json` lists the 28 required platform patches,
their base revisions and SHA-256 digests. The directory name is historical;
the snapshot includes the subsequent 2026-10-15 runtime integration changes.

The kernel repository retains full upstream Linux source, COPYING, LICENSES
and file-level notices. Its source origin is Google's Android common kernel
commit `c4127a25dcf3c8923daffa62632816f6acf0e135`, with a narrow KMI layout
compatibility patch and a Warsaw no-KUnit build target.

The publication excludes old Git metadata, build output, captured device logs,
credentials, local audit runners and unused optional consumer UI modules.
Existing source licenses and copyright notices remain in place.
