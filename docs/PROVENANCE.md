# Source origins

Reference firmware: Xiaomi warsaw **OS4.0.0.15.XHPCNXM**, Android 17,
build `17OS4.0.260920.220420124.QCPECN.S`.

| Component | Original base |
| --- | --- |
| Device configuration | `66154488ee9bf839bfcdde6122defe1078213b4a` |
| Vendor integration | `263854fe36b8ce5bbfa3e4cbff7499a7d32fba0f` |
| Google Android common kernel | `c4127a25dcf3c8923daffa62632816f6acf0e135` |

The public device and vendor histories include the subsequent integration and
boot repairs. The `warsaw-20261024` tags identify the coordinated publication.
[lineage-24.0-pinned.xml](lineage-24.0-pinned.xml) records the platform revisions;
[series.json](../patches/platform/series.json) records all 33 companion patches,
their base commits, and SHA-256 hashes.

The vendor [difference list](https://github.com/lrqtech/android_vendor_xiaomi_warsaw_lineage24/blob/lineage-24.0/docs/DIFFERENCES.md)
describes prepared files that differ from the OTA. Its payload hash manifest
records the prepared repository contents, not the original OTA contents.

The kernel retains its pinned upstream source with a Warsaw no-KUnit target and
the header layout changes needed by the production KMI. The source archive and
patch hashes are recorded in `warsaw/upstream-source.json` in the kernel tree.

## Licenses

Existing copyright notices, SPDX identifiers, and third-party license files are
retained. See [NOTICE](../NOTICE) for the device tree's license scope. The kernel's
`COPYING` and `LICENSES` apply to its source. Proprietary files remain subject to
their owners' terms.
