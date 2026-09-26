package com.xingmot.elementmark.circuit;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 电路板电压角标的配置（Forge 自带配置系统）。
 *
 * <p>落盘位置：{@code config/elementmark-client.toml}，由 Forge 在
 * {@code ModConfig.Type.CLIENT} 下自动创建与热重载。
 *
 * <p><b>为什么不并进 {@code config/elementmark.txt}</b>：那个文件是"材料名 -&gt; 缩写"的
 * 键值表，格式为每行 {@code 段名:值}，天生表达不了列表；而本模块需要的是
 * <b>字符串列表</b>（{@code overrides}）。硬塞进去要么破坏现有解析口径，
 * 要么再发明一套转义规则。Forge 的 toml 列表开箱即用，且改完即时生效，故选它。
 * 两个配置各管各的：{@code elementmark.txt} 管元素/材料缩写，本文件管电路板。
 *
 * <p><b>读取失败一律回退默认值</b>：{@code .get()} 在配置尚未加载完成时（例如
 * 构造期被触碰）会抛异常，而调用点在渲染热路径上，绝不能因为配置没就绪就把 GUI 打崩。
 * 三个取值方法因此都吞异常并返回出厂默认值。
 */
public final class CircuitConfig {

    /** Forge 配置规格；需在 mod 构造期注册 */
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> OVERRIDES;
    private static final ForgeConfigSpec.DoubleValue RAINBOW_CYCLE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment(
                        "Circuit board voltage badge.",
                        "",
                        "When an item is recognized as a GregTech circuit (tag gtceu:circuits/*,",
                        "a \"XX-Tier Circuit\" tooltip, or the built-in item table), its voltage",
                        "tier (ULV ~ MAX) is drawn in the badge corner and REPLACES the element",
                        "badge, since a circuit never carries a chemical element.",
                        "",
                        "Tier colours are GT's own VC table; MAX is drawn as an animated rainbow.")
                .push("circuit");

        ENABLED = builder
                .comment("Draw voltage tier badges on circuit boards.")
                .define("enabled", true);

        OVERRIDES = builder
                .comment(
                        "Manual tier mapping, one entry per list element: \"<item>=<TIER>\".",
                        "",
                        "The left side accepts any of:",
                        "  - the full id      gtceu:basic_electronic_circuit",
                        "  - the path only    basic_electronic_circuit",
                        "  - the display name shown in the current language, e.g. 基础电子电路",
                        "The right side is one of (case-insensitive):",
                        "  ULV LV MV HV EV IV LuV ZPM UV UHV UEV UIV UXV OpV MAX",
                        "",
                        "Entries are matched before every automatic source, so they also serve",
                        "as an override for items the automatic detection gets wrong.",
                        "Example: [\"gtceu:my_circuit=ZPM\", \"我的电路=MAX\"]")
                // 显式写 List.<String>of()：不加类型见证时 T 会被推断成 Object，
                // 返回的 ConfigValue<List<? extends Object>> 便赋不给下面的字段类型
                .defineList("overrides", List.<String>of(), o -> o instanceof String);

        RAINBOW_CYCLE = builder
                .comment("Seconds for one full colour cycle of the animated MAX badge.")
                .defineInRange("rainbow_cycle_seconds", 4.0D, 0.5D, 60.0D);

        builder.pop();
        SPEC = builder.build();
    }

    private CircuitConfig() {}

    /** 是否绘制电路板电压角标 */
    public static boolean enabled() {
        try {
            return ENABLED.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 用户手写的等级映射（已滤掉空行 / null）。
     *
     * <p>每次调用都重新过滤而不缓存：Forge 的 {@code ConfigValue} 自带值缓存，
     * 而本列表很短（通常为空），过滤开销可忽略；缓存反而会在配置重载后变成脏数据。
     */
    public static List<String> overrides() {
        try {
            List<? extends String> raw = OVERRIDES.get();
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(raw.size());
            for (String entry : raw) {
                if (entry != null && !entry.isBlank()) {
                    out.add(entry);
                }
            }
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** MAX 彩虹一个完整周期的秒数 */
    public static float rainbowCycleSeconds() {
        try {
            return RAINBOW_CYCLE.get().floatValue();
        } catch (Throwable t) {
            return 4.0F;
        }
    }
}
