package com.android.server.warsaw;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.util.*;

/** Deterministic binding callbacks; runs the production lifecycle source. */
public final class WarsawCameraMindLifecycleTest {
    private static final ComponentName NAME = new ComponentName(
            "com.xiaomi.cameramind", "com.xiaomi.cameramind.CameraMindService");
    private static final IBinder LIVE = () -> true;
    private static int failed;
    private static final class Fixture extends Context {
        final Handler worker = new Handler();
        final PackageManager pm = new PackageManager();
        final Set<ServiceConnection> registered = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<ServiceConnection> attempts = new ArrayList<>();
        final WarsawCameraMindLifecycle lifecycle = new WarsawCameraMindLifecycle(this, worker);
        int unbound;
        int failNext;
        Fixture() { SystemProperties.enabled = true; }
        public PackageManager getPackageManager() { return pm; }
        public boolean bindServiceAsUser(Intent intent, ServiceConnection conn, int flags,
                Handler handler, UserHandle user) {
            require(handler == worker && user == UserHandle.SYSTEM, "Worker/user binding changed");
            require((flags & BIND_AUTO_CREATE) != 0, "Service no longer starts automatically");
            registered.add(conn);
            attempts.add(conn);
            int failure = failNext;
            failNext = 0;
            if (failure == 2) throw new SecurityException("Denied by service manager");
            return failure != 1;
        }
        public void unbindService(ServiceConnection conn) {
            require(registered.remove(conn), "Duplicate unbind");
            unbound++;
        }
        void start() { lifecycle.start(); worker.advance(0); }
        ServiceConnection current() { return attempts.get(attempts.size() - 1); }
        void connected() { current().onServiceConnected(NAME, LIVE); }
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void test(String name, Runnable action) {
        try { action.run(); System.out.println("PASS " + name); }
        catch (AssertionError failure) { failed++; System.out.println("FAIL " + name + ": " + failure.getMessage()); }
    }
    public static void main(String[] args) {
        test("slow startup keeps accepted binding", () -> {
            Fixture f = new Fixture(); f.start(); f.worker.advance(1500);
            require(f.attempts.size() == 1 && f.unbound == 0, "Pending connection was cancelled before service startup");
            f.connected(); f.worker.advance(60000);
            require(f.attempts.size() == 1 && f.unbound == 0, "Connected service was rebound");
        });
        test("unanswered binding has bounded timeout", () -> {
            Fixture f = new Fixture(); f.start(); f.worker.advance(29999);
            require(f.attempts.size() == 1, "Retry backoff used as connection timeout");
            f.worker.advance(1); require(f.unbound == 1, "Unanswered request did not expire");
            f.worker.advance(500); require(f.attempts.size() == 2, "Timeout did not schedule retry");
        });
        test("false bind releases dispatcher", () -> {
            Fixture f = new Fixture(); f.failNext = 1; f.start();
            require(f.unbound == 1 && f.registered.isEmpty(), "Rejected binding leaked its dispatcher");
            f.worker.advance(499); require(f.attempts.size() == 1, "Retry ignored backoff");
            f.worker.advance(1); require(f.attempts.size() == 2, "Rejected binding did not retry");
        });
        test("security exception releases dispatcher", () -> {
            Fixture f = new Fixture(); f.failNext = 2; f.start();
            require(f.unbound == 1 && f.registered.isEmpty(), "Denied binding leaked its dispatcher");
            f.worker.advance(500); require(f.attempts.size() == 2, "Denied binding did not retry");
        });
        test("null binding releases before retry", () -> {
            Fixture f = new Fixture(); f.start(); f.current().onNullBinding(NAME);
            require(f.unbound == 1, "Null binder retained a connection");
            f.worker.advance(500); require(f.attempts.size() == 2, "Null binding did not retry");
        });
        test("binding death ignores stale callbacks", () -> {
            Fixture f = new Fixture(); f.start(); ServiceConnection old = f.current(); f.connected();
            old.onBindingDied(NAME); f.worker.advance(500);
            require(f.attempts.size() == 2 && f.unbound == 1, "Dead binding was not replaced");
            old.onServiceConnected(NAME, LIVE); old.onNullBinding(NAME);
            f.worker.advance(1500);
            require(f.attempts.size() == 2 && f.registered.size() == 1, "Stale callback changed the new attempt");
        });
        test("repeated start preserves pending connection", () -> {
            Fixture f = new Fixture(); f.start(); f.lifecycle.start(); f.worker.advance(0);
            require(f.attempts.size() == 1 && f.unbound == 0, "Repeated start replaced pending request");
        });
        test("disabled and untrusted services stay unbound", () -> {
            Fixture f = new Fixture(); SystemProperties.enabled = false; f.start();
            require(f.attempts.isEmpty(), "Disabled service was started");
            Fixture bad = new Fixture(); bad.pm.trusted = false; bad.start(); bad.worker.advance(60000);
            require(bad.attempts.isEmpty(), "Untrusted service was bound");
        });
        test("disconnect retries with reset backoff", () -> {
            Fixture f = new Fixture(); f.failNext = 1; f.start(); f.worker.advance(500); f.connected();
            f.current().onServiceDisconnected(NAME); f.worker.advance(499);
            require(f.attempts.size() == 2, "Reconnect ignored backoff");
            f.worker.advance(1); require(f.attempts.size() == 3, "Successful connection did not reset backoff");
        });
        test("expired callback cannot cancel active timeout", () -> {
            Fixture f = new Fixture(); f.start(); ServiceConnection expired = f.current();
            f.worker.advance(30500); require(f.attempts.size() == 2, "First request did not expire once");
            expired.onServiceConnected(NAME, LIVE); f.worker.advance(30000);
            require(f.unbound == 2, "Expired callback cancelled another request timeout");
        });
        System.out.println("Lifecycle cases=10 failures=" + failed);
        if (failed != 0) System.exit(1);
    }
}
