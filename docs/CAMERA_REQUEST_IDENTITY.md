# Per-request camera client identity

The supplied camera log reports missing `com.xiaomi.sessionparams.clientName`
in FaceAlignPreview, SkinBeautifierPreview and LdcPreviewMeta. Inspection of
these selected plugin inputs confirms that they call MiMetadata::find on the
metadata passed to isEnabled(MiaParams), rather than a CameraService session
parameter object. The existing framework hook only stamped endConfigure.

CameraDeviceClient now sets the authenticated package on the logical metadata
of every API2 capture request, including repeating, burst, reprocess and shared
primary-client submissions, before permission checks and HAL submission. The
existing provider-scoped helper rejects mismatched provider IDs, unknown tables
and wrong tag types. An explicitly empty external-provider table remains valid.
Physical metadata is left within its advertised physical request key set; app
metadata is copied and original controls are preserved. Errors stop submission.

The change is included in the recorded frameworks/av companion patch and its
hash. Host tests compile the actual submission block and production helper with
API doubles: 12 cases pass, versus 10 failures before the insertion. The existing
21 session helper tests also pass. Patch reverse checks and whitespace checks
pass.

The helper propagates authenticated framework identity while preserving
provider ownership, crop bounds, AWB limits and error returns.

## Legacy capture paths

API1 uses StreamingProcessor and CaptureSequencer rather than
CameraDeviceClient::submitRequestList. Preview, recording and still request
updates now set the same authenticated identity with provider-specific tag
validation. ZslProcessor also restores it on the copied result metadata before
queueing an input buffer; vendor results need not retain a request-only tag.
Expired streaming clients return INVALID_OPERATION. Other devices keep their
existing behavior, and an external provider with an empty tag table is supported.

The related startRecordingL code discarded updateRecordingRequest's return value
and checked an older status instead. It now propagates the actual preparation
failure before stream changes. This is a source-confirmed error-path defect;
the supplied log does not establish that it caused the observed camera failure.

The additional host regression compiles the four actual identity blocks and the
recording startup block with API doubles. All 36 cases pass; the previous source
fails 26. Combined with the 12 API2 request cases and 21 session-helper cases,
69 cases pass.

## October 26: allocation failures before identity submission

The identity hook is retained. Three previously unhandled copies could lose
capture controls: copying into the API2 logical-settings list, copying a ZSL
result, and the helper's own transactional copy. CameraMetadata reports a failed
clone as an empty buffer. The helper previously treated that as an intentionally
empty session and could return success with only the clientName tag. Each of
these nonempty-copy failures now returns an error before submission or ZSL input
enqueue. The caller's original metadata remains intact.

The identity write now uses CameraMetadata's byte-array update overload directly,
including the terminating NUL. This removes a temporary String8 allocation that
could otherwise lose the package string. Allocation/update failures still return
errors; legitimate empty sessions and provider-specific checks remain supported.

The helper suite has 28 cases, including seven new fault-injection cases; five
fail against the previous helper. Eight new pre-identity copy cases cover API2
and ZSL; three fail against the previous source. The copy harness includes the
actual Android utils/List.h. Existing API2 and API1 identity cases remain valid.
These failures are source-confirmed defects, not an established explanation of
the supplied strict-mode crop or AWB failures.

## October 26: shared-camera request lifetime

The shared-camera path had separate defects that were not repaired by clientName
propagation:

- A failed capture still inserted an uninitialized shared request ID and marked
  the client active. Both actions now require successful submission.
- The initial shared streaming pair defaulted to (0, 0), so the first capture
  could be mistaken for a repeating request. It now starts at (NONE, NONE).
- A partial result removed the capture mapping, and the last streaming frame's
  first partial cleared the repeating mapping. Static partial-result count is
  read before listener registration; mappings now survive until the final result.
- A buffer error or a physical-camera metadata error discarded the logical
  request mapping. Those errors now preserve it. Terminal request or logical
  metadata errors retire it and update the client's idle state.
- Flush discarded mappings before queued result/error callbacks drained. Those
  callbacks now retire their own mappings.
- A native shared-mode burst was acknowledged although only its first request
  was submitted. The native entry now rejects these bursts, matching the existing
  CameraSharedCaptureSessionImpl contract. Ordinary bursts remain supported.

The new runner compiles the actual submission, matching, result/error routing,
initialization and flush blocks with host API doubles. All 22 cases pass; the
pre-repair source fails 14. It replaces the baseline's local uninitialized output
ID with a defined poison value solely to reproduce the invalid state update
without executing an indeterminate read. These tests model sequential callback
interleavings.

The final related camera regressions total 106 passing cases. Applying the
updated frameworks/av companion patch to its pinned base reproduces all four
source files edited in this continuation. All 36 companion checks pass. These
changes are included in the October 27 full build.

```sh
python3 patches/camera-framework/tests/run-capture-copy-tests.py --report /tmp/camera-copy.json
python3 patches/camera-framework/tests/run-shared-request-tests.py --report /tmp/camera-shared.json
```

## October 26 camera/beauty recheck: additional failure paths

Earlier identity, shared-request and ZSL fixes remain in place. Two additional
failure paths were reproduced against the source saved immediately before this
recheck and repaired in CameraDeviceClient:

- endConfigureLocked cloned nonempty session parameters without checking for
  failure. It could configure an identity-only session after losing all caller
  controls. The new guard stops configuration; genuinely empty sessions remain
  supported, including external-provider sessions.
- Physical-camera filtering ignored allocation and per-entry update failures,
  and unchecked temporary/list copies could lose the filtered controls. Each
  failure now stops submission, preserving the caller's metadata. Legitimate
  empty filtered metadata and unadvertised keys retain their existing behavior.

The pre-identity copy runner now has 23 cases (eight failures against the source
before this recheck). Together with the unchanged 22 shared-request cases,
28 session-helper, 12 API2 identity and 36 API1 identity cases, all 121 related
host checks pass. These are extracted production blocks with host API/failure
doubles, including Android utils/List.h.

The beauty-region configuration and its 80 regression cases are documented in
[STRICT_LOG_REPAIRS.md](STRICT_LOG_REPAIRS.md). Allocation handling preserves
existing metadata, crop bounds and AWB limits.

Shared-stream cancellation must retain queued callbacks. A terminal error for
the last repeating frame can arrive before an earlier result because
Camera3OutputUtils dispatches errors directly while FrameProcessor queues
results. Two ordering cases show that clearing the mapping on the last error
would drop earlier callbacks. The existing notifyError mapping and 22-case
shared runner are retained; retirement must account for pending frames and
queued callbacks together.
