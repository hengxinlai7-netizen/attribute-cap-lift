package com.hengx.attributecaplift.test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hengx.attributecaplift.CapConfig;
import com.hengx.attributecaplift.PanelClamp;
import com.hengx.attributecaplift.TravelCap;
import com.hengx.attributecaplift.UncappedAttributes;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.event.TickEvent;

/**
 * 自检 / 基准测试。服务端带 {@code -Dattributecaplift.selftest=true} 时启动。
 *
 * <p>输出:</p>
 * <pre>
 * SELFTEST A       同一只僵尸:getAttributeValue 读到 2.23,属性 maxValue 仍是 0.5
 * SELFTEST PERF    getValue() 微基准:enabled=false / true 的 ns/op
 * SELFTEST PHASE   100 只僵尸,每 tick 直接调 travel(前进输入),分阶段测:
 *                  - mspt
 *                  - 200 tick 内累计位移(每 10 tick 记一段并传送回起点,避免区块卸载)
 *                  - travel 回落次数
 * SELFTEST DONE
 * </pre>
 *
 * <p>每阶段只有 {@code enabled/clampInTravel} 不同:</p>
 * <ul>
 *   <li>phase 1: enabled=false  → 完全关闭(基线)</li>
 *   <li>phase 2: enabled=true, clampInTravel=true → 读值不 clamp + 移动回落</li>
 *   <li>phase 3: enabled=true, clampInTravel=false → 只读值不 clamp(泄漏演示)</li>
 * </ul>
 */
public final class SelfTest {

    private static final Logger LOG = LoggerFactory.getLogger("AttrCapSelfTest");
    private static final UUID MODIFIER_ID = UUID.fromString("a7c1b0de-0000-4000-8000-00000000ca11");
    private static final int ZOMBIE_COUNT = 100;
    private static final int WARMUP_TICKS = 200;
    private static final int PHASE_TICKS = 200;
    private static final int SEG_TICKS = 10;
    private static final double ORIGIN_X = 6.0D;
    private static final double ORIGIN_Z = 6.0D;

    private static boolean active;
    private static ServerLevel level;
    private static final List<Zombie> ZOMBIES = new ArrayList<>();
    private static final List<Vec3> SEG_START = new ArrayList<>();
    private static final List<Double> ACCUM = new ArrayList<>();

    private static int phase;
    private static int phaseTicks;
    private static long tickStartNs;
    private static long phaseNanos;
    private static long phaseClampBase;
    private static volatile double sink;

    private SelfTest() {
    }

    /**
     * 面板 clamp 的四条断言(纯逻辑,不需要真的开 GUI)。
     *
     * <p>覆盖 {@link com.hengx.attributecaplift.PanelClamp#clamp} 的全部分支:
     * 关开关 → raw;raw 超上限 → max;raw 未超 → raw;非 watched → 原值。</p>
     */
    private static void panelClampChecks() {
        int pass = 0;
        int fail = 0;
        double raw = 2.23D;
        double max = 0.5D;

        // 1) panelClamp=false → 显示 raw
        double v1 = PanelClamp.clamp(true, false, true, true, raw, max);
        pass += v1 == raw ? 1 : 0;
        fail += v1 == raw ? 0 : 1;
        LOG.info("SELFTEST PANEL 1: panelClamp=false -> {} (expect {}) {}", v1, raw, v1 == raw ? "PASS" : "FAIL");

        // 2) panelClamp=true 且 raw > max → 显示 max
        double v2 = PanelClamp.clamp(true, true, true, true, raw, max);
        pass += v2 == max ? 1 : 0;
        fail += v2 == max ? 0 : 1;
        LOG.info("SELFTEST PANEL 2: panelClamp=true raw({})>max({}) -> {} (expect {}) {}",
                raw, max, v2, max, v2 == max ? "PASS" : "FAIL");

        // 3) panelClamp=true 且 raw <= max → 显示 raw
        double low = 0.3D;
        double v3 = PanelClamp.clamp(true, true, true, true, low, max);
        pass += v3 == low ? 1 : 0;
        fail += v3 == low ? 0 : 1;
        LOG.info("SELFTEST PANEL 3: panelClamp=true raw({})<=max({}) -> {} (expect {}) {}",
                low, max, v3, low, v3 == low ? "PASS" : "FAIL");

        // 4) 非 watched 属性 → 原值
        double v4 = PanelClamp.clamp(true, true, false, true, raw, max);
        pass += v4 == raw ? 1 : 0;
        fail += v4 == raw ? 0 : 1;
        LOG.info("SELFTEST PANEL 4: 非 watched -> {} (expect {}) {}", v4, raw, v4 == raw ? "PASS" : "FAIL");

        // 5) 配置联动:testPanelClamp=false 时 CapConfig.panelClamp() 必须读到 false
        CapConfig.setTestPanelClamp(Boolean.FALSE);
        boolean cfgOff = !CapConfig.panelClamp();
        CapConfig.setTestPanelClamp(Boolean.TRUE);
        boolean cfgOn = CapConfig.panelClamp();
        CapConfig.setTestPanelClamp(null);
        pass += cfgOff && cfgOn ? 1 : 0;
        fail += cfgOff && cfgOn ? 0 : 1;
        LOG.info("SELFTEST PANEL 5: CapConfig.panelClamp() override off={} on={} {}",
                cfgOff, cfgOn, cfgOff && cfgOn ? "PASS" : "FAIL");

        LOG.info("SELFTEST PANEL RESULT: PASSED={} FAILED={}", pass, fail);
    }

