package com.hengx.attributecaplift.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hengx.attributecaplift.TravelCap;

import net.minecraft.world.entity.LivingEntity;

/**
 * 移动侧:把 {@code travel} 路径里真正消费的速度压回属性上限。
 *
 * <h2>1.20.1 的移动速度消费点(javap 核对,SRG)</h2>
 * <pre>
 * // 地面:travel → handleRelativeFrictionAndCalculateMovement → getFrictionInfluencedSpeed
 * private float m_21330_(float friction) {              // getFrictionInfluencedSpeed
 *     return this.m_20096_()                             // onGround()
 *         ? this.m_6113_() * (0.21600002F / (f*f*f))     // ← getSpeed(),要回落
 *         : this.m_274460_();                            // getFlyingSpeed()
 * }
 *
 * // 空中/骑乘:getFlyingSpeed 内部也读 getSpeed
 * protected float m_274460_() {                          // getFlyingSpeed
 *     return this.m_6688_() instanceof Player ? this.m_6113_() * 0.1F : 0.02F;
 * }
 *
 * // 游泳:travel 的水分支直接读 getSpeed
 * public void m_7023_(Vec3 travel) {                     // travel
 *     ...
 *     if (this.m_20069_() && this.m_6129_() && ...) {    // isInWater && isAffectedByFluids
 *         ...
 *         f9 = this.m_6113_();                           // ← 游泳速度,要回落
 *     }
 * }
 * </pre>
 *
 * <p>这三处 {@code @Redirect} 都是原版方法,{@code require = 1}:注入失败会直接崩,不会静默失效。
 * 读值侧不受影响 —— 例如疾行项链读 {@code Player#getSpeed()}(直接返回
 * {@code getAttributeValue(MOVEMENT_SPEED)})拿到的仍是未 clamp 的 2.23。</p>
 */
@Mixin(LivingEntity.class)
public abstract class TravelCapMixin {

    /** 地面移动:getFrictionInfluencedSpeed 里的 getSpeed()。 */
    @Redirect(method = "getFrictionInfluencedSpeed",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getSpeed()F"),
            require = 1)
    private float attributecaplift$capGroundSpeed(LivingEntity self) {
        return TravelCap.cap(self, self.getSpeed());
    }

    /** 游泳:travel 水分支里的 getSpeed()。 */
    @Redirect(method = "travel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getSpeed()F"),
            require = 1)
    private float attributecaplift$capTravelSpeed(LivingEntity self) {
        return TravelCap.cap(self, self.getSpeed());
    }

    /** 空中/骑乘:getFlyingSpeed 里的 getSpeed()。 */
    @Redirect(method = "getFlyingSpeed",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getSpeed()F"),
            require = 1)
    private float attributecaplift$capFlyingSpeed(LivingEntity self) {
        return TravelCap.cap(self, self.getSpeed());
    }
}
