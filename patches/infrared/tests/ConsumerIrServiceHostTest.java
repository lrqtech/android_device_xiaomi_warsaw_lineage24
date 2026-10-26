/* SPDX-License-Identifier: Apache-2.0 */
package com.android.server;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.ir.ConsumerIrFreqRange;
import android.hardware.ir.IConsumerIr;
import android.os.*;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the complete production service, with simulated Binder and power APIs. */
public final class ConsumerIrServiceHostTest {
    private static final int[] PATTERN = {9000, 4500, 560, 1690, 560};
    private static int failures;
    private static int cases;

    private static final class Hal implements IConsumerIr, IBinder {
        final PowerManager.WakeLock lock;
        boolean alive = true;
        boolean awakeAtTransmit;
        int txCalls, frequencyCalls, lastFrequency;
        int[] lastPattern;
        RemoteException txFailure, frequencyFailure;
        RuntimeException runtimeFailure;
        CountDownLatch entered, finish;
        Hal(PowerManager.WakeLock lock) { this.lock = lock; }
        public IBinder asBinder() { return this; }
        public boolean isBinderAlive() { return alive; }
        public void transmit(int frequency, int[] pattern) throws RemoteException {
            txCalls++;
            lastFrequency = frequency;
            lastPattern = pattern.clone();
            awakeAtTransmit = lock.isHeld();
            if (entered != null) {
                entered.countDown();
                await(finish);
            }
            if (runtimeFailure != null) throw runtimeFailure;
            if (txFailure != null) throw txFailure;
            if (!alive) throw new DeadObjectException();
        }
        public ConsumerIrFreqRange[] getCarrierFreqs() throws RemoteException {
            frequencyCalls++;
            if (frequencyFailure != null) throw frequencyFailure;
            if (!alive) throw new DeadObjectException();
            ConsumerIrFreqRange range = new ConsumerIrFreqRange();
            range.minHz = 30000;
            range.maxHz = 56000;
            return new ConsumerIrFreqRange[]{range};
        }
    }

    private static final class Fixture extends Context {
        final PowerManager power = new PowerManager();
        final PackageManager packages = new PackageManager();
        final Hal initial = new Hal(power.lock);
        final ConsumerIrService service;
        Fixture() { this(true, true); }
        Fixture(boolean feature, boolean hal) {
            packages.feature = feature;
            ServiceManager.reset(hal ? initial : null);
            service = new ConsumerIrService(this);
        }
        public Object getSystemService(String name) { return power; }
        public PackageManager getPackageManager() { return packages; }
        Hal replace() {
            initial.alive = false;
            Hal replacement = new Hal(power.lock);
            ServiceManager.service = replacement;
            return replacement;
        }
        void send() { service.transmit("com.duokan.phone.remotecontroller", 38000, PATTERN); }
        void balanced() {
            require(power.lock.held == 0 && power.lock.acquired == power.lock.released,
                    "wake lock leaked or unbalanced");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch) {
        try { require(latch.await(5, TimeUnit.SECONDS), "thread timeout"); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Wrong exception: " + failure);
        }
        throw new AssertionError("Missing " + type.getSimpleName());
    }
    private static void test(String name, Runnable action) {
        cases++;
        try { action.run(); System.out.println("PASS " + name); }
        catch (Throwable failure) {
            failures++;
            System.out.println("FAIL " + name + ": " + failure);
        }
    }

