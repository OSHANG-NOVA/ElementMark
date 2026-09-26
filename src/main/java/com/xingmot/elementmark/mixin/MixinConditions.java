package com.xingmot.elementmark.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin条件加载插件
 * 用于根据模组存在情况动态加载不同的Mixin
 */
public class MixinConditions implements IMixinConfigPlugin {

    /**
     * 日志器<b>延迟</b>获取：本插件类在 mixin 配置解析期间就被加载，
     * 那一刻任何多余的类初始化失败都会连带整个配置失效，故不放进静态字段。
     */
    private static void log(String message, boolean important) {
        try {
            Logger logger = com.mojang.logging.LogUtils.getLogger();
            if (important) {
                logger.info(message);
            } else {
                logger.debug(message);
            }
        } catch (Throwable ignored) {
            // 日志设施不可用时静默跳过，绝不影响加载
        }
    }

    /**
     * 探测某个模组是否存在——<b>只查模组 ID，绝不用 {@code Class.forName} 探测类</b>。
     *
     * <p>血的教训（2026-09-24，整合包启动崩溃实证）：本插件在 <b>mixin 配置准备阶段</b>
     * 运行，此时各模组自己的 mixin 还没应用到目标类上。这里若 {@code Class.forName}
     * 一个第三方类（哪怕是 {@code initialize=false}），就会把该类提前加载进 JVM——
     * 如果这个类恰好是<b>那个模组自己的 mixin 目标</b>（EMI 的 {@code dev.emi.emi.api.EmiApi}
     * 正是 {@code emi.mixins.json:GlobalMixin} 的目标），EMI 的配置准备时会抛
     * {@code MixinTargetAlreadyLoadedException: ... was loaded too early}，整个游戏
     * 直接启动失败。dev 环境没炸只是配置准备顺序侥幸不同，不能当作没问题的证据。
     *
     * <p>{@code LoadingModList} 在这个阶段已就绪（模组发现早于游戏层构造），
     * 查 ID 是零类加载的安全操作。
     */
    private static boolean isLoaded(String modid) {
        LoadingModList modList = LoadingModList.get();
        return modList != null && modList.getModFileById(modid) != null;
    }

    private static boolean isEmiPresent() {
        return isLoaded("emi");
    }

    private static boolean isJeiPresent() {
        return isLoaded("jei");
    }

    @Override
    public void onLoad(String mixinPackage) {
        // 初始化时调用——留一行 INFO：排查"某个配方查看器没角标"时，
        // 这行能立刻说明是哪一侧的模组没被识别到
        log("[ElementMark] mixin 条件插件已加载 | jei=" + isJeiPresent()
                + " emi=" + isEmiPresent() + " gtceu=" + isLoaded("gtceu")
                + " ldlib=" + isLoaded("ldlib"), true);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean allow;
        try {

            // === EMI 兼容 ===
            if (mixinClassName.startsWith("com.xingmot.elementmark.mixin.emi.")) {
                allow = isEmiPresent();
            }
            // === GTCEu 兼容（EMI/JEI 配方界面里 GT 流体槽的真实绘制点） ===
            else if (mixinClassName.startsWith("com.xingmot.elementmark.mixin.gt.")) {
                allow = isLoaded("gtceu");
            }
            // === LDLib 兼容（LDLib 原生流体槽） ===
            else if (mixinClassName.startsWith("com.xingmot.elementmark.mixin.ldlib.")) {
                allow = isLoaded("ldlib");
            }
            // === JEI 兼容 ===
            else if (mixinClassName.startsWith("com.xingmot.elementmark.mixin.jei.")) {
                allow = isJeiPresent();
            }
            // === Forge 自身界面（模组列表搜索框） ===
            // 目标是 net.minecraftforge.client.gui.ModListScreen，属于 Forge 本体而非第三方模组，
            // 因此不需要探测任何模组 ID：Forge 在就说明它在。保留这个分支只为让日志口径统一
            // （每个 mixin 都会被打印出"放行/跳过"），排查"搜索没有拼音"时一眼能看出它有没有被应用。
            else if (mixinClassName.startsWith("com.xingmot.elementmark.mixin.forge.")) {
                allow = true;
            } else {
                allow = true;
            }
        } catch (Exception e) {
            log("[ElementMark] 检查 Mixin 条件时出错，按放行处理: " + e, true);
            allow = true; // 出错默认加载，避免意外禁用
        }
        if (!allow) {
            // 跳过才出声：这是"没角标"最可能的解释，其余情况（放行）留在 DEBUG
            log("[ElementMark] mixin 跳过（目标模组不存在）：" + mixinClassName, true);
        } else {
            log("[ElementMark] mixin 放行：" + mixinClassName + " -> " + targetClassName, false);
        }
        return allow;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // 接受目标类
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 应用前调用
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 应用后调用——这里是"注入确实落到目标类上"的硬证据
        log("[ElementMark] mixin 已应用到 " + targetClassName + "：" + mixinClassName, false);
    }
}
