#!/vendor/bin/sh

SYSTEM_DLKM_DIR=/system_dlkm/lib/modules
VENDOR_DLKM_DIR=/vendor_dlkm/lib/modules
MODPROBE=/vendor/bin/modprobe

if [ -f "${SYSTEM_DLKM_DIR}/modules.load" ]; then
    set -- "${SYSTEM_DLKM_DIR}"
else
    set -- "${SYSTEM_DLKM_DIR}"/*
fi

for module_dir do
    [ -f "${module_dir}/modules.load" ] || continue

    blocklist_file="${VENDOR_DLKM_DIR}/system_dlkm.modules.blocklist"
    if [ -f "${blocklist_file}" ] && grep -q blocklist "${blocklist_file}"; then
        blocklist_expr="$(sed -n -e 's/blocklist \(.*\)/\1/p' "${blocklist_file}" | sed -e 's/-/[-_]/g' -e 's/^/-e /')"
    else
        blocklist_expr="-e %"
    fi

    load_modules=$(grep -w -v ${blocklist_expr} "${module_dir}/modules.load")
    [ -n "${load_modules}" ] || continue
    set -- ${load_modules}
    first_module=$1
    shift

    if ! "${MODPROBE}" -b -s -d "${module_dir}" -a "${first_module}" > /dev/null; then
        continue
    fi

    module_pids=""
    for module do
        ( "${MODPROBE}" -b -s -d "${module_dir}" -a "${module}" ) &
        module_pids="${module_pids} $!"
    done
    failed=0
    for module_pid in ${module_pids}; do
        if ! wait "${module_pid}"; then
            failed=1
        fi
    done
    exit "${failed}"
done

exit 1
