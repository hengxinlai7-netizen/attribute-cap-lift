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
 * <h2>2.1.0 为什么没生效(两个独立的错)</h2>
 * <ol>
 *   <li><b>方法名错了</b>:2.1.0 写的是 {@code m_88315_} / {@code lambda$refreshData$1},
 *       而 1.3.7 实际是 {@code renderTooltip}(3 处)/ {@code renderEntry}(4 处)/
 *       {@code lambda$refreshData$2}(1 处)—— 且 tooltip 那 3 处完全没覆盖,
 *       所以面板里「当前 / 基础值 / 最小值 / 最大值」仍显示 raw;</li>
 *   <li><b>目标描述符错了</b>:{@code AttributesGui} 是第三方模组类,它字节码里对原版方法的调用写的是
 *       <b>SRG 名</b> {@code m_22135_};而 2.1.0 写的是 {@code getValue()} + {@code remap = false},
 *       Mixin 按字面匹配 ⇒ 一条都匹配不到,{@code require = 0} 又让它静默跳过。</li>
 * </ol>
 *
 * <h2>2.1.1 的写法</h2>
 * 三个方法各一个 {@code @Redirect},{@code target} 直接用 SRG 描述符
 * {@code Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;m_22135_()D}
 * (与方法体内的字节码逐字一致),{@code remap = false} 保持不变。
 *
 * <p>安全性:目标类不在编译期依赖里({@code targets} 用字符串),没装 ApothicAttributes 时
 * 整个 optional 配置被跳过;即使装了但版本不同,每个注入点 {@code require = 0} 也不会崩。</p>
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

    /** 面板主行:单行条目的数值(4 处调用点)。 */
    @Redirect(
            method = "renderEntry",
            at = @At(value = "INVOKE", target = GET_VALUE_SRG),
            remap = false,
            require = 0)
    private static double attributecaplift$clampInEntry(AttributeInstance instance) {
        return clampForDisplay(instance);
    }

    /** tooltip:「当前 / 基础值 / 最小值 / 最大值」(3 处调用点)。 */
    @Redirect(
            method = "renderTooltip",
            at = @At(value = "INVOKE", target = GET_VALUE_SRG),
            remap = false,
            require = 0)
    private static double attributecaplift$clampInTooltip(AttributeInstance instance) {
        return clampForDisplay(instance);
    }

    /** 「只显示变化过的」过滤条件(1 处调用点)。 */
    @Redirect(
            method = "lambda$refreshData$2",
            at = @At(value = "INVOKE", target = GET_VALUE_SRG),
            remap = false,
            require = 0)
    private static double attributecaplift$clampInFilter(AttributeInstance instance) {
        return clampForDisplay(instance);
    }

    /**
     * 三个重定向共用:读数 → 必要时按 {@code maxValue} 压一次 → 返回显示值。
     *
     * <p>{@code debugLog = true} 时每 5 秒最多打一条,这是"注入确实生效"的唯一运行时证据。</p>
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
