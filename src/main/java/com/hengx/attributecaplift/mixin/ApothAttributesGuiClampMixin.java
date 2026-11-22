package com.hengx.attributecaplift.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hengx.attributecaplift.CapConfig;
import com.hengx.attributecaplift.PanelClamp;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

/**
 * 让神化(ApothicAttributes)的属性面板显示"被属性上限压过"的值。
 *
 * <p>{@code AttributesGui} 里有 <b>8 处</b>直接调用 {@code AttributeInstance#getValue()},
 * <b>0 处</b>调用 {@code getMaxValue} —— 所以它显示的就是本模组
 * {@code AttributeInstanceMixin} 改出来的 raw 值。这里把每个调用点重定向到
 * {@link PanelClamp#decide},<b>只影响面板像素</b>。</p>
 *
 * <h2>8 处调用点分布(javap -c 偏移)</h2>
 * <ul>
 *   <li>{@code m_88315_} (render,原版映射名):偏移 313 / 335 / 357 —— 3 处</li>
 *   <li>{@code renderEntry}:偏移 436 / 456 / 484(另一个 335 属于此方法内部) —— 3 处</li>
 *   <li>{@code lambda$refreshData$1}(过滤"只显示变化过的"):1 处</li>
 * </ul>
 * 这里<b>逐个方法</b>写 {@code @Redirect},不用 {@code method = "*"},避免误命中其它方法。
 *
 * <ul>
 *   <li>{@code targets} 用字符串:编译期不依赖 ApothicAttributes,缺它也能编译;</li>
 *   <li>{@code remap = false}:第三方类,名字不参与原版映射;</li>
 *   <li>{@code require = 0}:没装 ApothicAttributes 时 Mixin 静默跳过,不报
 *       {@code InvalidInjectionException}。</li>
 * </ul>
 */
@Mixin(targets = "dev.shadowsoffire.attributeslib.client.AttributesGui", remap = false)
public final class ApothAttributesGuiClampMixin {

    private ApothAttributesGuiClampMixin() {
    }

    /** {@code AttributesGui#render} —— 面板主渲染里的 3 处读数。 */
    @Redirect(
            method = "m_88315_",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;getValue()D"),
            remap = false,
            require = 0)
    private static double attributecaplift$clampInRender(AttributeInstance instance) {
        return clampForDisplay(instance);
    }

    /** {@code AttributesGui#renderEntry} —— 单行条目的数值(含 hover 显示)。 */
    @Redirect(
            method = "renderEntry",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;getValue()D"),
            remap = false,
            require = 0)
    private static double attributecaplift$clampInEntry(AttributeInstance instance) {
        return clampForDisplay(instance);
    }

    /** {@code AttributesGui#lambda$refreshData$1} —— "只显示变化过的"过滤条件里的读数。 */
    @Redirect(
            method = "lambda$refreshData$1",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;getValue()D"),
            remap = false,
            require = 0)
    private static double attributecaplift$clampInFilter(AttributeInstance instance) {
        return clampForDisplay(instance);
    }

    /**
     * 三个重定向共用的判定:读到的 raw 值若超过该属性的 {@code maxValue} 且属于 watched 属性,
     * 就返回 {@code maxValue};否则原样返回。
     */
    private static double clampForDisplay(AttributeInstance instance) {
        double raw = instance.getValue();          // 仍是 AttributeInstanceMixin 处理后的 raw
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
        return Math.min(raw, ranged.getMaxValue());
    }
}
