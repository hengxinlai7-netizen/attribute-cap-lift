package com.hengx.attributecaplift.mixin;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hengx.attributecaplift.CapConfig;
import com.hengx.attributecaplift.UncappedAttributes;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 读值侧:被 AttributeFix 夹住的属性,在 {@code getValue()} 返回时把 clamp 去掉。
 *
 * <p>原版调用链:</p>
 * <pre>
 * LivingEntity.getAttributeValue(attr)      // m_21133_
 *   └─ AttributeMap.getValue(attr)
 *       └─ AttributeInstance.getValue()      // m_22135_,本 mixin 注入点
 *           └─ calculateValue()              // m_22137_,末尾 sanitizeValue() 做 min/max clamp
 * </pre>
 *
 * <p><b>零额外开销路径</b>:只有 {@code watchedAttributes} 里的属性、且返回值正好等于 maxValue
 * (说明被上限压住了)时才计算 raw;其余情况直接返回原版值。</p>
 *
 * <p><b>为什么可接受全局不 clamp</b>:本模组的设计就是"读值不设限,移动时再压回",不是按调用方区分。
 * 代价是其它读取方(例如 AI 寻路、攻击击退)也会看到高值 —— 见 README 的"局限"。</p>
 */
@Mixin(AttributeInstance.class)
public abstract class AttributeInstanceMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("AttributeCapLift");
    private static final AtomicInteger SAMPLES = new AtomicInteger();

    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
    private void attributecaplift$uncapWatched(CallbackInfoReturnable<Double> cir) {
        if (!CapConfig.enabled()) {
            return;
        }
        AttributeInstance self = (AttributeInstance) (Object) this;
        Attribute attribute = self.getAttribute();
        if (!CapConfig.isWatched(attribute)) {
            return;
        }
        if (!(attribute instanceof RangedAttribute ranged)) {
            return;
        }
        double clamped = cir.getReturnValueD();
        // 快路径:没有顶到上限(常见),一次比较就返回,不迭代 modifier
        if (clamped < ranged.getMaxValue()) {
            return;
        }
        double raw = UncappedAttributes.computeRaw(self);
        if (raw <= clamped) {
            // 恰好等于上限,或在下限侧被夹:不动
            return;
        }
        if (CapConfig.debugLog() && SAMPLES.getAndIncrement() < 20) {
            ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(attribute);
            LOGGER.info("[属性上限解除] 读值未 clamp: {} raw={} (clamped={})", id, raw, clamped);
        }
        cir.setReturnValue(raw);
    }
}
