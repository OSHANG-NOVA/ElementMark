package com.xingmot.elementmark.compat;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.chemical.material.stack.MaterialEntry;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/**
 * GTCEu（格雷科技：现代版）材料体系的<b>隔离访问层</b>。
 *
 * <p><b>为什么必须单独一个类。</b>GT 是本模组的<b>可选</b>前置：整合包里没装 GT 时，
 * 方法体里那些 {@code com.gregtechceu.gtceu.*} 引用一旦被 JVM 解析就会抛
 * {@link NoClassDefFoundError}，直接把 GUI 渲染打崩。把全部 GT 引用关进这一个类，
 * 调用方（{@code BadgeResolver}）只在"GT 确实装了"时才触碰本类；JVM 对常量池的解析是
 * <b>惰性</b>的（首次执行到那条指令才解析），因此没装 GT 的整合包永远不会加载本类。
 *
 * <p>配套地，本类的<b>公开签名只用 JDK / 原版类型</b>（{@code Item} / {@code Fluid} /
 * {@code String}），于是即便某条路径意外触发了本类的解析，也不会连带要求 GT 的类。
 *
 * <p><b>覆盖范围为什么是"全部"。</b>{@code ChemicalHelper.getMaterialEntry} 内部既查
 * GT 自己注册的"物品 → 材料"表，也在查不到时按物品 tag 反查；而那张反查表是在 GT
 * <b>全部材料注册收口之后</b>才建立的（遍历 {@code materialManager} 的全 registry 并集），
 * 于是 GTM 本体、所有 {@code IGTAddon} 附属模组、以及 KubeJS 脚本注册的元素与材料
 * 都能自动命中——这正是"新注册的元素也能自动显示中文名"的实现基础。
 *
 * <p><b>中文名从哪来。</b>GT 的 {@code Element} 本身<b>没有</b>任何本地化字段
 * （只有 protons / neutrons / halfLifeSeconds / decayTo / name / symbol / isIsotope），
 * 游戏内元素名来自承载它的 {@code Material}：{@code Material.getLocalizedName()} 解析的
 * {@code material.<modid>.<name>} 键（GTM 本体 zh_cn 有 654 条，元素与合金同表）。
 * 所以"元素中文名"就是"该元素对应材料的中文名"，本类据此取值。
 */
public final class GtCompat {

    private GtCompat() {}

    /**
     * GT 材料注册表是否<b>已经就绪</b>。
     *
     * <p>{@code getRegisteredMaterials()} 在材料注册尚未收口时（{@code Phase != CLOSED/FROZEN}）
     * 会抛 {@link IllegalStateException}——那表示"注册还没结束"，<b>不是</b>"没有材料"。
     * 故这里吞掉异常并返回 {@code false}，让调用方稍后重试，而不会把这次失败当成
     * 否定结论永久缓存下来。
     */
    public static boolean probe() {
        try {
            var manager = GTCEuAPI.materialManager;
            return manager != null && !manager.getRegisteredMaterials().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 物品所属材料的<b>注册名</b>（如 {@code iron} / {@code echoite}）。
     *
     * <p>用途是让用户配置与内置表仍能覆盖 GT 自动识别：某些 GT 物品（尤其是附属模组
     * 或 KubeJS 造的）不带 {@code c:/forge:} 材料 tag，走不到"按 tag 取材料名"那条路，
     * 于是这里补上材料名，让 {@code config/elementmark.txt} 里写 {@code echoite:回响晶}
     * 依然能生效。
     *
     * @return 取不到返回 {@code null}
     */
    public static String materialNameOf(Item item) {
        Material mat = materialOf(item);
        return mat == null ? null : mat.getName();
    }

    /** 流体所属材料的注册名；取不到返回 {@code null} */
    public static String materialNameOf(Fluid fluid) {
        try {
            Material mat = ChemicalHelper.getMaterial(fluid);
            return mat == null || mat.isNull() ? null : mat.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 物品所属材料在<b>当前语言</b>下的名称（中文环境即中文名）。
     *
     * @return 取不到返回 {@code null}（物品不属于任何 GT 材料，或该材料没有语言条目）
     */
    public static String localizedNameOf(Item item) {
        return localizedName(materialOf(item));
    }

    /**
     * 流体所属材料在当前语言下的名称。
     *
     * @return 取不到返回 {@code null}
     */
    public static String localizedNameOf(Fluid fluid) {
        try {
            return localizedName(ChemicalHelper.getMaterial(fluid));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 物品所属材料若<b>是化学元素</b>，返回其元素符号（{@code H} / {@code He-3} / …）。
     *
     * <p>这是"材料有元素归属、但语言文件里没有它的中文名"时的兜底：
     * 显示符号总好过什么都不显示。
     *
     * @return 非元素或取不到时返回 {@code null}
     */
    public static String elementSymbolOf(Item item) {
        try {
            Material mat = materialOf(item);
            if (mat == null || !mat.isElement() || mat.getElement() == null) {
                return null;
            }
            return mat.getElement().symbol();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 物品 → 材料；GT 不认识该物品时返回 {@code null} */
    private static Material materialOf(Item item) {
        try {
            MaterialEntry entry = ChemicalHelper.getMaterialEntry(item);
            return entry == null || entry.isEmpty() ? null : entry.material();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 材料 → 本地化名称；取不到返回 {@code null}。
     *
     * <p><b>语言文件缺键时必须判掉。</b>{@code getLocalizedName().getString()} 在找不到
     * 翻译时会把<b>键本身</b>原样返回（形如 {@code material.kubejs.foo}），那不是能画在
     * 物品图标上的名字。故与 {@link Material#getUnlocalizedName()} 比对，相同即视为
     * "取不到"，交给上层退回内置中文表或元素符号。
     *
     * <p>这一条对 KubeJS 注册的材料尤其重要：材料对象一定拿得到，但整合包作者
     * 未必补了 {@code lang}，不判掉就会在角标上显示一串 {@code material.kubejs.xxx}。
     */
    private static String localizedName(Material mat) {
        if (mat == null || mat.isNull()) {
            return null;
        }
        String key = mat.getUnlocalizedName();
        String name = mat.getLocalizedName().getString();
        if (name.isEmpty() || name.equals(key)) {
            return null;
        }
        return name;
    }
}
