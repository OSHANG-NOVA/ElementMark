package com.xingmot.elementmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 用户配置文件读写。
 *
 * 位置：config/elementmark.txt
 *
 * 格式：每行 "<段名>:<值>"，# 起始行与空行忽略；段名不区分大小写。
 * 除下列保留段名外，其余段名一律视为"材料名"，值为绘制缩写：
 *
 * <pre>
 * redstone:红石            材料名 redstone（匹配 c:dusts/redstone 等）绘制「红石」
 * lapis:青金石             同上
 * zinc:Zn                  覆盖内置元素表对该材料的默认值
 * corner:top_left           保留段：绘制角落
 * font_scale:0.9            保留段：字号系数（配置界面里由滑动条调整）
 * scroll:loop               保留段：超长缩写的滚动方式
 * </pre>
 *
 * 默认内容在文件缺失时写入一次；已存在则原样保留（不覆盖用户改动）。
 * 通过 {@link #reload()} 可在运行期重新读取，无需重启游戏
 * （游戏内按 F3+T 重载资源、或在配置界面点「重新读取配置」按钮）。
 */
public final class ConfigLoader {

    /** 保留段名：缩写绘制在物品图标的哪个角 */
    private static final String SECTION_CORNER = "corner";
    /** 保留段名：在 corner 定位结果之上再乘的字号系数（配置界面里是那根滑动条） */
    private static final String SECTION_FONT_SCALE = "font_scale";
    /** 保留段名：超长缩写的滚动方式，sway/loop（配置界面里是可切换的按钮） */
    private static final String SECTION_SCROLL = "scroll";

    /**
     * 该段名是否为保留段（而非"材料名"）。
     *
     * <p>抽成公开方法是为了让 {@link BuiltinNames} 用<b>同一套口径</b>解析内置表：
     * 内置表就是 {@code elementmark.txt} 的原文，末尾同样带着 corner / font_scale / scroll
     * 三个保留段，若两处各判一遍、判据一旦漂移，内置表里就会混进名为 "corner" 的假材料。
     */
    public static boolean isReservedSection(String section) {
        return SECTION_CORNER.equals(section)
                || SECTION_FONT_SCALE.equals(section)
                || SECTION_SCROLL.equals(section);
    }

    public enum Corner {
        TOP_LEFT(0, 0),
        TOP_RIGHT(1, 0),
        BOTTOM_LEFT(0, 1),
        BOTTOM_RIGHT(1, 1);

        /** 横向 0 = 靠左，1 = 靠右 */
        public final int horizontal;
        /** 纵向 0 = 靠上，1 = 靠下 */
        public final int vertical;

        Corner(int horizontal, int vertical) {
            this.horizontal = horizontal;
            this.vertical = vertical;
        }

        public static Corner fromName(String name) {
            return switch (name.toLowerCase()) {
                case "top_left", "topleft" -> TOP_LEFT;
                case "top_right", "topright" -> TOP_RIGHT;
                case "bottom_left", "bottomleft" -> BOTTOM_LEFT;
                case "bottom_right", "bottomright" -> BOTTOM_RIGHT;
                default -> null;
            };
        }
    }

    /**
     * 超长缩写的滚动方式。
     *
     * <p>只影响"长到放不下、必须动起来"的角标（判定见 {@code BadgePlacement#compute}）；
     * 放得下的角标贴角落、放不太下的静态居中，两者与本节无关。
     */
    public enum ScrollMode {
        /**
         * 左右往返摆动：开头对齐窗口左缘 → 摆到末尾对齐窗口右缘 → 再摆回来，两端各停一会。
         * 两端一停才给得出"看清开头与结尾"的时间，单向滚动时每个字都一闪而过。
         */
        SWAY("sway"),
        /**
         * 单向循环滚动：文字自窗口右缘外侧滚入、向左滚出窗口，隔一段空档后从头再来。
         * 移动方向恒定，更像跑马灯；代价是每个字都不停。
         */
        LOOP("loop");

        private final String configName;

        ScrollMode(String configName) {
            this.configName = configName;
        }

        /** 写回配置文件时使用的值 */
        public String configName() {
            return configName;
        }

        /** 解析配置值；无法识别返回 null（调用方据此报格式错误并取默认值） */
        public static ScrollMode fromName(String name) {
            return switch (name.toLowerCase()) {
                case "sway", "swing", "back_forth", "backforth", "both" -> SWAY;
                case "loop", "cycle", "one_way", "oneway", "single" -> LOOP;
                default -> null;
            };
        }
    }

    /**
     * 滚动方式的默认值：单向循环滚动（{@link ScrollMode#LOOP}）。
     *
     * <p>与 {@link #DEFAULT_FONT_SCALE} 一起构成出厂默认配置（角落 / 字号 / 滚动方式），
     * 与 {@code DEFAULT_CONTENT} 写出的文件、以及 {@code run/config/elementmark.txt} 三方同值。
     */
    public static final ScrollMode DEFAULT_SCROLL_MODE = ScrollMode.LOOP;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "elementmark.txt";

    /**
     * 默认字号系数 = 滑条下限。
     *
     * <p>取 {@code 0.9} 要分两层看，两者并不矛盾：
     * <ul>
     *   <li><b>数学基线是 {@code 1.0}</b>——"只用自动适配的结果，不再额外缩放"；</li>
     *   <li><b>观感默认取 {@code 0.9}</b>——出厂偏好，也是滑条能表达的最小值。放在下限还顺带
     *       一个好处：字号越小，滚动行一次能看见的字越多（0.9 档可见 3.70 个字形位置，
     *       1.0 档 3.33，见 {@code BadgePlacement#CLIP_SLACK} 的核算），而超长缩写恰恰最需要
     *       "多看一点"。</li>
     * </ul>
     */
    public static final float DEFAULT_FONT_SCALE = 0.9F;

    /**
     * 字号系数下限。配置界面滑动条的区间与 {@link #saveFontScale} 的钳制共用此值。
     *
     * <p>取 {@code 0.9} 是实测结论：用户认可的"文字最小到哪一步还能看"，就是
     * <b>0.9 倍下三个全角字的大小</b>，即自动适配 {@code 0.667} × {@code 0.9} ≈
     * <b>0.6</b>（{@code 0.667} 是"三个全角字刚好填满可用宽度"的适配值，
     * 见 {@code BadgePlacement#MIN_FIT_SCALE}）。比这更小的字不允许通过滑条调出。
     */
    public static final float FONT_SCALE_MIN = 0.9F;

    /**
     * 字号系数上限。
     *
     * <p>取 {@code 1.1}，由"角标永不越出图标"反推：自动适配保证了未摆动文本的渲染宽度
     * {@code ≤ 可用宽度 18}，乘 1.1 后最多 19.8，仍小于图标边长 20。也就是说只要字号
     * 不超过 1.1，就<b>不存在</b>"字大得压到相邻槽位"的情况；再往上放，这条保证就没了。
     */
    public static final float FONT_SCALE_MAX = 1.1F;

    /**
     * 首次运行写入的默认配置；注释用英文，规避不同系统 locale 下的编码差异。
     *
     * <p>三条生效的段（{@code corner} / {@code font_scale} / {@code scroll}）必须与
     * {@link #DEFAULT_FONT_SCALE} / {@link #DEFAULT_SCROLL_MODE} 以及 {@code corner} 字段初值
     * 同值——前者管"文件不存在时写出什么"，后者管"文件存在但缺该段时用什么"。
     */
    private static final String DEFAULT_FALLBACK = """
            # Element Mark config
            # Format: one entry per line, "section:value" or "material:abbreviation"
            #
            #   <material>:<abbreviation>   draw <abbreviation> on items whose c:/forge: tag material
            #                               matches <material>, e.g. "zinc" matches c:plates/zinc
            #                               leave the value empty to hide the badge for that material
            #                               Fluid buckets match by their fluid id instead
            #                               (gtceu:soldering_alloy_bucket -> soldering_alloy);
            #                               form markers raw_/molten_/liquid_/_plasma are
            #                               stripped automatically (full names win).
            #   corner:<position>           where to draw: top_left | top_right | bottom_left | bottom_right
            #   font_scale:<number>         size of the abbreviation: multiplier on top of the automatic
            #                               fit-to-icon scaling, clamped to 0.9 ~ 1.1; 1 = automatic only
            #                               (the in-game config screen edits this same value with a slider)
            #   scroll:<sway|loop>          how a badge that is too long to fit moves:
            #                               "sway"  = swings back and forth, pausing at both ends
            #                               "loop"  = scrolls one way, right to left, over and over
            #
            # A badge too long to fit the icon is not shrunk any further: it keeps the smallest
            # readable size (three full-width characters at font_scale 0.9) and instead moves along
            # the top / bottom edge inside the icon, in the way chosen by "scroll" above.
            #
            # Lines starting with '#' and blank lines are ignored; section names are case-insensitive.
            # Changes are picked up automatically while the game is running.

            # The built-in tags already cover the 118 chemical elements; list here only what needs
            # overriding, or what is not an element.
            # lapis:青金石

            # Where the abbreviation sits inside the item slot
            corner:top_left

            # Size of the abbreviation. 1 = fit-to-icon only; bigger / smaller scales it further.
            # The in-game config screen edits this same value with a slider.
            font_scale:0.9

            # How an over-long abbreviation moves. The in-game config screen flips this too.
            scroll:loop
            """;

    /**
     * 首次运行写出的配置全文。
     *
     * <p><b>优先用打进 jar 的那份 {@code elementmark.txt}</b>（{@link BuiltinNames#rawContent()}）——
     * 它就是"把 elementmark.txt 直接集成进模组"的落点：新装用户开箱即得 678 行的中文名表
     * （118 元素 + 536 条 GTCEu 材料），而不是只有三个保留段的空壳。
     *
     * <p>只有在该资源读不到时（打包异常）才退回 {@link #DEFAULT_FALLBACK}，
     * 保证模组在任何情况下都写得出一个语法合法的配置文件。
     */
    public static String defaultContent() {
        String builtin = BuiltinNames.rawContent();
        return builtin.isEmpty() ? DEFAULT_FALLBACK : builtin;
    }

    /** material（小写） -> 缩写。用 LinkedHashMap 保留文件顺序，便于日志与调试 */
    private static volatile Map<String, String> entries = Map.of();
    private static volatile Corner corner = Corner.TOP_LEFT;
    /** 字号系数，与自动适配结果相乘。来源：配置文件的 {@code font_scale}，或配置界面的滑动条 */
    private static volatile float fontScale = DEFAULT_FONT_SCALE;
    /** 超长缩写的滚动方式。来源：配置文件的 {@code scroll}，或配置界面的切换按钮 */
    private static volatile ScrollMode scrollMode = DEFAULT_SCROLL_MODE;

    private ConfigLoader() {}

    /** 配置文件绝对路径 */
    public static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    /** 确保配置文件存在（缺失则写入默认内容），并加载一次 */
    public static void init() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                Files.writeString(path, defaultContent(), StandardCharsets.UTF_8);
                LOGGER.info("[ElementMark] 已生成默认配置文件（内置中文名表 {} 条）：{}",
                        BuiltinNames.size(), path);
            }
        } catch (IOException e) {
            LOGGER.error("[ElementMark] 配置文件创建失败：{}", path, e);
        }
        reload();
    }

    /** 重新读取配置文件；解析失败的行跳过并记警告，不影响其余条目 */
    public static void reload() {
        Path path = configPath();
        Map<String, String> parsed = new LinkedHashMap<>();
        Corner parsedCorner = Corner.TOP_LEFT;
        // lambda 里要写这些值，故用单元素数组当可变容器（局部变量本身得是 effectively final）
        float[] parsedScale = { DEFAULT_FONT_SCALE };
        ScrollMode parsedScroll = DEFAULT_SCROLL_MODE;
        List<String> broken = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                // 只在第一个冒号处切分，允许缩写本身含冒号（如 "time:12:00"）
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    broken.add("第 " + (i + 1) + " 行: " + line);
                    continue;
                }
                String section = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                if (section.isEmpty()) {
                    broken.add("第 " + (i + 1) + " 行: " + line);
                    continue;
                }

                switch (section) {
                    case SECTION_CORNER -> {
                        Corner c = Corner.fromName(value);
                        if (c == null) {
                            broken.add("第 " + (i + 1) + " 行 corner 取值无效: " + value);
                        } else {
                            parsedCorner = c;
                        }
                    }
                    case SECTION_FONT_SCALE -> parseScale(value, i, broken, v -> parsedScale[0] = v);
                    case SECTION_SCROLL -> {
                        ScrollMode mode = ScrollMode.fromName(value);
                        if (mode == null) {
                            broken.add("第 " + (i + 1) + " 行 scroll 取值无效（应为 sway/loop）: " + value);
                        } else {
                            parsedScroll = mode;
                        }
                    }
                    // 空缩写视为"去掉该材料的角标"，是合法写法而非格式错误
                    default -> parsed.put(section, value);
                }
            }
            entries = Map.copyOf(parsed);
            corner = parsedCorner;
            fontScale = parsedScale[0];
            scrollMode = parsedScroll;
            if (broken.isEmpty()) {
                LOGGER.info("[ElementMark] 配置已加载：{} 条材料条目，corner={}，font_scale={}，scroll={}",
                        parsed.size(), parsedCorner.name().toLowerCase(), fontScale, scrollMode.configName());
            } else {
                LOGGER.warn("[ElementMark] 配置已加载：{} 条材料条目，corner={}，font_scale={}，scroll={}；以下 {} 行有问题已跳过或钳制：{}",
                        parsed.size(), parsedCorner.name().toLowerCase(), fontScale, scrollMode.configName(),
                        broken.size(), String.join(" | ", broken));
            }
        } catch (IOException e) {
            LOGGER.error("[ElementMark] 配置文件读取失败，沿用上一次的 {} 条配置：{}", entries.size(), path, e);
        }
    }

    /**
     * 解析 {@code font_scale}：空值取默认值；非法值记入 broken 并取默认值；
     * 越界值同样记入 broken（但保留用户意图的可读提示），结果钳制到
     * {@link #FONT_SCALE_MIN} ~ {@link #FONT_SCALE_MAX}，与配置界面滑动条的区间一致。
     */
    private static void parseScale(String value, int lineIndex, List<String> broken,
                                   java.util.function.Consumer<Float> sink) {
        if (value.isEmpty()) {
            sink.accept(DEFAULT_FONT_SCALE);
            return;
        }
        float parsed;
        try {
            parsed = Float.parseFloat(value);
        } catch (NumberFormatException e) {
            broken.add("第 " + (lineIndex + 1) + " 行 font_scale 取值无效: " + value);
            sink.accept(DEFAULT_FONT_SCALE);
            return;
        }
        float clamped = clampScale(parsed);
        if (clamped != parsed) {
            broken.add("第 " + (lineIndex + 1) + " 行 font_scale 超出 " + FONT_SCALE_MIN + "~"
                    + FONT_SCALE_MAX + "，已钳制为 " + clamped + ": " + value);
        }
        sink.accept(clamped);
    }

    /** 把字号系数钳制到滑动条能表达的区间内 */
    public static float clampScale(float scale) {
        return Math.max(FONT_SCALE_MIN, Math.min(FONT_SCALE_MAX, scale));
    }

    /**
     * @return 配置中该 material 的缩写；未配置返回 null。
     * 返回空串表示"显式关闭该材料的角标"——调用方需与未配置区分开
     */
    public static String lookup(String material) {
        return entries.get(material);
    }

    /** 缩写绘制角落 */
    public static Corner corner() {
        return corner;
    }

    /** 字号系数（与自动缩放结果相乘） */
    public static float fontScale() {
        return fontScale;
    }

    /** 超长缩写的滚动方式（只影响必须动起来的角标） */
    public static ScrollMode scrollMode() {
        return scrollMode;
    }

    /**
     * 把角落设置写回配置文件并立即生效。
     *
     * @return 写入是否成功；失败时内存中的值也不变，界面上显示的值始终与文件一致
     */
    public static boolean saveCorner(Corner newCorner) {
        if (!writeSection(SECTION_CORNER, newCorner.name().toLowerCase())) {
            return false;
        }
        corner = newCorner;
        BadgeResolver.invalidate();
        return true;
    }

    /**
     * 把尺寸（字号系数）写回配置文件并立即生效。
     *
     * <p>配置界面的滑动条在拖动过程中会反复调用本方法，因此入参先按
     * {@link #FONT_SCALE_MIN} ~ {@link #FONT_SCALE_MAX} 钳制，并四舍五入到两位小数
     * （与滑动条按钮上显示的精度一致），保证"文件里的值"与"界面上看到的值"永远相同。
     *
     * @return 写入是否成功；失败时内存中的值也不变
     */
    public static boolean saveFontScale(float newScale) {
        float clamped = clampScale(newScale);
        // 滑动条是连续量，不取整会把 1.055 这种尾数写进文件，而按钮上只显示两位小数
        float rounded = Math.round(clamped * 100.0F) / 100.0F;
        if (!writeSection(SECTION_FONT_SCALE, formatScale(rounded))) {
            return false;
        }
        fontScale = rounded;
        return true;
    }

    /**
     * 把超长缩写的滚动方式写回配置文件并立即生效。
     *
     * <p>与 {@code saveCorner} 不同，这里<b>不需要</b>让 {@code BadgeResolver} 失效：
     * 滚动方式只影响绘制动作，不参与"解析出哪个缩写"的判断。
     *
     * @return 写入是否成功；失败时内存中的值也不变
     */
    public static boolean saveScrollMode(ScrollMode mode) {
        if (!writeSection(SECTION_SCROLL, mode.configName())) {
            return false;
        }
        scrollMode = mode;
        return true;
    }

    /**
     * 原地改写配置文件里某个保留段的值；文件里没有该段则在末尾追加。
     * 其余内容（注释、空行、用户自定义的材料条目）原样保留，不会抹掉用户的编辑。
     *
     * @return 写入是否成功
     */
    private static boolean writeSection(String section, String value) {
        Path path = configPath();
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                // 只在第一个冒号处切分，与 reload() 的解析口径保持一致
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(section)) {
                    lines.set(i, section + ":" + value);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(section + ":" + value);
            }
            Files.write(path, lines, StandardCharsets.UTF_8);
            LOGGER.info("[ElementMark] {} 已设为 {}，配置已保存：{}", section, value, path);
            return true;
        } catch (IOException e) {
            LOGGER.error("[ElementMark] 配置写入失败（段 {}）：{}", section, path, e);
            return false;
        }
    }

    /** 写入文件时的数字格式：整数不带小数点，其余保留原样（如 0.85） */
    private static String formatScale(float scale) {
        return scale == Math.round(scale) ? String.valueOf((int) scale) : String.valueOf(scale);
    }

    /** 当前条目数，供调试 */
    public static int size() {
        return entries.size();
    }
}
