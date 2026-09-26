package com.xingmot.elementmark.circuit;

/**
 * GT 电压等级的名称与配色。
 *
 * <p><b>名称表</b>逐项取自 GT-Modern 7.5.3 的 {@code com.gregtechceu.gtceu.api.GTValues.VN}
 * （{@code GTValues.java:154}），顺序即等级顺序，{@link #MAX_INDEX} 是最后一个。
 *
 * <p><b>配色表是用户自定义的，不是 GT 的 {@code VC}</b>。早期版本直接照搬 GT 的 {@code VC}
 * （那套是 GT 给<b>材质</b>定义的精确 RGB，从 ULV 暗红到 MAX 蓝），但用户实际看下来觉得
 * 那套色彩层次不符合自己的观感，于是逐档重新指定了颜色（例如 MV 要天蓝而不是橙、
 * HV 要黄色而不是荧光黄绿、ZPM 取参考图里的淡紫灰）。<b>不要再"顺手改回 GT 官方色"</b>——
 * 这是用户的明确选择，{@link #COLORS} 的每一项都能在下面的注释里找到对应的口头描述。
 *
 * <p><b>OpV 与 MAX 不在这里取色</b>：{@link #COLORS} 里这两项只是"静态兜底"
 * （配置里若关掉动画或取值越界时用），真正的绘制走动态色相
 * （见 {@code RainbowVertexConsumer}）——它要的是"字形当蒙版、蒙版下是流动彩图"，
 * 而不是给字填一个固定颜色。两者的区别是<b>色相区间</b>：
 * <ul>
 *   <li>{@code MAX}：整圈色相 {@code [0, 1]}，红→黄→绿→青→蓝→紫→红，无缝循环；</li>
 *   <li>{@code OpV}：只取 {@code [0.75, 1.0]} 这一段，即紫→品红→红，来回折返。</li>
 * </ul>
 * 为什么 OpV 要折返而不是像 MAX 那样绕回：全色相是一个<b>闭合的圆环</b>，
 * 从 1.0 跳回 0.0 时颜色本来就相邻（红接红），所以无缝；
 * 而 {@code [0.75, 1.0]} 是一个<b>弧段</b>，从 1.0 跳回 0.75 是红直接跳紫，会看到明显的接缝。
 * 折返（三角波）让它在紫↔红之间来回走，永远不会跳变。
 */
public final class VoltageTier {

    /**
     * 等级缩写，顺序 = GT 的 {@code GTValues.VN}。
     *
     * <p>注意 {@code LuV} / {@code OpV} 是 GT 自己的大小写（不是 {@code LUV} / {@code OPV}），
     * 显示时原样照抄——这是整合包玩家认得的写法。
     */
    public static final String[] NAMES = {
            "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV",
            "UHV", "UEV", "UIV", "UXV", "OpV", "MAX"
    };

    /**
     * 等级配色，顺序 = {@link #NAMES}（{@code 0xRRGGBB}）。
     *
     * <p>用户逐档指定的颜色，括号里是用户的原话描述：
     * <pre>
     * ULV 纯白        LV 淡灰白      MV 天蓝        HV 黄色
     * EV  淡紫        IV 灰蓝        LuV 灰紫粉     ZPM 淡紫灰（取用户给的参考图）
     * UV  红灰        UHV 粉紫灰（饱和度比 LuV 高）  UEV 深蓝
     * UIV 金色        UXV 绿色       OpV 紫→红动态  MAX 全色相动态
     * </pre>
     *
     * <p>{@code OpV} / {@code MAX} 两项是静态兜底，正常不会被画出来（走动态色相）。
     */
    public static final int[] COLORS = {
            0xFFFFFF, // ULV 纯白
            0xD8D8D8, // LV  淡灰白
            0x5FB8FF, // MV  天蓝
            0xFFFF33, // HV  黄色
            0xC9A8FF, // EV  淡紫
            0x7E9DC4, // IV  灰蓝
            0xC79BC7, // LuV 灰紫粉
            0xA096B0, // ZPM 淡紫灰（用户参考图的主色，逐像素采样得到）
            0xC08A8A, // UV  红灰
            0xE06BC0, // UHV 粉紫灰（比 LuV 更饱和）
            0x1E40D8, // UEV 深蓝
            0xFFC020, // UIV 金色
            0x3FC63F, // UXV 绿色
            0xB060C0, // OpV 兜底（实际走紫→红动态色相）
            0x2828F5  // MAX 兜底（实际走全色相动态）
    };

