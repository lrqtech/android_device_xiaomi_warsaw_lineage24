# Source origins

The reference firmware is Xiaomi warsaw **OS4.0.0.15.XHPCNXM**, Android 17,
build `17OS4.0.260920.220420124.QCPECN.S`.

| Component | Base |
| --- | --- |
| Device tree | lrqtech's warsaw tree, `66154488ee9bf839bfcdde6122defe1078213b4a` |
| Vendor tree | lrqtech's warsaw vendor tree, `263854fe36b8ce5bbfa3e4cbff7499a7d32fba0f` |
| GKI kernel | Google Android common, `c4127a25dcf3c8923daffa62632816f6acf0e135` |

The device and vendor trees include subsequent integration changes and OTA
files. See the vendor [difference list](https://github.com/lrqtech/android_vendor_xiaomi_warsaw_lineage24/blob/lineage-24.0/docs/DIFFERENCES.md)
for changes to the original files.

[lineage-24.0-pinned.xml](lineage-24.0-pinned.xml) records the platform
revisions. [series.json](../patches/stability-20261012/series.json) lists the
28 platform patches, base revisions and SHA-256 hashes. The series includes
changes through 2026-10-15.

The kernel adds a Warsaw build target with KUnit disabled and a small header
patch to preserve the production KMI layout. Its `warsaw/upstream-source.json`
records the upstream archive and patch hashes.

## Licenses

Original copyright notices, SPDX identifiers and license files are retained.
The kernel's `COPYING` and `LICENSES` directory apply to its source.
Proprietary files remain subject to their owners' terms.
