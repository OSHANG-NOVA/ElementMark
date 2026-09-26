package com.xingmot.elementmark.circuit;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.registries.ForgeRegistries;

import org.slf4j.Logger;

/**
 * 电路板 / 分级组件识别器：物品 -&gt; 电压等级下标。
 *
 * <p><b>识别顺序</b>（先命中先用，与用户确认过的优先级一致）：
 * <ol>
 *   <li><b>配置里的手写映射</b>（{@code overrides}）——用户显式写的永远最大，
 *       它同时也是"自动识别认错了"的补救手段；</li>
 *   <li><b>物品 tag</b>，两条并列的分支：
 *       <ul>
 *         <li>路径形如 {@code circuits/<tier>}（GT 本体用 {@code gtceu:circuits/ulv … max}）。
 *             这是最权威的来源——附属模组新加的电路只要按 GT 的规范打了标签，
 *             无需本模组做任何适配；</li>
 *         <li>路径命中 {@link #COMPONENT_TAGS}（GT 的 9 类分级组件：电动马达 / 电力活塞 /
 *             电动泵 / 流体校准器 / 传送带 / 机械臂 / 力场发生器 / 发射器 / 传感器）。
 *             这类物品的 tag 只说明"它是个组件"，等级要从注册名前缀
 *             {@code <tier>_<component>} 里取，见 {@link #tierFromComponentPath}。</li>
 *       </ul></li>
 *   <li><b>内置识别表</b>（{@link CircuitNames}）：按注册名 path，再按当前语言显示名；</li>
 *   <li><b>Tooltip</b>：扫描 {@code "XX-Tier Circuit"} / {@code "XX级电路"} 这类文案。
 *       GT 本体每个电路的第二行 tooltip 都写着等级，附属模组也普遍照抄这个格式。</li>
 * </ol>
 *
 * <p><b>为什么组件必须走"标签 + 名字前缀"</b>：GT 只给电路写了等级 tooltip
 * （{@code item.gtceu.<circuit>.tooltip.1 = "§6HV-Tier Circuit"}），
 * <b>组件一个都没有</b>（已逐条核对 7.5.3 的 {@code en_us.json}：组件只有
 * "Transfers Items at specific rates as Cover" 这类功能说明），内置表也只收录电路。
 * 于是组件原本四条途径一条都命中不了。标签本身也不带等级信息
 * （{@code gtceu:electric_motors} 把所有等级混在一起），
 * 但 GT 的注册名严格是 {@code <tier>_<component>}，等级从名字里就能读出来。
 *
 * <p><b>为什么把内置表排在 Tooltip 前面</b>（看似与"标签 &gt; Tooltip &gt; 名字"相反）：
 * 内置表只收录 GT 本体的 28 个电路，而 GT 本体这些物品的 Tooltip 与内置表<b>必然同值</b>
 * （表就是从 GT 的 tag 与语言文件抄的），所以两者的相对顺序对任何 GT 物品都不会产生
 * 不同结果。反过来，先查内置表能避开一次 {@code getTooltipLines}——那要构造整份
 * tooltip 列表（含所有 mod 的 tooltip 回调），是这几条途径里最贵的一步。
 * 也就是说：<b>语义上仍是"Tooltip 优先于名字"，实现上把等价的廉价查表提到了前面</b>。
 *
 * <p><b>缓存</b>：按 {@link Item} 缓存结果（含"不是电路"这一否定结论）。GUI 每帧都会
 * 问一次，不缓存的话 Tooltip 构造会成为主要开销。缓存只在配置重载 / 资源重载时清空。
 * 标签在 datapack 加载后不再变化，而 GUI 渲染必然发生在加载完成之后，故否定结论可以安全缓存。
 */
public final class CircuitDetector {

    /** "不是电路板"。用 -1 而不是 0，是因为 0 是合法的 ULV */
    public static final int NOT_CIRCUIT = -1;

    /** 电路标签的路径前缀；GT 用的是 {@code gtceu:circuits/<tier>} */
    private static final String TAG_PREFIX = "circuits/";

