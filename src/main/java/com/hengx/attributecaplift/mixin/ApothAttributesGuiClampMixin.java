package com.hengx.attributecaplift.mixin;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hengx.attributecaplift.CapConfig;

/**
 * 让神化(ApothicAttributes)的属性面板 + tooltip 显示"被属性上限压过"的值。
 *
 * <h2>历次踩坑记录</h2>
 * <ol>
 *   <li><b>2.1.0</b>:方法名错({@code m_88315_} / {@code lambda$refreshData$1} 不存在,
 *       且没覆盖 {@code renderTooltip});</li>
 *   <li><b>2.1.1</b>:方法名对了,但三个 handler 写成 {@code private static} ——
 *       {@code @Redirect} 的 handler 修饰符必须与<b>宿主方法</b>一致,而
 *       {@code renderEntry} / {@code renderTooltip} / {@code lambda$refreshData$2}
 *       都是<b>实例方法</b>,于是 {@code Mixin apply failed: 'static' modifier of handler method
 *       does not match target} —— 这条是硬校验,{@code require = 0} 挡不住;</li>
 *   <li><b>2.1.2</b>(本版):三个 handler 改为<b>实例方法</b>,{@link #clampForDisplay} 保持 static。</li>
 * </ol>
 *
 * <h2>选择器</h2>
 * {@code AttributesGui} 是第三方类,字节码里调用原版方法写的是 <b>SRG 名</b>
 * {@code m_22135_},所以 {@code @At} 的 target 用 SRG 描述符,{@code remap = false} 保持字面匹配。
 */
@Mixin(targets = "dev.shadowsoffire.attributeslib.client.AttributesGui", remap = false)
public final class ApothAttributesGuiClampMixin {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String TAG = "[属性上限解除] 面板 clamp";

    /** 目标描述符:必须与 {@code AttributesGui} 字节码里的调用逐字一致(SRG 名)。 */
    private static final String GET_VALUE_SRG =
            "Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;m_22135_()D";

    /** 节流:5 秒最多一条。 */
    private static long lastLogAt = 0L;
    private static final long LOG_INTERVAL_MS = 5000L;

    private ApothAttributesGuiClampMixin() {
    }

    /** 面板主行:单行条目的数值(4 处调用点)。<b>实例方法</b>——宿主 {@code renderEntry} 是实例方法。 */
    @Redirect(
            method = "renderEntry",
            at = @At(value = "INVOKE", target = GET_VALUE_SRG),
            remap = false,
            require = 0)
    private double attributecaplift$clampInEntry(AttributeInstance instance) {
        return clampForDisplay(instance);
    }

    /** tooltip:「当前 / 基础值 / 最小值 / 最大值」(3 处调用点)。<b>实例方法</b>。 */
    @Redirect(
            method = "renderTooltip",
            at = @At(value = "INVOKE", target = GET_VALUE_SRG),
            remap = false,
            require = 0)
    private double attributecaplift$clampInTooltip(AttributeInstance instance) {
        return clampForDisplay(instance);
    }

    /** 「只显示变化过的」过滤条件(1 处调用点)。<b>实例方法</b>。 */
    @Redirect(
            method = "lambda$refreshData$2",
            at = @At(value = "INVOKE", target = GET_VALUE_SRG),
            remap = false,
            require = 0)
    private double attributecaplift$clampInFilter(AttributeInstance instance) {
        return clampForDisplay(instance);
    }

    /**
     * 三个重定向共用:读数 → 必要时按 {@code maxValue} 压一次 → 返回显示值。
     *
     * <p>这个方法本身是 {@code static},它只是被三个实例 handler 调用,不参与 Mixin 的修饰符校验。</p>
     */
    private static double clampForDisplay(AttributeInstance instance) {
        double raw = instance.getValue();
        if (!CapConfig.enabled() || !CapConfig.panelClamp()) {
            return raw;
        }
        Attribute attribute = instance.getAttribute();
        if (!CapConfig.isWatched(attribute)) {
            return raw;
        }
        if (!(attribute instanceof RangedAttribute ranged)) {
            return raw;
        }
        double max = ranged.getMaxValue();
        double clamped = Math.min(raw, max);
        if (clamped != raw && CapConfig.debugLog()) {
            long now = System.currentTimeMillis();
            if (now - lastLogAt >= LOG_INTERVAL_MS) {
                lastLogAt = now;
                LOG.info("{}: {} raw={} -> {} (max={})",
                        TAG, attribute.getDescriptionId(), raw, clamped, max);
            }
        }
        return clamped;
    }
}