    public static void start(MinecraftServer server) {
        if (active) {
            return;
        }
        active = true;
        panelClampChecks();
        level = server.overworld();
        level.setDayTime(18000L); // 夜里,僵尸不会烧起来
        level.getGameRules().getRule(GameRules.RULE_MAX_ENTITY_CRAMMING).set(0, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);

        // ---------- A: 同一只僵尸的"读值" ----------
        Zombie probe = EntityType.ZOMBIE.create(level);
        if (probe != null) {
            AttributeInstance inst = probe.getAttribute(Attributes.MOVEMENT_SPEED);
            if (inst != null) {
                inst.addTransientModifier(new AttributeModifier(MODIFIER_ID, "attrcap_selftest", 2.0D,
                        AttributeModifier.Operation.ADDITION));
                double read = probe.getAttributeValue(Attributes.MOVEMENT_SPEED);
                double max = Attributes.MOVEMENT_SPEED instanceof RangedAttribute r ? r.getMaxValue() : Double.NaN;
                LOG.info("SELFTEST A: getAttributeValue={} instance.getValue()={} maxValue={} "
                                + "clampedWouldBe={} base={}",
                        read, inst.getValue(), max, Math.min(read, max), inst.getBaseValue());
                // RAWCHECK: 未被 clamp 的属性上,computeRaw 必须与原版 getValue 逐位相同
                AttributeInstance dmg = probe.getAttribute(Attributes.ATTACK_DAMAGE);
                if (dmg != null) {
                    dmg.addTransientModifier(new AttributeModifier(
                            UUID.fromString("a7c1b0de-0000-4000-8000-00000000ca12"), "rawcheck_add", 1.7D,
                            AttributeModifier.Operation.ADDITION));
                    dmg.addTransientModifier(new AttributeModifier(
                            UUID.fromString("a7c1b0de-0000-4000-8000-00000000ca13"), "rawcheck_mb", 0.3D,
                            AttributeModifier.Operation.MULTIPLY_BASE));
                    dmg.addTransientModifier(new AttributeModifier(
                            UUID.fromString("a7c1b0de-0000-4000-8000-00000000ca14"), "rawcheck_mt", 0.15D,
                            AttributeModifier.Operation.MULTIPLY_TOTAL));
                    double vanilla = dmg.getValue();
                    double computed = UncappedAttributes.computeRaw(dmg);
                    LOG.info("SELFTEST RAWCHECK attack_damage vanilla={} computeRaw={} bitEqual={} "
                                    + "vanillaBits={} rawBits={}",
                            vanilla, computed, Double.doubleToLongBits(vanilla) == Double.doubleToLongBits(computed),
                            Double.doubleToLongBits(vanilla), Double.doubleToLongBits(computed));
                }
                benchmark(inst);
            }
        }

        // ---------- 100 只僵尸 ----------
        for (int i = 0; i < ZOMBIE_COUNT; i++) {
            Zombie z = EntityType.ZOMBIE.create(level);
            if (z == null) {
                continue;
            }
            z.teleportTo(ORIGIN_X + (i % 10) * 1.6D, -60.0D, ORIGIN_Z + (i / 10) * 1.6D);
            z.setPersistenceRequired();
            z.setInvulnerable(true);
            AttributeInstance inst = z.getAttribute(Attributes.MOVEMENT_SPEED);
            if (inst != null) {
                inst.addTransientModifier(new AttributeModifier(MODIFIER_ID, "attrcap_selftest", 2.0D,
                        AttributeModifier.Operation.ADDITION));
            }
            level.addFreshEntity(z);
            ZOMBIES.add(z);
        }
        LOG.info("SELFTEST START: spawned {} zombies (movement_speed +2.0)", ZOMBIES.size());

        phase = 0;
        phaseTicks = 0;
        beginPhase(0);
    }

