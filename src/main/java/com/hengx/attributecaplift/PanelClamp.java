package com.hengx.attributecaplift;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

/**
 * 「只掐显示」的纯逻辑:数值面板要显示被属性上限压过的值,而玩法仍然读未 clamp 的 raw 值。
 *
 * <p>起因:{@link com.hengx.attributecaplift.mixin.AttributeInstanceMixin} 把
 * {@code AttributeInstance#getValue()} 对 watched 属性改成了"返回未 clamp 值"。
 * 这是全局改动,所以任何读 {@code getValue()} 的界面都会显示 raw ——
 * 包括神化(ApothicAttributes)的属性面板 {@code AttributesGui}。
 * 用户希望面板显示"被 AttributeFix 上限压过"的值,而战斗/移动逻辑仍用 raw。</p>
 *
 * <p>这里的 {@link #decide(AttributeInstance, double)} 由
 * {@code mixin.ApothAttributesGuiClampMixin} 在 {@code AttributesGui} 的
 * {@code getValue()} 调用点重定向进来。<b>不修改 getValue 本身</b>。</p>
 */
public final class PanelClamp {

    private PanelClamp() {}

    /** 面板要显示的值:把已经拿到的 raw 值按上限压一次(热路径,无分配)。 */
    public static double decide(AttributeInstance instance, double raw) {
        if (instance == null) {
            return raw;
        }
        Attribute attribute;
        try {
            attribute = instance.getAttribute();
        }
        catch (RuntimeException | LinkageError e) {
            return raw;
        }
        return clamp(CapConfig.enabled(), CapConfig.panelClamp(), CapConfig.isWatched(attribute),
                attribute instanceof RangedAttribute,
                raw, attribute instanceof RangedAttribute ranged ? ranged.getMaxValue() : 0.0D);
    }

    /**
     * 纯函数版本(自检直接断言,不需要真实 {@code AttributeInstance})。
     *
     * @param enabled    总开关
     * @param panelClamp 面板开关(本功能)
     * @param watched    属性是否在 watchedAttributes 里
     * @param ranged     属性是不是 {@link RangedAttribute}(只有它有 maxValue)
     * @param raw        已经拿到的值(可能是未 clamp 的 raw)
     * @param maxValue   {@code RangedAttribute#getMaxValue()}
     */
    public static double clamp(boolean enabled, boolean panelClamp, boolean watched, boolean ranged,
                               double raw, double maxValue) {
        if (!enabled || !panelClamp) {
            return raw;      // 关掉任何一层都保持原样
        }
        if (!watched || !ranged) {
            return raw;      // 不是我们抬上限的属性,或没有上限概念
        }
        return Math.min(raw, maxValue);
    }
}
