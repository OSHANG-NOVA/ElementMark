package com.xingmot.elementmark.mixin.forge;

import java.util.List;
import java.util.stream.Collectors;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.xingmot.elementmark.search.ModSearchMatcher;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.StringUtil;
import net.minecraftforge.client.gui.ModListScreen;
import net.minecraftforge.forgespi.language.IModInfo;

/**
 * 给 <b>Forge 的模组列表搜索框</b>接入拼音与"忽略字母位置"。
 *
 * <p><b>为什么目标是 Forge 的类，而不是某个模组的配置界面。</b>玩家在"模组"界面看到的
 * 那个搜索框属于 {@code net.minecraftforge.client.gui.ModListScreen}——是 Forge 自己
 * 提供的界面，与任何配置模组无关。真正的过滤逻辑收口在它的 {@code reloadMods()} 一个方法里，
 * 故注入点就选在那里。
 *
 * <p><b>为什么整体接管（{@code HEAD} + {@code cancellable}）而不是改写那行
 * {@code String.contains}。</b>原实现把过滤写在一个 <b>lambda</b> 里：
 *
 * <pre>
 * this.mods = this.unsortedMods.stream()
 *         .filter(mi -&gt; StringUtils.toLowerCase(stripControlCodes(mi.getDisplayName()))
 *                 .contains(StringUtils.toLowerCase(search.getValue())))
 *         .collect(Collectors.toList());
 * </pre>
 *
 * lambda 会编译成 {@code lambda$reloadMods$0} 这样的<b>合成方法</b>，其编号随编译环境漂移，
 * 用它当注入目标非常脆弱。而 {@code reloadMods()} 本身是有名字的真实方法、描述符恒为
 * {@code ()V}，在 Forge 47.x 全系列都存在——在它的入口处整体接管，既稳定又把改动面
 * 限制在一个方法内。
 *
 * <p><b>行为差异只在"多出来的结果"。</b>接管后仍以 {@link ModSearchMatcher} 为准，
 * 而该匹配器把"原文子串命中"放在最前面，与 Forge 原生行为逐字一致；拼音与"忽略字母位置"
 * 只是在其后追加的兜底。因此英文/数字搜索的手感不变，仅原先搜不到的中文/跳字母查询
 * 变得能搜到。
 *
 * <p><b>字段用 {@code @Shadow} 直读，不反射。</b>{@code mods} / {@code lastFilterText}
 * 是被赋值的目标，{@code unsortedMods} 是构造期定下的全量快照（{@code final}，故需
 * {@code @Final}）。三个字段名都是 Forge 自己的字段名，不经过混淆。
 *
 * <p><b>为什么 {@code remap = false}。</b>目标是 Forge 的类，Forge 类不参与混淆，
 * 故类名与方法名一律按原名匹配；若走 remap，反而会因为找不到映射而注入失败。
 */
@Mixin(value = ModListScreen.class, remap = false)
public abstract class ModListScreenMixin {

    /** 当前显示（过滤后）的模组列表——原方法在这里赋值，我们照做 */
    @Shadow(remap = false)
    private List<IModInfo> mods;

    /** 过滤前的全量模组列表（构造期快照，不可变） */
    @Shadow(remap = false)
    @Final
    private List<IModInfo> unsortedMods;

    /** Forge 用来判断"搜索词是否变了"的上一轮文本 */
    @Shadow(remap = false)
    private String lastFilterText;

    /** 模组列表上方的搜索框 */
    @Shadow(remap = false)
    private EditBox search;

    /**
     * 接管过滤：原文命中（与原生一致）之外，再补拼音与"忽略字母位置"。
     *
     * <p>注入点是 {@code HEAD}，并用 {@code ci.cancel()} 跳过原方法体——原方法只做这一件事，
     * 整体替换比"改一行表达式"更不容易被 Forge 的小改动影响。
     *
     * <p>{@code require = 0}：万一将来 Forge 改了方法名/描述符，本注入<b>静默失效</b>
     * （退回原生搜索）而不是让游戏起不来。
     */
    @Inject(method = "reloadMods()V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void elementmark$reloadModsWithSmartSearch(CallbackInfo ci) {
        String query = this.search.getValue();
        this.mods = this.unsortedMods.stream()
                // 先剥颜色代码再匹配：Forge 原生就是先 stripControlCodes 再 toLowerCase，
                // 而带颜色代码的模组名（"§6GregTech"）不剥的话，连最普通的 "greg" 都搜不到。
                // 必须与原行为保持同一口径，否则接管之后反而比原生更难用。
                .filter(info -> ModSearchMatcher.matches(stripColor(info.getDisplayName()), query))
                .collect(Collectors.toList());
        this.lastFilterText = query;
        // 原方法体只做上面这两件事，故直接取消，避免二次覆盖
        ci.cancel();
    }

    /**
     * 剥掉字符串里的 {@code §} 颜色/格式代码（转发原版 {@code StringUtil.stripColor}）。
     *
     * <p>单独包一层是为了在 mixin 里保持可读：注入方法本身不该再塞进 try/catch 或
     * 空值判断——{@code getDisplayName()} 在 Forge 里恒非空，{@code stripColor} 对
     * {@code null} 也有兜底。
     */
    private static String stripColor(String value) {
        return value == null ? null : StringUtil.stripColor(value);
    }
}
