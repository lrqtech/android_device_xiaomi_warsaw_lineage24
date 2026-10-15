package com.miui.cameraopt.intentaware;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Credentials;
import android.net.LocalSocket;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class PeerIdentity {
    public final int uid;
    public final int pid;
    public final String name;

    private PeerIdentity(Credentials credentials, String name) {
        uid = credentials.getUid();
        pid = credentials.getPid();
        this.name = name;
    }

    public static void verifyServer(Context context, LocalSocket socket) throws IOException {
        PackageManager pm = context.getPackageManager();
        final String name = "com.xiaomi.cameramind";
        try {
            int expectedUid = pm.getApplicationInfo(name, 0).uid;
            if (socket.getPeerCredentials().getUid() != expectedUid
                    || pm.checkSignatures("android", name) != PackageManager.SIGNATURE_MATCH) {
                throw new IOException("CameraMind socket peer identity mismatch");
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            throw new IOException("CameraMind package identity unavailable", e);
        }
    }

    public static final class Authorizer {
        private final PackageManager pm;
        private final Set<String> allowedPackages;
        private final Map<String, Integer> nativeUids;

        public Authorizer(Context context, Set<String> packages, Map<String, Integer> nativeUids) {
            pm = context.getPackageManager();
            allowedPackages = Collections.unmodifiableSet(new HashSet<>(packages));
            this.nativeUids = Collections.unmodifiableMap(new HashMap<>(nativeUids));
        }

        public PeerIdentity verify(LocalSocket socket, String claim) throws IOException {
            Credentials peer = socket.getPeerCredentials();
            if (claim == null || claim.trim().isEmpty() || claim.length() > 512 || peer.getUid() % 100000 == 2000) {
                throw new IOException("CameraMind peer name/UID rejected");
            }
            String name = claim.trim();
            int appId = peer.getUid() % 100000;
            if (appId >= 10000) {
                String[] packages = pm.getPackagesForUid(peer.getUid());
                if (!allowedPackages.contains(name) || packages == null
                        || !Arrays.asList(packages).contains(name)
                        || pm.checkSignatures("android", name) != PackageManager.SIGNATURE_MATCH) {
                    throw new IOException("CameraMind package/name/signature rejected");
                }
            } else {
                String actual = readProcessName(peer.getPid());
                String actualBase = actual.substring(actual.lastIndexOf('/') + 1);
                String claimedBase = name.substring(name.lastIndexOf('/') + 1);
                Integer expected = nativeUids.get(actualBase);
                if (expected == null || expected != peer.getUid() || !actualBase.equals(claimedBase)) {
                    throw new IOException("CameraMind native process identity rejected");
                }
                name = actualBase;
            }
            return new PeerIdentity(peer, name);
        }
    }

    public static String readProcessName(int pid) throws IOException {
        byte[] data = new byte[512];
        int count = 0;
        try (FileInputStream input = new FileInputStream("/proc/" + pid + "/cmdline")) {
            while (count < data.length) {
                int value = input.read();
                if (value < 0 || value == 0) break;
                data[count++] = (byte) value;
            }
        }
        if (count == 0 || count == data.length) throw new IOException("Unavailable bounded peer argv[0]");
        return new String(data, 0, count, StandardCharsets.UTF_8);
    }
}
