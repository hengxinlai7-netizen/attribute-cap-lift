package com.hengx.attributecaplift;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

/**
 * 未 clamp 的属性值计算 —— 与 {@code AttributeInstance#calculateValue()} 逐位对齐,只去掉最后一步
 * {@code RangedAttribute#sanitizeValue(Mth.clamp(min,max))}。
 *
 * <h2>原版字节码(1.20.1,{@code AttributeInstance.m_22137_} = calculateValue)</h2>
 * <pre>
 *   0: aload_0
 *   1: invokevirtual m_22115_()D              // getBaseValue()
 *   4: dstore_1                                // d = base
 *        ... ADDITION 循环: d += m.getAmount()
 *  49: dload_1 / 50: dstore_3                  // d1 = d
 *        ... MULTIPLY_BASE 循环: d1 += d * m.getAmount()
 * 100: ... MULTIPLY_TOTAL 循环: d1 *= (1 + m.getAmount())
 * 149: aload_0
 * 150: getfield f_22088_                        // this.attribute
 * 153: dload_3
 * 154: invokevirtual Attribute.m_6740_(D)D      // sanitizeValue(d1)  ← clip 在这一步
 * 157: dreturn
 * </pre>
 *
 * <p>所以 raw = d1(第 153 行之前的那个值)。本类按同样的顺序、同样的 iteration order 累加,
 * <b>不是</b>数学等价改写(例如不能把 MULTIPLY_BASE 写成 {@code d * (1 + Σa)},浮点结果会差 1 ulp)。</p>
 */
public final class UncappedAttributes {

    private UncappedAttributes() {
    }

    /**
     * 计算"没被 min/max 夹住"的属性值。
     *
     * @param instance 属性实例
     * @return 与 {@code calculateValue()} 的 d1 完全相同(只少了 sanitizeValue)
     */
    public static double computeRaw(AttributeInstance instance) {
        double d = instance.getBaseValue();
        for (AttributeModifier modifier : instance.getModifiers(AttributeModifier.Operation.ADDITION)) {
            d += modifier.getAmount();
        }
        double d1 = d;
        for (AttributeModifier modifier : instance.getModifiers(AttributeModifier.Operation.MULTIPLY_BASE)) {
            d1 += d * modifier.getAmount();
        }
        for (AttributeModifier modifier : instance.getModifiers(AttributeModifier.Operation.MULTIPLY_TOTAL)) {
            d1 *= 1.0D + modifier.getAmount();
        }
        return d1;
    }

    /** 该属性当前被上限夹住时返回 true(只判断 max 侧)。 */
    public static boolean isCappedByMax(AttributeInstance instance) {
        if (!(instance.getAttribute() instanceof RangedAttribute ranged)) {
            return false;
        }
        return instance.getValue() >= ranged.getMaxValue();
    }
}