    /**
     * GT 的 9 类"分级组件"标签路径（不带命名空间，与 {@link #TAG_PREFIX} 一样只认路径）。
     *
     * <p>不限定命名空间是刻意的：附属模组把自己的组件挂到 {@code gtceu:} 这套标签下
     * 是标准做法，限定命名空间反而会把它们挡在外面。误命中的代价也很低——
     * 标签命中后还要过一遍 {@link #tierFromComponentPath}，名字前缀读不出等级就放弃。
     */
    private static final Set<String> COMPONENT_TAGS = Set.of(
            "electric_pistons", "electric_motors", "electric_pumps", "fluid_regulators",
            "conveyor_modules", "robot_arms", "field_generators", "emitters", "sensors");

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 物品 -> 等级（含 {@link #NOT_CIRCUIT}）。ConcurrentHashMap 不允许 null value，故用 int 装箱 */
    private static final Map<Item, Integer> CACHE = new ConcurrentHashMap<>();

    /**
     * 已解析的手写映射：小写键 -&gt; 等级。
     *
     * <p>与 {@link #overridesRaw} 一起构成"只在配置真的变了才重新解析"的缓存：
     * Forge 的配置项每次 {@code get()} 都返回新列表实例，直接比较对象没有意义，
     * 故比较内容。
     */
    private static volatile Map<String, Integer> overrides = Map.of();
    private static volatile List<String> overridesRaw = List.of();

    private CircuitDetector() {}

    /**
     * 判断物品是否为电路板并给出电压等级。
     *
     * @return 等级下标（{@code 0} = ULV … {@code 14} = MAX）；不是电路板时返回
     *         {@link #NOT_CIRCUIT}
     */
    public static int detect(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return NOT_CIRCUIT;
        }
        Item item = stack.getItem();
        Integer cached = CACHE.get(item);
        if (cached != null) {
            return cached;
        }
        int tier = compute(stack);
        CACHE.put(item, tier);
        return tier;
    }

    /** 清空缓存。配置重载后必须调用，否则手写映射的改动不会生效 */
    public static void invalidate() {
        CACHE.clear();
        // 同时让手写映射重新解析（配置内容可能已经变了）
        overridesRaw = List.of();
        overrides = Map.of();
    }

    private static int compute(ItemStack stack) {
        Item item = stack.getItem();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        String path = id == null ? null : id.getPath();

        // 途径 1：配置里的手写映射（全名 / 仅 path / 显示名，三种键都认）
        int byOverride = fromOverrides(id, path, stack);
        if (byOverride != NOT_CIRCUIT) {
            return byOverride;
        }

        // 途径 2：物品 tag —— 最权威，附属模组无需适配（电路按 circuits/<tier>，组件按名字前缀）
        int byTag = fromTags(stack, path);
        if (byTag != NOT_CIRCUIT) {
            return byTag;
        }

        // 途径 3：内置识别表（注册名 path -> 当前语言显示名）
        int byPath = CircuitNames.byPath(path);
        if (byPath != NOT_CIRCUIT) {
            return byPath;
        }
        String hoverName = displayName(stack);
        int byName = CircuitNames.byName(hoverName);
        if (byName != NOT_CIRCUIT) {
            return byName;
        }

        // 途径 4：Tooltip 文案（最通用，也最贵，故放最后）
        return fromTooltip(stack);
    }

