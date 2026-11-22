package com.hengx.attributecaplift;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import com.hengx.attributecaplift.test.SelfTest;

/**
 * 属性上限解除 2.0.0 —— 读值不 clamp,移动时再压回上限。
 *
 * <h2>用户需求</h2>
 * <pre>
 * 玩家移速 = 0.5(AttributeFix 限制照旧,玩家真的跑不快)
 * 疾行项链加伤 = 2.23 × 1.5(换算用"没被限制"的值)
 * </pre>
 *
 * <h2>做法</h2>
 * <ol>
 *   <li>{@link com.hengx.attributecaplift.mixin.AttributeInstanceMixin}:{@code AttributeInstance#getValue()}
 *       返回未 clamp 值(只对 watchedAttributes,默认 movement_speed);</li>
 *   <li>{@link com.hengx.attributecaplift.mixin.TravelCapMixin}:{@code travel} 路径里把速度压回
 *       该属性自己的 {@code maxValue}(AttributeFix 的 0.5)。</li>
 * </ol>
 * <p>不再修改任何 {@code RangedAttribute.maxValue};不再有抬上限 / 定时复查 / 调用方识别。</p>
 */
@Mod(AttributeCapLift.MODID)
public class AttributeCapLift {

    public static final String MODID = "attributecaplift";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AttributeCapLift() {
        CapConfig.register();

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(EventPriority.LOWEST, AttributeCapLift::onLoadComplete);
        modBus.addListener(AttributeCapLift::onConfigReload);

        MinecraftForge.EVENT_BUS.addListener(AttributeCapLift::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(AttributeCapLift::onServerTick);
    }

    private static void onLoadComplete(FMLLoadCompleteEvent event) {
        // 配置此时已加载;enqueueWork 保证在所有并行监听器之后
        event.enqueueWork(() -> {
            CapConfig.reloadCache();
            LOGGER.info("[属性上限解除] 已加载: 读值不 clamp={}, travel 回落={}, debugLog={}",
                    CapConfig.watchedIds(), CapConfig.clampInTravel(), CapConfig.debugLog());
        });
    }

    private static void onConfigReload(ModConfigEvent.Reloading event) {
        try {
            if (event.getConfig().getSpec() != CapConfig.SPEC) {
                return;
            }
            CapConfig.reloadCache();
            LOGGER.info("[属性上限解除] 配置已重载: 读值不 clamp={}, travel 回落={}",
                    CapConfig.watchedIds(), CapConfig.clampInTravel());
        }
        catch (Throwable t) {
            // 配置事件异常不影响游戏
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        if (Boolean.getBoolean("attributecaplift.selftest")) {
            SelfTest.start(event.getServer());
        }
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        SelfTest.onTick(event);
    }
}
