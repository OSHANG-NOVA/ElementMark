package com.xingmot.elementmark.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模组列表搜索的匹配算法——本模组"自制兼容性搜索"的落点。
 *
 * <p><b>为什么需要它。</b>Forge 的模组列表（{@code ModListScreen}）原生只做一件事：
 * 把搜索词与模组显示名都转小写后调一次 {@code String.contains}。于是中文玩家按拼音
 * （{@code glkj} → 格雷科技）搜不到、想忽略字母位置（{@code n+u}）也做不到。
 * 本类在这条基线之上补三层匹配，<b>基线行为原样保留</b>（原文命中永远优先），
 * 因此英文/数字搜索的手感与原生完全一致，只是多出了原先搜不到的那些结果。
 *
 * <p><b>语法</b>（两处特殊符号都取自用户的明确要求）：
 * <ul>
 *   <li><b>空格 = 或</b>：{@code n u} 表示"名称含 n <b>或</b>含 u"，任一 token 命中即算命中；</li>
 *   <li><b>加号 = 忽略字母位置</b>：{@code n+u} 表示"名称里依次含有 {@code n} 与 {@code u}，
 *       但两者<b>不必相邻</b>"，即把 {@code nu} 当作<b>子序列</b>来搜——等价于正则
 *       {@code n.*u}。这正是需求里"忽略字母位置"那一项：{@code zn} 能搜到 {@code zinc}。
 *       多个加号可连用（{@code a+b+c} → {@code a.*b.*c}）。</li>
 * </ul>
 *
 * <p><b>匹配层次</b>（对单个 token，先命中先用）：
 * <ol>
 *   <li><b>原文</b>：把 token 按加号拆成若干段，各段<b>按顺序</b>在名称里依次出现即命中
 *       （段内连续、段间允许任意字符）。只有一段时它就退化成普通 {@code contains}——
 *       与 Forge 原生行为完全一致；</li>
 *   <li><b>拼音</b>：交给 {@link PinyinBridge}（JustEnoughCharacters），支持全拼与首字母。</li>
 * </ol>
 *
 * <p><b>为什么把原文放在拼音前面。</b>拼音匹配（PinIn）内部要建索引、开销远大于一次
 * {@code indexOf}；而实际使用中绝大多数查询是英文/数字，本来就该被原文那一层直接命中。
 * 先廉价后昂贵，最常用的路径完全不会碰到 PinIn。
 */
public final class ModSearchMatcher {

    private ModSearchMatcher() {}

    /**
     * 判断某个模组显示名是否匹配搜索词。
     *
     * @param name  模组显示名（已剥掉颜色代码；可为 {@code null}）
     * @param query 搜索框内容；{@code null} 或空白表示"不过滤"，一律返回 {@code true}
     * @return 是否命中
     */
    public static boolean matches(String name, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (name == null || name.isEmpty()) {
            return false;
        }
        // 大小写不敏感：两侧都转小写后比较（与 Forge 原生用的 toLowerCase 口径一致）
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (String token : query.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (matchesToken(name, lowerName, token)) {
                return true;
            }
        }
        // 空白查询已在上面判掉，能走到这里说明所有 token 都没命中
        return false;
    }

    /**
     * 单个 token 的匹配：原文（含"加号 = 忽略位置"）优先，其次拼音。
     *
     * @param name      原始显示名（拼音匹配需要保留中文原文，故与 lowerName 分开传）
     * @param lowerName 已转小写的显示名
     * @param token     一个空格分隔出来的 token
     */
    private static boolean matchesToken(String name, String lowerName, String token) {
        String lowerToken = token.toLowerCase(Locale.ROOT);
        List<String> parts = splitParts(lowerToken);
        if (parts.isEmpty()) {
            // token 全是加号（如 "+" / "++"）：没有可匹配的内容，视为命中，
            // 避免用户敲了一半的加号时列表突然空掉
            return true;
        }
        if (containsAllParts(lowerName, parts)) {
            return true;
        }
        // 拼音桥是"可选依赖"，用 try 兜住 Throwable：万一 JEC 那边抛了任何东西
        // （含类初始化失败），本次判定退化成"没匹配上"，而不是把搜索界面带崩。
        try {
            return PinyinBridge.contains(name, lowerToken);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按加号把 token 拆成若干段，丢弃空段。
     *
     * <p>{@code split} 传 {@code -1} 保留末尾空串，再手工过滤——这样 {@code a+} / {@code +a}
     * 这类敲一半的输入会退化成单段匹配，而不是变成"要匹配一个空串"的怪规则。
     */
    private static List<String> splitParts(String token) {
        List<String> parts = new ArrayList<>();
        for (String part : token.split("\\+", -1)) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    /**
     * 各段<b>都</b>在名称里出现即命中——这就是"忽略字母位置"。
     *
     * <p>只有一段时等价于 {@code contains}（与 Forge 原生一致）。
     *
     * <p><b>为什么不要求顺序。</b>用户对加号的描述是"名字里包含有、但位置不管"，
     * 故 {@code u+n} 与 {@code n+u} 必须等价。若改成"按顺序依次出现"（等价正则
     * {@code n.*u}），{@code u+n} 就会搜不到本来该命中的名字，与需求相反。
     *
     * <p>各段独立判断，互不消耗：{@code n+n} 只要名称里有 {@code n} 即命中。
     * 这是有意为之——加号的语义是"这些片段都要在"，而不是"要凑出这么多个字符"。
     */
    private static boolean containsAllParts(String lowerName, List<String> parts) {
        for (String part : parts) {
            if (!lowerName.contains(part)) {
                return false;
            }
        }
        return true;
    }
}