    /** 当前语言下的显示名；取不到返回 {@code null} */
    private static String displayName(ItemStack stack) {
        try {
            Component name = stack.getHoverName();
            return name == null ? null : name.getString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 途径 1：配置手写映射。
     *
     * <p>键同时按三种写法试：完整 id、仅 path、显示名——用户不必知道"注册名"与
     * "显示名"的区别，写哪个都能命中。
     */
    private static int fromOverrides(ResourceLocation id, String path, ItemStack stack) {
        Map<String, Integer> map = overrides();
        if (map.isEmpty()) {
            return NOT_CIRCUIT;
        }
        if (id != null) {
            Integer hit = map.get(id.toString().toLowerCase(Locale.ROOT));
            if (hit != null) {
                return hit;
            }
        }
        if (path != null) {
            Integer hit = map.get(path.toLowerCase(Locale.ROOT));
            if (hit != null) {
                return hit;
            }
        }
        String name = displayName(stack);
        if (name != null && !name.isEmpty()) {
            Integer hit = map.get(name.trim().toLowerCase(Locale.ROOT));
            if (hit != null) {
                return hit;
            }
        }
        return NOT_CIRCUIT;
    }

    /**
     * 解析并缓存手写映射。
     *
     * <p>条目格式 {@code "<键>=<等级>"}；等级名交给 {@link VoltageTier#indexOf} 解析
     * （不区分大小写，故 {@code luv} / {@code LuV} 都能认）。格式不对的条目跳过并记警告，
     * 不影响其余条目——与 {@code elementmark.txt} 的容错口径一致。
     */
    private static Map<String, Integer> overrides() {
        List<String> raw = CircuitConfig.overrides();
        if (raw.equals(overridesRaw)) {
            return overrides;
        }
        Map<String, Integer> parsed = new HashMap<>();
        for (String entry : raw) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                LOGGER.warn("[ElementMark] 电路配置项格式应为 \"<物品>=<等级>\"，已跳过：{}", entry);
                continue;
            }
            String key = entry.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String tierName = entry.substring(eq + 1).trim();
            int tier = VoltageTier.indexOf(tierName);
            if (key.isEmpty() || tier == NOT_CIRCUIT) {
                LOGGER.warn("[ElementMark] 电路配置项无法识别（物品为空或等级名无效），已跳过：{}", entry);
                continue;
            }
            parsed.put(key, tier);
        }
        overridesRaw = List.copyOf(raw);
        overrides = Map.copyOf(parsed);
        if (!parsed.isEmpty()) {
            LOGGER.info("[ElementMark] 电路手写映射已加载 {} 条", parsed.size());
        }
        return overrides;
    }

    /**
     * 途径 2：按物品标签取等级，分两条分支。
     *
     * <p><b>分支一 —— 电路</b>：只认"路径以 {@code circuits/} 开头"的标签，
     * <b>不限定命名空间</b>：GT 本体用 {@code gtceu:}，但附属模组用别的命名空间
     * （甚至 {@code c:}）打同一套标签是常见做法，限定命名空间反而会把它们挡在外面。
     * 取尾段交给 {@link VoltageTier#indexOf}，于是 {@code circuits/luv}、{@code circuits/MAX} 都能认。
     *
     * <p><b>分支二 —— 分级组件</b>：路径命中 {@link #COMPONENT_TAGS} 时，
     * 等级从注册名 path 的前缀取（见 {@link #tierFromComponentPath}）。
     * 标签在这条分支里只起"这是个组件"的判定作用——它本身不带等级信息。
     *
     * <p>两分支共用一个循环而不是各扫一遍 {@code getTags()}：{@code getTags()}
     * 会构造一个 Stream，扫两遍等于把这层开销翻倍，而它恰好在 GUI 每帧的路径上。
     * 一旦命中就直接 return，不做"先收集全部再挑"。
     *
     * @param path 物品注册名的 path（{@code gtceu:zpm_electric_motor} → {@code zpm_electric_motor}）
     */
    private static int fromTags(ItemStack stack, String path) {
        try {
            for (TagKey<Item> tag : stack.getTags().toList()) {
                String p = tag.location().getPath();
                if (p.startsWith(TAG_PREFIX)) {
                    int byCircuitTag = VoltageTier.indexOf(p.substring(TAG_PREFIX.length()));
                    if (byCircuitTag != NOT_CIRCUIT) {
                        return byCircuitTag;
                    }
                    continue;
                }
                if (COMPONENT_TAGS.contains(p)) {
                    int byComponent = tierFromComponentPath(path);
                    if (byComponent != NOT_CIRCUIT) {
                        return byComponent;
                    }
                }
            }
        } catch (Throwable t) {
            // 标签查询失败只影响"识别得全不全"，绝不能让异常冒到渲染路径上
        }
        return NOT_CIRCUIT;
    }

