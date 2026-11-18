package com.hengx.attributecaplift;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.LoggerFactory;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

/**
 * 移动路径的"回落":把 travel 里实际消费的速度压回该属性自己的 {@code maxValue}。
 *
 * <p>AttributeFix 只改了 {@code RangedAttribute.maxValue}(例如移速 0.5),本模组<b>不动它</b>;
 * 读值侧(饰品换算)通过 {@link UncappedAttributes} 拿到未 clamp 的 2.23,而移动侧在
 * {@code travel} / {@code getFrictionInfluencedSpeed} / {@code getFlyingSpeed} 里用本类把值压回 0.5。</p>
 */
public final class TravelCap {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("AttributeCapLift");

    /** debugLog 时的样本上限(避免刷屏)。 */
    private static final AtomicInteger SAMPLES = new AtomicInteger();
    /** 累计被回落的次数(自检/基准用)。 */
    private static volatile long clampCount;

    private TravelCap() {
    }

    /**
     * 把"移动路径实际要用的速度"压回属性上限。
     *
     * @param self 正在移动的实体
     * @param used 原版 getSpeed() 的返回值(可能是未 clamp 的 2.23)
     * @return 压回后的值(0.5)
     */
    public static float cap(LivingEntity self, float used) {
        if (!CapConfig.enabled() || !CapConfig.clampInTravel()) {
            return used;
        }
        Attribute attribute = Attributes.MOVEMENT_SPEED;
        if (!CapConfig.isWatched(attribute)) {
            return used;
        }
        if (!(attribute instanceof RangedAttribute ranged)) {
            return used;
        }
        double max = ranged.getMaxValue();
        if ((double) used <= max) {
            return used;
        }
        clampCount++;
        if (CapConfig.debugLog() && SAMPLES.getAndIncrement() < 20) {
            LOGGER.info("[属性上限解除] 移动回落: raw={} -> cap={} ({})",
                    used, max, self.getType().toShortString());
        }
        return (float) max;
    }

    public static long clampCount() {
        return clampCount;
    }

    public static void resetCount() {
        clampCount = 0L;
    }
}
