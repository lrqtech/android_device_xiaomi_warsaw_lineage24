# Camera provider RPC handle registration

The supplied strict and permissive comparison logs contain 6,338
`get_domain_from_handle64():115 no find domain by h` messages. The logger and
handle map are in `odm/bin/hw/vendor.qti.camera.provider-service_64`, not in
libDlhookDumpStates. The latter only supplies monitoring helpers.

## Confirmed defects and changes

The provider calls the underlying `remote_handle64_open` at 0xbcd8 and saves
its result in w26. Its old branch at 0xbce0 only checked the name pointer. A
failed open with a non-null name could still dereference the output pointer,
insert an uninitialized/stale handle, or overwrite another live handle's domain.
The branch now tests w26 and skips registration on any nonzero result. The
selected libcdsprpc already rejects null name/output pointers before success.
The original return value, failure logs and SSR bookkeeping are preserved.

The domain parser correctly returns zero for ADSP session 0, but the flag set
at 0xbd80 caused the map insertion at 0xc038 to be skipped. The flag now permits
registration for this valid domain. Other domains and sessions are unchanged.
ADSP still uses the normal invoke path. The existing watchdog remains limited
to CDSP domains 3 and 7. Unknown-handle diagnostics are retained.

Only these two instructions and the GNU build-id note changed. File size, ELF
layout, dependencies, symbol exports, relocations, RPC transport and invocation
results are unchanged. The preparation tool verifies both provider and RPC
library hashes and rejects other versions. The prepared-input manifest matches
and full vendor metadata reproduction passes.

## Host verification

`configs/camera/tests/test-provider-rpc.py` executes the actual ARM open,
map insertion/lookup, invoke and close instructions with modeled external calls.
Twenty-two cases pass; the old executable fails eleven. Coverage includes all
four DSP domains and both sessions, successful and failed opens, null inputs,
failed opens with a live stale output, close/reopen, retry isolation, unknown
handles, original return values and the existing CDSP watchdog branch. Five
unsupported provider/library inputs are rejected. The patch is reproducible and
idempotent. These tests do not execute DSP firmware or reproduce Android threads.

Run with the original provider retained outside the public tree:

```sh
python3 configs/camera/tests/test-provider-rpc.py \
  --baseline /path/to/original-provider \
  --provider /path/to/prepared/odm/bin/hw/vendor.qti.camera.provider-service_64 \
  --rpc-library /path/to/prepared/vendor/lib64/libcdsprpc.so
```

The host environment requires pyelftools and Unicorn. Optional `--python-path`
and `--report` select an existing dependency directory and a JSON result file.

## Handle-map attribution

The supplied logs do not contain matching open records for the specific missing
handles. This repair reproduces a concrete missing-map path and repairs unsafe
failure handling; it does not prove all 6,338 messages have the same cause.
The original missing-map branch still invokes the real RPC operation and returns
its status, so the warning alone is not proof of a failed DSP request.

This change is restricted to the provider handle-map path. Crop coordinates,
AWB limits and the existing error results are retained.
