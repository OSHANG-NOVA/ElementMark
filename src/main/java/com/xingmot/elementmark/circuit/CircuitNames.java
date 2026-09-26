package com.xingmot.elementmark.circuit;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 格雷科技电路板的<b>内置识别表</b>：注册名 / 英文名 / 中文名 -&gt; 电压等级。
 *
 * <p><b>数据来源</b>：全部取自 GT-Modern 7.5.3 自身，逐条核对过两处权威出处——
 * <ul>
 *   <li>{@code data/gtceu/tags/items/circuits/*.json}：等级归属（哪个物品属于哪一档）；</li>
 *   <li>{@code assets/gtceu/lang/en_us.json} 与 {@code zh_cn.json}：中英文显示名。</li>
 * </ul>
 * 表里这 28 项覆盖了 GT 本体全部电路（从 ULV 的真空管到 UHV 的湿件处理器主机）。
 *
 * <p><b>它只是第 4 顺位的数据源</b>，见 {@link CircuitDetector} 的优先级说明：
 * 标签与 Tooltip 都比本表更权威、也更通用（附属模组新加的电路会带自己的标签与 Tooltip，
 * 但不会出现在这张写死的表里）。本表的价值在于<b>兜底</b>——某些附属模组的电路既没打
 * GT 的电路标签、Tooltip 也没写等级文字，此时按名字仍然认得出。
 *
 * <p><b>为什么要同时收中英文名</b>：{@code stack.getHoverName()} 给的是<b>当前语言</b>的名字，
 * 而整合包玩家与模组作者填配置时两种语言都可能写。把两个名字都收进同一张表，
 * 于是"游戏是中文、用户却按英文名配置"这种组合也能命中。
 */
public final class CircuitNames {

    /** 注册名 path（不含命名空间） -&gt; 等级下标 */
    private static final Map<String, Integer> BY_PATH = new HashMap<>();
    /** 小写显示名（中英皆收） -&gt; 等级下标 */
    private static final Map<String, Integer> BY_NAME = new HashMap<>();
    /** 注册名 path -&gt; {英文名, 中文名}，供配置项按任一语言的名字反查 */
    private static final Map<String, String[]> ALIASES = new HashMap<>();

    private CircuitNames() {}

    private static void put(int tier, String path, String en, String zh) {
        BY_PATH.put(path, tier);
        BY_NAME.put(en.toLowerCase(Locale.ROOT), tier);
        BY_NAME.put(zh.toLowerCase(Locale.ROOT), tier);
        ALIASES.put(path, new String[] { en, zh });
    }

    static {
        // ---- ULV ----
        put(0, "vacuum_tube", "Vacuum Tube", "真空管");
        put(0, "nand_chip", "NAND Chip", "NAND芯片");

        // ---- LV ----
        put(1, "basic_electronic_circuit", "Basic Electronic Circuit", "基础电子电路");
        put(1, "basic_integrated_circuit", "Basic Integrated Circuit", "基础集成电路");
        put(1, "microchip_processor", "Microchip Processor", "微芯片处理器");

        // ---- MV ----
        put(2, "good_electronic_circuit", "Good Electronic Circuit", "优质电子电路");
        put(2, "good_integrated_circuit", "Good Integrated Circuit", "优质集成电路");
        put(2, "micro_processor", "Microprocessor", "微型处理器");

        // ---- HV ----
        put(3, "advanced_integrated_circuit", "Advanced Integrated Circuit", "进阶集成电路");
        put(3, "micro_processor_assembly", "Microprocessor Assembly", "微型处理器集群");
        put(3, "nano_processor", "Nanoprocessor", "纳米处理器");

        // ---- EV ----
        put(4, "micro_processor_computer", "Microprocessor Supercomputer", "微型处理器超级计算机");
        put(4, "nano_processor_assembly", "Nanoprocessor Assembly", "纳米处理器集群");
        put(4, "quantum_processor", "Quantum Processor", "量子处理器");

        // ---- IV ----
        put(5, "micro_processor_mainframe", "Microprocessor Mainframe", "微型处理器主机");
        put(5, "nano_processor_computer", "Nanoprocessor Supercomputer", "纳米处理器超级计算机");
        put(5, "quantum_processor_assembly", "Quantum Processor Assembly", "量子处理器集群");
        put(5, "crystal_processor", "Crystal Processor", "晶体处理器");

        // ---- LuV ----
        put(6, "nano_processor_mainframe", "Nanoprocessor Mainframe", "纳米处理器主机");
        put(6, "quantum_processor_computer", "Quantum Processor Supercomputer", "量子处理器超级计算机");
        put(6, "crystal_processor_assembly", "Crystal Processor Assembly", "晶体处理器集群");
        put(6, "wetware_processor", "Wetware Processor", "湿件处理器");

        // ---- ZPM ----
        put(7, "quantum_processor_mainframe", "Quantum Processor Mainframe", "量子处理器主机");
        put(7, "crystal_processor_computer", "Crystal Processor Supercomputer", "晶体处理器超级计算机");
        put(7, "wetware_processor_assembly", "Wetware Processor Assembly", "湿件处理器集群");

        // ---- UV ----
        put(8, "crystal_processor_mainframe", "Crystal Processor Mainframe", "晶体处理器主机");
        put(8, "wetware_processor_computer", "Wetware Processor Supercomputer", "湿件处理器超级计算机");

        // ---- UHV ----
        put(9, "wetware_processor_mainframe", "Wetware Processor Mainframe", "湿件处理器主机");
    }

    /**
     * 按注册名 path 查等级。
     *
     * @return 等级下标；不在内置表里返回 {@link CircuitDetector#NOT_CIRCUIT}
     */
    public static int byPath(String path) {
        if (path == null) {
            return CircuitDetector.NOT_CIRCUIT;
        }
        Integer tier = BY_PATH.get(path);
        return tier == null ? CircuitDetector.NOT_CIRCUIT : tier;
    }

    /**
     * 按显示名（中文或英文，不区分大小写）查等级。
     *
     * @return 等级下标；不在内置表里返回 {@link CircuitDetector#NOT_CIRCUIT}
     */
    public static int byName(String name) {
        if (name == null || name.isEmpty()) {
            return CircuitDetector.NOT_CIRCUIT;
        }
        Integer tier = BY_NAME.get(name.trim().toLowerCase(Locale.ROOT));
        return tier == null ? CircuitDetector.NOT_CIRCUIT : tier;
    }

    /** 该注册名 path 对应的 {英文名, 中文名}；不在表里返回 {@code null} */
    public static String[] aliasesOf(String path) {
        return path == null ? null : ALIASES.get(path);
    }

    /** 内置表条目数（= 收录的电路种数），供日志与调试 */
    public static int size() {
        return BY_PATH.size();
    }
}
