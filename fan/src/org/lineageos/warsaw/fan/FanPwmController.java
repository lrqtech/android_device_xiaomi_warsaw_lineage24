package org.lineageos.warsaw.fan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** The stock MCA driver exposes duty as an integer percentage, from 0 through 100. */
final class FanPwmController {
    private final Path root;

    FanPwmController() { this(Path.of("/sys/class/xm_power/hw_monitor/pwm_fan")); }
    FanPwmController(Path root) { this.root = root; }

    int apply(int duty, BooleanSupplier current) throws IOException {
        if (duty < 0 || duty > 100) throw new IllegalArgumentException("PWM out of range");
        if (read("fan_support") != 1) throw new IOException("Cooling fan unavailable");
        // 100 restores the driver's normal target-level mode after a factory override.
        // Target zero disables its closed-loop level adjustment before raw PWM is set.
        if (read("target_level") != 0) {
            checkCurrent(current);
            write("target_level", 100);
            checkCurrent(current);
            write("target_level", 0);
            if (read("target_level") != 0) throw new IOException("Fan level did not reset");
        }
        if (read("pwm_duty") != duty) {
            checkCurrent(current);
            write("pwm_duty", duty);
        }
        int observed = read("pwm_duty");
        if (observed != duty) throw new IOException("Fan PWM readback mismatch");
        checkCurrent(current);
        return observed;
    }
    private void checkCurrent(BooleanSupplier current) {
        if (!current.getAsBoolean()) throw new CancellationException("Fan request superseded");
    }
    private int read(String name) throws IOException {
        try { return Integer.parseInt(Files.readString(root.resolve(name)).trim()); }
        catch (NumberFormatException e) { throw new IOException("Invalid fan state", e); }
    }
    private void write(String name, int value) throws IOException {
        Files.write(root.resolve(name), (Integer.toString(value) + "\n")
                .getBytes(StandardCharsets.US_ASCII), java.nio.file.StandardOpenOption.WRITE);
    }
}