    /**
     * 从组件物品的注册名里读等级：GT 的命名是 {@code <tier>_<component>}
     * （如 {@code gtceu:zpm_electric_motor}），取第一个 {@code _} 之前的那一段当等级名。
     *
     * <p>只切<b>第一个</b>下划线，不做"按 {@code _} 全切再逐个试"：组件名本身含下划线
     * （{@code electric_motor} / {@code robot_arm} / {@code field_generator}），
     * 全切会把 {@code motor} 这类片段也当成候选；而等级名里不含下划线，
     * 第一个下划线之前的那一段<b>必然</b>是等级，不需要猜。
     *
     * <p>切出来的段交给 {@link VoltageTier#indexOf}（不区分大小写），
     * 于是 {@code luv} / {@code opv} 这类小写写法也能认。
     * 注意 {@code luv} 与 {@code lv} 不会互相误伤——{@code indexOf} 是整段等值比较，
     * 不是前缀匹配。
     *
     * @return 等级下标；名字前缀不是已知等级时返回 {@link #NOT_CIRCUIT}
     */
    private static int tierFromComponentPath(String path) {
        if (path == null) {
            return NOT_CIRCUIT;
        }
        int underscore = path.indexOf('_');
        if (underscore <= 0) {
            return NOT_CIRCUIT;
        }
        return VoltageTier.indexOf(path.substring(0, underscore));
    }

    /**
     * 途径 4：扫 Tooltip 里的等级文案。
     *
     * <p>GT 的格式是英文 {@code "§6HV-Tier Circuit"}、中文 {@code "§6HV级电路"}，
     * 附属模组普遍照抄。做法是先剥掉 {@code §} 颜色码，再对每一行按
     * {@code "<等级>-Tier"} / {@code "<等级>级"} 两种模式匹配。
     *
     * <p><b>为什么要剥颜色码</b>：等级名与后缀之间可能插着格式化码，
     * 直接 {@code contains} 会漏；剥掉之后是纯文本，匹配才可靠。
     *
     * <p><b>大小写</b>：先按 GT 的原样拼写（{@code LuV} / {@code OpV}）精确匹配，
     * 这样 {@code "LuV-Tier"} 不会因为大小写宽松而被 {@code LV} 抢先命中；
     * 精确匹配失败后才退到不区分大小写的宽松匹配，兼容附属模组写成 {@code LUV-Tier} 的情况。
     */
    private static int fromTooltip(ItemStack stack) {
        List<Component> lines;
        try {
            lines = stack.getTooltipLines(null, TooltipFlag.NORMAL);
        } catch (Throwable t) {
            // 某些模组的 tooltip 回调依赖玩家上下文，传 null 可能抛异常；
            // 这只影响"识别得全不全"，绝不能让异常冒到渲染路径上
            return NOT_CIRCUIT;
        }
        if (lines == null || lines.isEmpty()) {
            return NOT_CIRCUIT;
        }
        for (Component line : lines) {
            String text = stripColors(line);
            if (text.isEmpty()) {
                continue;
            }
            int exact = matchTier(text, false);
            if (exact != NOT_CIRCUIT) {
                return exact;
            }
        }
        // 精确匹配全落空后再宽松扫一遍，避免"宽松匹配把精确结果抢走"
        for (Component line : lines) {
            String text = stripColors(line);
            if (text.isEmpty()) {
                continue;
            }
            int loose = matchTier(text, true);
            if (loose != NOT_CIRCUIT) {
                return loose;
            }
        }
        return NOT_CIRCUIT;
    }

    /** 在单行文本里找 {@code "<等级>-Tier"} / {@code "<等级>级"} */
    private static int matchTier(String text, boolean ignoreCase) {
        for (int tier = 0; tier < VoltageTier.count(); tier++) {
            String name = VoltageTier.NAMES[tier];
            if (containsTier(text, name, ignoreCase)) {
                return tier;
            }
        }
        return NOT_CIRCUIT;
    }

    private static boolean containsTier(String text, String name, boolean ignoreCase) {
        if (ignoreCase) {
            String lower = text.toLowerCase(Locale.ROOT);
            String n = name.toLowerCase(Locale.ROOT);
            return lower.contains(n + "-tier") || lower.contains(n + "级");
        }
        return text.contains(name + "-Tier") || text.contains(name + "级");
    }

    /** 剥掉 {@code §x} 颜色 / 格式码 */
    private static String stripColors(Component line) {
        try {
            String raw = line.getString();
            StringBuilder sb = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '\u00a7' && i + 1 < raw.length()) {
                    i++; // 跳过格式码本身
                    continue;
                }
                sb.append(c);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
