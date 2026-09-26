package com.xingmot.elementmark;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * 内置材料中文名表——仓库根目录 {@code elementmark.txt} <b>直接打进 jar</b> 的那一份。
 *
 * <p>它是任务"把 elementmark.txt 集成进模组"的落点，同时充当<b>用户配置的下一级兜底</b>：
 * 配置文件里没写的材料名，先来这里查，再交给 GT 自动识别。这样两种情形都能显示中文名：
 *
 * <ul>
 *   <li><b>老用户升级</b>：config 里还是旧版那份精简配置，没被覆盖，但内置表照样生效；</li>
 *   <li><b>新装用户</b>：首次运行写出的 {@code config/elementmark.txt} 就是本表的全文
 *       （{@link ConfigLoader#defaultContent()}），开箱即见中文名。</li>
 * </ul>
 *
 * <p><b>与用户配置的优先级</b>：配置<b>先查</b>，且"配置里显式写了这个键"就一律以配置为准——
 * 包括显式留空（{@code lead:}）这种"关掉该材料角标"的写法。只有配置里<b>根本没有这个键</b>时，
 * 才轮到本表。于是 README 里承诺的"值留空即隐藏"语义不受内置表影响。
 *
 * <p>解析口径与 {@link ConfigLoader#reload()} 保持一致：{@code #} 起始行与空行忽略、
 * 只在第一个冒号处切分、段名转小写、保留段（corner / font_scale / scroll）跳过。
 */
public final class BuiltinNames {

    /** jar 内资源路径（{@code src/main/resources} 下的绝对路径写法） */
    public static final String RESOURCE = "/assets/elementmark/builtin_names.txt";

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 原文（UTF-8）。同一份文本两用：既是解析数据源，也是首次运行写出的默认配置内容——
     * 单一来源，避免"文件里的默认值"和"内置表"两处各写一遍而漂移。
     */
    private static final String RAW = readRaw();

    /** material（小写） -> 角标文字；LinkedHashMap 保留文件顺序，便于日志与排查 */
    private static final Map<String, String> NAMES = parse(RAW);

    private BuiltinNames() {}

    /** @return 内置表里该材料的中文名；没有该键返回 {@code null}（与"配置里留空"区分开） */
    public static String get(String material) {
        return NAMES.get(material);
    }

    /** 内置表条目数，供日志与调试 */
    public static int size() {
        return NAMES.size();
    }

    /** 内置表全文，供 {@link ConfigLoader} 作为 {@code config/elementmark.txt} 的出厂内容 */
    public static String rawContent() {
        return RAW;
    }

    /**
     * 读取 jar 内资源。
     *
     * <p>失败时返回空串而不是抛异常：本表只是"更全的默认值"，读不到时模组仍应能
     * 靠用户配置 + 内置元素符号表正常工作，不能因为一个可选资源把游戏启动带崩。
     */
    private static String readRaw() {
        try (InputStream in = BuiltinNames.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[ElementMark] 未找到内置中文名表 {}，本次仅使用内置元素符号表", RESOURCE);
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[ElementMark] 内置中文名表读取失败：{}", RESOURCE, e);
            return "";
        }
    }

    /** 解析成表；保留段与注释跳过，口径与 {@code ConfigLoader.reload()} 相同 */
    private static Map<String, String> parse(String raw) {
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim().toLowerCase();
            if (key.isEmpty() || ConfigLoader.isReservedSection(key)) {
                continue;
            }
            out.put(key, trimmed.substring(colon + 1).trim());
        }
        return Map.copyOf(out);
    }
}
