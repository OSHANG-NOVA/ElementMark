package com.xingmot.elementmark;

import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import com.xingmot.elementmark.circuit.CircuitConfig;
import com.xingmot.elementmark.circuit.CircuitDetector;
import com.xingmot.elementmark.client.ConfigScreen;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Element Mark：按矿辞 tag 给 GUI 物品画缩写角标（元素符号或自定义文字）。
 *
 * 不绑定任何具体模组，任何遵循 c: / forge: 材料 tag 规范的物品都能命中。
 * 全部逻辑在客户端；服务端只负责生成配置文件，避免专服装了这个 mod 却没有配置。
 */
@Mod("elementmark")
public class ElementMark {

    private static void registerConfigScreen(FMLJavaModLoadingContext context) {
        context.registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new ConfigScreen(parent)));
    }

    public ElementMark(FMLJavaModLoadingContext context) {
        // 配置生成与加载放在 mod 构造期，早于任何一次渲染
        ConfigLoader.init();

        // 电路板电压角标的配置走 Forge 自带的 toml（config/elementmark-client.toml）。
        // 只在客户端注册：这个配置只管"角标画成什么样"，服务端读了也没有用处，
        // 注册成 CLIENT 类型还能避免专服生成一份永远不会被读的文件。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 用构造参数上的 registerConfig，而不是已过时的 ModLoadingContext.get()：
            // FMLJavaModLoadingContext 继承自 ModLoadingContext，方法一样，
            // 但拿到的是"本次构造所属的那个 mod 容器"，不依赖线程局部的全局上下文。
            context.registerConfig(ModConfig.Type.CLIENT, CircuitConfig.SPEC);
        }

        // 模组列表「配置」按钮：只在客户端注册，避免服务端加载 Screen 相关类
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerConfigScreen(context);
        }
    }

    /**
     * 客户端事件总线订阅者。
     * 挂到资源重载监听器上，让配置文件随 F3+T / 资源包重载一起刷新，
     * 调试时改完存盘即可看到效果，无需重启游戏。
     *
     * <p>本模组<b>不注册任何按键绑定</b>：重新读取配置只有两个入口——
     * 游戏原生的 F3+T 资源重载，以及配置界面里的「重新读取配置」按钮。
     */
    @Mod.EventBusSubscriber(modid = "elementmark", value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ClientEvents {

        private ClientEvents() {}

        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(new ConfigReloadListener());
        }

        /**
         * Forge 配置（{@code elementmark-client.toml}）重载后清掉电路识别缓存。
         *
         * <p>必须挂这一手：{@link CircuitDetector} 按物品缓存"是不是电路、是哪一档"，
         * 而手写映射（{@code overrides}）是配置内容——用户在游戏里改完 toml 保存后，
         * Forge 会重载配置，但已缓存的物品不会重新查表，表现为"配置改了没反应"。
         * 清一次缓存即可，下一帧自然按新配置重算。
         *
         * <p>注意 {@code bus = Bus.MOD}：本类两个方法订阅的都是<b>模组总线</b>事件
         * （{@code RegisterClientReloadListenersEvent} 与 {@code ModConfigEvent} 都实现了
         * {@code IModBusEvent}）。默认的 FORGE 总线收不到它们——这正是此前
         * "F3+T 不重读配置"的根因。
         */
        @SubscribeEvent
        public static void onConfigReload(net.minecraftforge.fml.event.config.ModConfigEvent.Reloading event) {
            CircuitDetector.invalidate();
        }
    }
}
