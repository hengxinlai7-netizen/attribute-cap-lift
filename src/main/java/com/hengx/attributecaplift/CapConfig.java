package com.hengx.attributecaplift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 配置 {@code config/attributecaplift-common.toml}。
 *
 * <pre>
 * [general]
 *     enabled = true
 *     watchedAttributes = ["minecraft:generic.movement_speed"]
 *     clampInTravel = true
 *     debugLog = false
 * </pre>
 *
 * <p>热路径({@code AttributeInstance#getValue})不能每次都去 ForgeConfigSpec 里查,所以这里把值缓存到
 * volatile 字段;配置加载/重载时调用 {@link #reloadCache()} 刷新。</p>
 */
public final class CapConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> WATCHED_ATTRIBUTES;
    public static final ForgeConfigSpec.BooleanValue CLAMP_IN_TRAVEL;
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOG;

    /** 热路径缓存。 */
    private static volatile boolean enabled = true;
    private static volatile boolean clampInTravel = true;
    private static volatile boolean debug = false;

    /** watchedAttributes 解析成 Attribute 身份集合;懒加载,配置重载时清空。 */
    private static volatile Set<Attribute> watchedSet;

    /** 自检/基准测试用的临时覆盖(null = 正常读配置)。 */
    private static volatile Boolean testEnabled;
    private static volatile Boolean testClampInTravel;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("属性上限解除:读值不 clamp(给'数值转加成'用),移动时再压回 AttributeFix 的上限。").push("general");

        ENABLED = b.comment("总开关。默认 true。", "关掉后本模组对 getValue 零影响(直接返回原版 clamp 值)。")
                .define("enabled", true);

        WATCHED_ATTRIBUTES = b.comment("这些属性在 getValue 时返回'未 clamp 值'(给饰品换算用)。",
                        "默认只有移速:已知的'数值转加成'饰品(疾行项链/光速螺旋桨)都吃移速。",
                        "警告:加进来的属性必须同时有对应的'作用点回落',否则它会真的变强。",
                        "示例: [\"minecraft:generic.movement_speed\"]")
                .defineListAllowEmpty("watchedAttributes",
                        Collections.singletonList("minecraft:generic.movement_speed"),
                        CapConfig::isString);

        CLAMP_IN_TRAVEL = b.comment("是否在 LivingEntity 的移动路径里把移速压回该属性的 maxValue(AttributeFix 给的值)。",
                        "这是'玩家真的跑不快'的核心开关,默认 true。",
                        "关掉 = 抬上限的效果会直接作用到玩家移动上(玩家会真的变快)。")
                .define("clampInTravel", true);

        DEBUG_LOG = b.comment("打印每次'读值未 clamp'和'移动回落'的样本(节流,最多几十行)。默认 false。")
                .define("debugLog", false);

        b.pop();
        SPEC = b.build();
    }

    private CapConfig() {
    }

    private static boolean isString(Object o) {
        return o instanceof String;
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "attributecaplift-common.toml");
    }

    // ------------------------------------------------------------- 热路径

    public static boolean enabled() {
        Boolean o = testEnabled;
        return o != null ? o : enabled;
    }

    public static boolean clampInTravel() {
        Boolean o = testClampInTravel;
        return o != null ? o : clampInTravel;
    }

    public static boolean debugLog() {
        return debug;
    }

    /** 属性是否在 watchedAttributes 里(按 Attribute 身份判断,热路径只做一次哈希)。 */
    public static boolean isWatched(Attribute attr) {
        Set<Attribute> s = watchedSet;
        if (s == null) {
            s = buildWatchedSet();
            watchedSet = s;
        }
        return s.contains(attr);
    }

    private static Set<Attribute> buildWatchedSet() {
        Set<Attribute> set = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String id : safeList(WATCHED_ATTRIBUTES)) {
            if (id == null || id.isBlank()) {
                continue;
            }
            ResourceLocation rl = ResourceLocation.tryParse(id.trim());
            if (rl == null) {
                AttributeCapLift.LOGGER.warn("[属性上限解除] watchedAttributes 里的 id 不合法,已忽略: {}", id);
                continue;
            }
            Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(rl);
            if (attr == null) {
                AttributeCapLift.LOGGER.warn("[属性上限解除] watchedAttributes 里的属性不存在,已忽略: {}", id);
                continue;
            }
            set.add(attr);
        }
        return Collections.unmodifiableSet(set);
    }

    // ------------------------------------------------------------- 生命周期

    /** 配置加载/重载后刷新热路径缓存。 */
    public static void reloadCache() {
        try {
            enabled = ENABLED.get();
            clampInTravel = CLAMP_IN_TRAVEL.get();
            debug = DEBUG_LOG.get();
        }
        catch (RuntimeException | LinkageError e) {
            // 配置还没加载:保持默认值
        }
        watchedSet = null;
        if (debug) {
            AttributeCapLift.LOGGER.info("[属性上限解除] 配置: enabled={} clampInTravel={} watched={}",
                    enabled, clampInTravel, safeList(WATCHED_ATTRIBUTES));
        }
    }

    /** 自检用:临时覆盖 enabled/clampInTravel(传 null 恢复)。 */
    public static void setTestOverrides(Boolean enabledOverride, Boolean clampOverride) {
        testEnabled = enabledOverride;
        testClampInTravel = clampOverride;
    }

    public static List<String> watchedIds() {
        List<String> out = new ArrayList<>();
        for (String s : safeList(WATCHED_ATTRIBUTES)) {
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<? extends String> safeList(ForgeConfigSpec.ConfigValue<List<? extends String>> value) {
        try {
            return value.get();
        }
        catch (RuntimeException | LinkageError e) {
            return Collections.singletonList("minecraft:generic.movement_speed");
        }
    }
}
