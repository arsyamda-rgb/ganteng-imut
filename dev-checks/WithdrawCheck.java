package com.veyrion.character.economy;

import com.veyrion.character.api.MoneyResult;
import com.veyrion.character.config.PluginSettings;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Check of the public has()/withdraw() path (EconomyService.hasFunds / withdrawChecked). See README.txt. */
public final class WithdrawCheck {

    static int checks = 0;
    static int failures = 0;

    static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    /**
     * A provider that is NOT thread safe and does NOT refuse overdrafts unless asked: if the service did not
     * serialize check-and-take, many threads would pass the check and the balance would go negative.
     */
    static final class SlowBridge implements EconomyBridge {
        final Map<UUID, Double> money = new HashMap<>();
        boolean allowOverdraft = true;
        boolean refuse;
        boolean explode;
        final AtomicInteger withdrawCalls = new AtomicInteger();

        @Override public Status status() { return Status.AVAILABLE; }
        @Override public String providerName() { return "slow"; }
        @Override public double balance(UUID p) { synchronized (money) { return money.getOrDefault(p, 0.0); } }
        @Override public boolean has(UUID p, double a) { return balance(p) >= a; }
        @Override public Result deposit(UUID p, double a) { money.merge(p, a, Double::sum); return Result.ok(); }
        @Override public Result withdraw(UUID p, double a) {
            withdrawCalls.incrementAndGet();
            if (explode) throw new IllegalStateException("provider exploded");
            if (refuse) return Result.fail("refused");
            if (!allowOverdraft && !has(p, a)) return Result.fail("insufficient");
            double before = balance(p);
            Thread.yield(); // widen the race window on purpose
            synchronized (money) { money.put(p, before - a); }
            return Result.ok();
        }
    }

    static PluginSettings.Economy settings(boolean enabled) {
        return new PluginSettings.Economy(enabled, "Veyra", "VYR", true, true, true, 1, 120, 3000);
    }

    static EconomyService service(SlowBridge bridge, boolean enabled) {
        return new EconomyService(Logger.getLogger("check"), () -> settings(enabled), bridge);
    }

    public static void main(String[] args) throws Exception {
        UUID a = UUID.randomUUID();

        // ---- enough money
        SlowBridge bridge = new SlowBridge();
        bridge.money.put(a, 100.0);
        EconomyService svc = service(bridge, true);
        eq("has 40 of 100", true, svc.hasFunds(a, 40));
        eq("has exactly 100", true, svc.hasFunds(a, 100));
        eq("has 100.01 of 100", false, svc.hasFunds(a, 100.01));
        eq("withdraw 40 ok", MoneyResult.SUCCESS, svc.withdrawChecked(a, 40));
        eq("balance after", 60.0, bridge.balance(a));
        eq("withdraw exactly the rest", MoneyResult.SUCCESS, svc.withdrawChecked(a, 60));
        eq("balance zero, not negative", 0.0, bridge.balance(a));

        // ---- not enough money: nothing is taken, the provider is not even asked
        bridge = new SlowBridge();
        bridge.money.put(a, 10.0);
        svc = service(bridge, true);
        eq("insufficient", MoneyResult.INSUFFICIENT_FUNDS, svc.withdrawChecked(a, 10.01));
        eq("balance untouched", 10.0, bridge.balance(a));
        eq("provider not called", 0, bridge.withdrawCalls.get());
        eq("unknown account is 0", MoneyResult.INSUFFICIENT_FUNDS, svc.withdrawChecked(UUID.randomUUID(), 1));
        eq("rounding: 9.999 pays as 10.00", MoneyResult.SUCCESS, svc.withdrawChecked(a, 9.999));
        eq("balance after rounding", 0.0, bridge.balance(a));

        // ---- invalid amounts and unavailable economy
        bridge = new SlowBridge();
        bridge.money.put(a, 50.0);
        svc = service(bridge, true);
        for (double bad : new double[] {0, -5, Double.NaN, Double.POSITIVE_INFINITY, 0.004, Money.MAX * 2}) {
            eq("invalid " + bad, MoneyResult.INVALID_AMOUNT, svc.withdrawChecked(a, bad));
            eq("has invalid " + bad, false, svc.hasFunds(a, bad));
        }
        eq("balance untouched by invalid", 50.0, bridge.balance(a));
        EconomyService off = service(bridge, false);
        eq("disabled economy", MoneyResult.UNAVAILABLE, off.withdrawChecked(a, 5));
        eq("disabled has", false, off.hasFunds(a, 5));
        eq("balance untouched when disabled", 50.0, bridge.balance(a));

        // ---- provider failures
        bridge.refuse = true;
        eq("provider refuses", MoneyResult.ERROR, svc.withdrawChecked(a, 5));
        bridge.refuse = false;
        bridge.explode = true;
        eq("provider throws", MoneyResult.ERROR, svc.withdrawChecked(a, 5));
        eq("balance untouched by failures", 50.0, bridge.balance(a));

        // ---- concurrent: 64 threads each try to pay 10 from 100 => exactly 10 succeed, balance 0, never negative
        bridge = new SlowBridge();
        bridge.money.put(a, 100.0);
        svc = service(bridge, true);
        final EconomyService shared = svc;
        final int threads = 64;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    MoneyResult r = shared.withdrawChecked(a, 10);
                    if (r == MoneyResult.SUCCESS) ok.incrementAndGet();
                    else if (r == MoneyResult.INSUFFICIENT_FUNDS) insufficient.incrementAndGet();
                    else other.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        eq("concurrent successes", 10, ok.get());
        eq("concurrent insufficient", threads - 10, insufficient.get());
        eq("concurrent other", 0, other.get());
        eq("concurrent balance", 0.0, bridge.balance(a));
        eq("provider charged once per success", 10, bridge.withdrawCalls.get());

        // ---- two different players do not block or affect each other
        UUID b = UUID.randomUUID();
        bridge = new SlowBridge();
        bridge.money.put(a, 30.0);
        bridge.money.put(b, 30.0);
        svc = service(bridge, true);
        eq("a pays", MoneyResult.SUCCESS, svc.withdrawChecked(a, 30));
        eq("b untouched", 30.0, bridge.balance(b));

        System.out.println(checks + " checks, " + failures + " failures");
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        if (failures != 0) System.exit(1);
    }
}
