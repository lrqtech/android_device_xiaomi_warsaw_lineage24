package miui.os;

import android.os.SystemProperties;

/** OS4 API compatibility backed by the running build, never an invented MIUI version. */
public final class Build {
    private Build() {}
    public static final boolean IS_INTERNATIONAL_BUILD =
            SystemProperties.get("ro.product.mod_device", "").contains("_global");
    public static final boolean IS_STABLE_VERSION = "user".equals(android.os.Build.TYPE)
            && android.os.Build.VERSION.INCREMENTAL.matches("^((OS|V)\\d{1,})(\\.\\d{1,})*(\\.[A-Z]{4,})$");
    public static String getRegion() { return SystemProperties.get("ro.miui.region", "CN"); }
}