    public static void main(String[] args) {
        test("successful transmit preserves carrier and pattern", () -> {
            Fixture f = new Fixture(); f.send();
            require(f.initial.txCalls == 1 && f.initial.lastFrequency == 38000
                    && Arrays.equals(PATTERN, f.initial.lastPattern), "IR request changed");
            f.balanced();
        });
        test("transmit holds wake lock until completion", () -> {
            Fixture f = new Fixture(); f.send();
            require(f.initial.awakeAtTransmit && f.power.lock.acquired == 1,
                    "transmit was not protected from suspend"); f.balanced();
        });
        test("remote failure releases wake lock without replay", () -> {
            Fixture f = new Fixture(); f.initial.txFailure = new RemoteException(); f.send();
            require(f.initial.txCalls == 1 && f.initial.awakeAtTransmit, "missing lock or replay");
            f.balanced();
        });
        test("HAL runtime error propagates and releases wake lock", () -> {
            Fixture f = new Fixture(); ServiceSpecificException original = new ServiceSpecificException(5);
            f.initial.runtimeFailure = original;
            try { f.send(); throw new AssertionError("HAL error swallowed"); }
            catch (ServiceSpecificException e) { require(e == original, "HAL status replaced"); }
            require(f.initial.awakeAtTransmit, "HAL error path had no wake lock"); f.balanced();
        });
        test("dead cached HAL is replaced before next transmission", () -> {
            Fixture f = new Fixture(); Hal next = f.replace(); f.send();
            require(next.txCalls == 1 && f.initial.txCalls == 0, "dead HAL retained");
            require(ServiceManager.waits == 1 && ServiceManager.checks == 1, "recovery waited for HAL");
            f.balanced();
        });
        test("dead cached HAL is replaced for frequency query", () -> {
            Fixture f = new Fixture(); Hal next = f.replace();
            require(Arrays.equals(f.service.getCarrierFrequencies(), new int[]{30000, 56000}),
                    "frequency query did not recover");
            require(next.frequencyCalls == 1 && f.initial.frequencyCalls == 0, "queried dead HAL");
            require(f.power.lock.acquired == 0, "query acquired transmit wake lock");
        });
        test("death during transmit invalidates but never replays request", () -> {
            Fixture f = new Fixture(); f.initial.txFailure = new DeadObjectException();
            Hal next = new Hal(f.power.lock); ServiceManager.service = next;
            f.send(); require(next.txCalls == 0 && f.initial.txCalls == 1, "uncertain send replayed");
            f.send(); require(next.txCalls == 1 && f.initial.txCalls == 1, "dead transaction cache retained");
            f.balanced();
        });
        test("death during frequency query recovers on next call", () -> {
            Fixture f = new Fixture(); f.initial.frequencyFailure = new DeadObjectException();
            Hal next = new Hal(f.power.lock); ServiceManager.service = next;
            require(f.service.getCarrierFrequencies() == null, "failed query fabricated data");
            require(next.frequencyCalls == 0, "failed query replayed");
            require(f.service.getCarrierFrequencies() != null && next.frequencyCalls == 1
                    && f.initial.frequencyCalls == 1, "frequency death cache retained");
        });
        test("temporary HAL absence permits later recovery", () -> {
            Fixture f = new Fixture(); f.initial.alive = false; ServiceManager.service = null;
            f.send(); require(f.service.getCarrierFrequencies() == null, "missing HAL fabricated ranges");
            require(f.power.lock.acquired == 0 && f.initial.txCalls == 0, "missing HAL used or locked");
            Hal next = new Hal(f.power.lock); ServiceManager.service = next; f.send();
            require(next.txCalls == 1 && ServiceManager.waits == 1, "HAL restart did not recover");
            require(f.service.hasIrEmitter(), "temporary outage removed hardware capability"); f.balanced();
        });
        test("live cached HAL remains in use", () -> {
            Fixture f = new Fixture(); Hal other = new Hal(f.power.lock); ServiceManager.service = other;
            f.send(); f.service.getCarrierFrequencies();
            require(f.initial.txCalls == 1 && f.initial.frequencyCalls == 1 && other.txCalls == 0
                    && ServiceManager.checks == 0, "live connection replaced unnecessarily");
        });
        test("ordinary remote error does not discard live HAL", () -> {
            Fixture f = new Fixture(); f.initial.txFailure = new RemoteException(); f.send();
            f.initial.txFailure = null; Hal other = new Hal(f.power.lock); ServiceManager.service = other;
            f.send(); require(f.initial.txCalls == 2 && other.txCalls == 0, "live HAL discarded");
        });
        test("two successive HAL restarts recover", () -> {
            Fixture f = new Fixture(); Hal second = f.replace(); f.send(); second.alive = false;
            Hal third = new Hal(f.power.lock); ServiceManager.service = third; f.send();
            require(second.txCalls == 1 && third.txCalls == 1, "second restart failed"); f.balanced();
        });
        test("permission denial never calls HAL or takes wake lock", () -> {
            Fixture f = new Fixture(); f.service.permitted = false;
            expect(SecurityException.class, f::send);
            expect(SecurityException.class, () -> f.service.getCarrierFrequencies());
            require(f.initial.txCalls == 0 && f.initial.frequencyCalls == 0
                    && f.power.lock.acquired == 0 && ServiceManager.checks == 0, "permission bypass");
        });
        test("nonpositive slices stay rejected before HAL use", () -> {
            Fixture f = new Fixture();
            for (int value : new int[]{0, -1, Integer.MIN_VALUE}) {
                expect(IllegalArgumentException.class,
                        () -> f.service.transmit("remote", 38000, new int[]{500, value}));
            }
            require(f.initial.txCalls == 0 && f.power.lock.acquired == 0, "invalid pattern dispatched");
        });
        test("maximum duration validation remains intact", () -> {
            Fixture f = new Fixture();
            expect(IllegalArgumentException.class,
                    () -> f.service.transmit("remote", 38000, new int[]{2000000, 1}));
            expect(IllegalArgumentException.class,
                    () -> f.service.transmit("remote", 38000, new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE}));
            f.service.transmit("remote", 38000, new int[]{1000000, 1000000});
            require(f.initial.txCalls == 1, "duration boundary changed"); f.balanced();
        });
        test("missing hardware still rejects transmit and query", () -> {
            Fixture f = new Fixture(false, false);
            require(!f.service.hasIrEmitter(), "hardware fabricated");
            expect(UnsupportedOperationException.class, f::send);
            expect(UnsupportedOperationException.class, () -> f.service.getCarrierFrequencies());
            require(f.power.lock.acquired == 0 && ServiceManager.checks == 0, "absent hardware queried");
        });
        test("startup feature and HAL mismatch stays fatal", () -> {
            expect(RuntimeException.class, () -> new Fixture(true, false));
            expect(RuntimeException.class, () -> new Fixture(false, true));
        });
        test("healthy frequency ranges preserve public representation", () -> {
            Fixture f = new Fixture();
            require(Arrays.equals(f.service.getCarrierFrequencies(), new int[]{30000, 56000}), "ranges changed");
            require(f.initial.txCalls == 0 && f.power.lock.acquired == 0, "query caused send");
        });
        test("frequency RemoteException returns null", () -> {
            Fixture f = new Fixture(); f.initial.frequencyFailure = new RemoteException();
            require(f.service.getCarrierFrequencies() == null, "query error fabricated result");
            require(f.initial.frequencyCalls == 1 && ServiceManager.checks == 0, "query unexpectedly retried");
        });
        test("concurrent sends stay serialized with balanced wake locks", () -> {
            Fixture f = new Fixture(); f.initial.entered = new CountDownLatch(1);
            f.initial.finish = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable send = () -> { try { f.send(); } catch (Throwable t) { failure.set(t); } };
            Thread first = new Thread(send); Thread second = new Thread(send);
            first.start(); await(f.initial.entered); second.start(); f.initial.finish.countDown();
            try { first.join(6000); second.join(6000); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            require(!first.isAlive() && !second.isAlive() && failure.get() == null, "thread failure");
            require(f.initial.txCalls == 2 && f.power.lock.acquired == 2 && f.power.lock.maximum == 1,
                    "send serialization or wake ownership broken"); f.balanced();
        });
        System.out.println("IR service cases=" + cases + " failures=" + failures);
        if (failures != 0) System.exit(1);
    }
}