    /** getValue() 微基准(算进去的只是本模组的额外开销)。 */
    private static void benchmark(AttributeInstance inst) {
        double acc = 0.0D;
        for (int i = 0; i < 500_000; i++) {
            acc += inst.getValue();
        }
        double offNs = 0.0D;
        double onNs = 0.0D;
        for (int round = 0; round < 2; round++) {
            CapConfig.setTestOverrides(Boolean.FALSE, null);
            long t0 = System.nanoTime();
            for (int i = 0; i < 1_000_000; i++) {
                acc += inst.getValue();
            }
            long t1 = System.nanoTime();
            offNs += (t1 - t0) / 1_000_000.0D;

            CapConfig.setTestOverrides(Boolean.TRUE, null);
            long t2 = System.nanoTime();
            for (int i = 0; i < 1_000_000; i++) {
                acc += inst.getValue();
            }
            long t3 = System.nanoTime();
            onNs += (t3 - t2) / 1_000_000.0D;
        }
        CapConfig.setTestOverrides(null, null);
        sink = acc;
        offNs /= 2.0D;
        onNs /= 2.0D;
        LOG.info("SELFTEST PERF getValue(): off={} ns/op ({} us/op), on={} ns/op ({} us/op), delta={} ns/op",
                String.format("%.2f", offNs), String.format("%.5f", offNs / 1000.0D),
                String.format("%.2f", onNs), String.format("%.5f", onNs / 1000.0D),
                String.format("%.2f", onNs - offNs));
    }

    public static void onTick(TickEvent.ServerTickEvent event) {
        if (!active) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            tickStartNs = System.nanoTime();
            return;
        }
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long now = System.nanoTime();
        if (phase == 0) {
            drive();
            if (++phaseTicks >= WARMUP_TICKS) {
                beginPhase(1);
            }
            return;
        }
        if (phase >= 6) {
            return;
        }
        drive();
        phaseNanos += now - tickStartNs;
        if (++phaseTicks % SEG_TICKS == 0) {
            closeSegment();
        }
        if (phaseTicks >= PHASE_TICKS) {
            endPhase();
        }
    }

    /**
     * 每 tick 让每只僵尸走一步:速度字段先按"属性读值"设置(玩家

     * {@code Player#getSpeed()} 的语义),再调用 travel。
     */
    private static void drive() {
        for (Zombie z : ZOMBIES) {
            if (z.isRemoved()) {
                continue;
            }
            z.setSpeed((float) z.getAttributeValue(Attributes.MOVEMENT_SPEED));
            z.travel(new Vec3(0.0D, 0.0D, 1.0D));
        }
    }

    /** 记录这一小段走过的水平距离,然后传送回段起点(避免区块卸载把实体移除)。 */
    private static void closeSegment() {
        for (int i = 0; i < ZOMBIES.size(); i++) {
            Zombie z = ZOMBIES.get(i);
            Vec3 start = SEG_START.get(i);
            if (!z.isRemoved()) {
                Vec3 end = z.position();
                double dx = end.x - start.x;
                double dz = end.z - start.z;
                ACCUM.set(i, ACCUM.get(i) + Math.sqrt(dx * dx + dz * dz));
            }
            z.teleportTo(start.x, start.y, start.z);
            z.setDeltaMovement(Vec3.ZERO);
            z.setYRot(0.0F);
        }
    }

    private static void beginPhase(int next) {
        phase = next;
        phaseTicks = 0;
        phaseNanos = 0L;
        phaseClampBase = TravelCap.clampCount();
        SEG_START.clear();
        ACCUM.clear();
        for (int i = 0; i < ZOMBIES.size(); i++) {
            Zombie z = ZOMBIES.get(i);
            Vec3 start = new Vec3(ORIGIN_X + (i % 10) * 1.6D, -60.0D, ORIGIN_Z + (i / 10) * 1.6D);
            z.teleportTo(start.x, start.y, start.z);
            z.setDeltaMovement(Vec3.ZERO);
            z.setYRot(0.0F);
            SEG_START.add(start);
            ACCUM.add(0.0D);
        }
        if (next == 1 || next == 3) {
            CapConfig.setTestOverrides(Boolean.FALSE, null);           // 完全关闭(基线)
        }
        else if (next == 2 || next == 4) {
            CapConfig.setTestOverrides(Boolean.TRUE, Boolean.TRUE);    // 读值不 clamp + travel 回落
        }
        else if (next == 5) {
            CapConfig.setTestOverrides(Boolean.TRUE, Boolean.FALSE);   // 只读值不 clamp(泄漏)
        }
        LOG.info("SELFTEST PHASE {} begin (enabled={}, clampInTravel={})",
                next, CapConfig.enabled(), CapConfig.clampInTravel());
    }

    private static void endPhase() {
        int current = phase;
        closeSegment();
        double total = 0.0D;
        int counted = 0;
        for (int i = 0; i < ZOMBIES.size(); i++) {
            if (ZOMBIES.get(i).isRemoved()) {
                continue;
            }
            total += ACCUM.get(i);
            counted++;
        }
        double avg = counted > 0 ? total / counted : 0.0D;
        double mspt = phaseNanos / 1_000_000.0D / PHASE_TICKS;
        long clamps = TravelCap.clampCount() - phaseClampBase;
        LOG.info("SELFTEST PHASE {} end: mspt={} ms, avgDistance={} blocks/{} ticks, "
                        + "travelClamps={}, alive={}",
                current, String.format("%.4f", mspt), String.format("%.2f", avg), PHASE_TICKS, clamps, counted);

        if (current == 5) {
            phase = 6;
            CapConfig.setTestOverrides(null, null);
            LOG.info("SELFTEST DONE");
        }
        else {
            beginPhase(current + 1);
        }
    }
}