    /**
     * {@code MAX} 在 {@link #NAMES} / {@link #COLORS} 里的下标。
     *
     * <p>单独提出来是因为它触发一条<b>完全不同的绘制路径</b>（全色相动态彩虹蒙版），
     * 散落的 {@code == 14} 字面量会让"哪一档是 MAX"这件事失去单一出处。
     */
    public static final int MAX_INDEX = 14;

    /**
     * {@code OpV} 在 {@link #NAMES} / {@link #COLORS} 里的下标。
     *
     * <p>与 {@link #MAX_INDEX} 一样是"走动态色相"的档位，只是色相区间不同。
     */
    public static final int OPV_INDEX = 13;

    /**
     * {@code OpV} 动态色的色相起点。
     *
     * <p>色相 {@code 0.75} 是蓝紫（violet），{@code 1.0} 是正红，中间经过品红——
     * 也就是用户要的"只有紫色到红色"。终点固定取 {@code 1.0}（见 {@link #hueEndOf}）。
     */
    private static final float OPV_HUE_START = 0.75F;

    private VoltageTier() {}

    /**
     * 按名称查等级下标，<b>不区分大小写</b>。
     *
     * <p>之所以忽略大小写：配置里用户很可能写成 {@code luv} / {@code max}，
     * 而 GT 自己写的是 {@code LuV} / {@code MAX}；两者必须都能认，
     * 否则用户明明填对了等级却因为一个字母的大小写被静默忽略。
     *
     * @return 下标；不是任何已知等级时返回 {@code -1}
     */
    public static int indexOf(String name) {
        if (name == null) {
            return -1;
        }
        String trimmed = name.trim();
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equalsIgnoreCase(trimmed)) {
                return i;
            }
        }
        return -1;
    }

    /** 等级数量 */
    public static int count() {
        return NAMES.length;
    }

    /** 下标 -> 缩写；越界返回 {@code null} */
    public static String nameOf(int tier) {
        return tier >= 0 && tier < NAMES.length ? NAMES[tier] : null;
    }

    /** 下标 -> 配色（{@code 0xRRGGBB}）；越界返回白色 */
    public static int colorOf(int tier) {
        return tier >= 0 && tier < COLORS.length ? COLORS[tier] : 0xFFFFFF;
    }

    /**
     * 该等级是否走动态色相（{@code OpV} 与 {@code MAX}）。
     *
     * <p>两者共用 {@code RainbowVertexConsumer} 的"字形当蒙版、蒙版下是流动彩图"方案，
     * 区别只在色相区间，见 {@link #hueStartOf} / {@link #hueEndOf}。
     */
    public static boolean isAnimated(int tier) {
        return tier == OPV_INDEX || tier == MAX_INDEX;
    }

    /**
     * 动态色的色相区间起点（0~1）。非动态等级返回 {@code 0}。
     *
     * <p>{@code MAX} 取 {@code 0}（整圈从红起），{@code OpV} 取 {@code 0.75}（从蓝紫起）。
     */
    public static float hueStartOf(int tier) {
        return tier == OPV_INDEX ? OPV_HUE_START : 0.0F;
    }

    /**
     * 动态色的色相区间终点（0~1）。
     *
     * <p>两档都取 {@code 1.0}（正红）：{@code MAX} 是"整圈从红回到红"，
     * {@code OpV} 是"从蓝紫走到红"——终点相同，区别全在起点。
     */
    public static float hueEndOf(int tier) {
        return 1.0F;
    }
}
