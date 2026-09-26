package com.xingmot.elementmark.client;

import java.util.function.Consumer;

import com.xingmot.elementmark.BadgeResolver;
import com.xingmot.elementmark.ConfigLoader;
import com.xingmot.elementmark.ConfigLoader.Corner;
import com.xingmot.elementmark.ConfigLoader.ScrollMode;
import com.xingmot.elementmark.circuit.CircuitDetector;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 配置界面：在模组列表里点「配置」打开。
 *
 * <p>界面上可改三项，都是即时生效并写回 config/elementmark.txt：
 * <ul>
 *   <li><b>角落</b>：按钮循环切换四个角；</li>
 *   <li><b>滚动方式</b>：按钮在"左右摆动 / 单向循环"之间切换，只影响放不下的超长缩写；</li>
 *   <li><b>尺寸</b>：滑动条调整 {@code font_scale}（字号系数），拖动即改。</li>
 * </ul>
 * 下方用三个样例物品预览角标位置与大小（铁矿 / 铁块 / 铁锭），
 * 每个样例的正下方标出它解析出的缩写，方便判断是哪个物品的定位有问题。
 *
 * <p><b>预览不做任何自定义绘制</b>：样例物品就是普通的
 * {@code GuiGraphics.renderItem}，角标由 {@code ItemRenderer.render} 那支 mixin
 * 照常画上去——与物品栏、容器走的是同一条路径、同一份定位代码。
 * 因此"预览与实际不一致"在结构上不可能发生。
 *
 * <p>界面上还有「重新读取配置」按钮，便于手动改完配置立刻看到效果——它会把三个控件的
 * 显示一并同步到文件里的新值，避免"界面写着旧值、实际按新值渲染"。
 */
public class ConfigScreen extends Screen {

    private static final Component TITLE = Component.translatable("elementmark.config.title");

    /**
     * 界面上展示的配置文件位置。
     *
     * <p>只给相对路径而不用 {@code ConfigLoader.configPath()} 的绝对路径：真实路径往往
     * 长到把整行挤满（整合包实例尤其如此），而"相对 .minecraft 的 config 目录"已足够
     * 让用户找到文件。若要精确定位，看游戏日志里 {@code [ElementMark]} 开头的行更省事。
     */
    private static final String CONFIG_DISPLAY_PATH = "\\config\\elementmark.txt";
    /** 角落按钮的显示名；顺序与 Corner 枚举的声明顺序一致 */
    private static final Component[] CORNER_LABELS = {
            Component.translatable("elementmark.corner.top_left"),
            Component.translatable("elementmark.corner.top_right"),
            Component.translatable("elementmark.corner.bottom_left"),
            Component.translatable("elementmark.corner.bottom_right"),
    };
    /** 滚动方式按钮的显示名；顺序与 ScrollMode 枚举的声明顺序一致 */
    private static final Component[] SCROLL_LABELS = {
            Component.translatable("elementmark.scroll.sway"),
            Component.translatable("elementmark.scroll.loop"),
    };
    /**
     * 预览用的示例物品，取同一种材料（铁）的三种形态，便于对照"同一缩写在不同物品上是否对齐"：
     * <ul>
     *   <li>铁矿 → Fe，未加工矿石贴图</li>
     *   <li>铁块 → Fe，<b>方块</b>模型：GUI 下有旋转与 0.625 缩放，最容易被判成"位置不一样"</li>
     *   <li>铁锭 → Fe，平面 generated 模型，最典型的形态</li>
     * </ul>
     * 三者缩写相同，所以画面上的差异一定来自物品模型本身，而不是解析结果。
     */
    private static final ItemStack[] PREVIEW_STACKS = {
            new ItemStack(Items.RAW_IRON),
            new ItemStack(Items.IRON_BLOCK),
            new ItemStack(Items.IRON_INGOT),
    };

    private final Screen parent;
    /** 角落按钮与滚动方式按钮；「重新读取配置」之后要把按钮文字同步到新读到的值 */
    private Button cornerButton;
    private Button scrollButton;
    /** 尺寸滑动条；「重新读取配置」之后要把它的位置同步到新读到的值 */
    private ScaleSlider scaleSlider;

    public ConfigScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 110;
        final int WIDTH = 220;
        int y = this.height / 2 - 10;

        // 四行排布：角落 / 滚动方式 / 尺寸 / 按钮行，行距 28（20 高 + 8 间隔）
        this.cornerButton = this.addRenderableWidget(Button.builder(cornerLabel(ConfigLoader.corner()), button -> {
                    // 当前值以 ConfigLoader 为权威，不在界面内另存暂存值：
                    // 写盘失败时内存值不变，按钮文字自然与文件保持一致。
                    Corner next = nextCorner(ConfigLoader.corner());
                    if (ConfigLoader.saveCorner(next)) {
                        button.setMessage(cornerLabel(next));
                    }
                })
                .bounds(left, y, WIDTH, 20)
                .build());

        this.scrollButton = this.addRenderableWidget(Button.builder(scrollLabel(ConfigLoader.scrollMode()), button -> {
                    // 与角落按钮同款：即时写回配置文件，界面关掉与否都已生效
                    ScrollMode next = nextScrollMode(ConfigLoader.scrollMode());
                    if (ConfigLoader.saveScrollMode(next)) {
                        button.setMessage(scrollLabel(next));
                    }
                })
                .bounds(left, y + 28, WIDTH, 20)
                .build());

        // 尺寸：拖动即改，不需要按「应用」。取值范围与钳制都由 ConfigLoader 提供，界面不另设一套
        this.scaleSlider = this.addRenderableWidget(
                new ScaleSlider(left, y + 56, WIDTH, 20, ConfigLoader.fontScale()));

        // 最后一排并排放，省一行高度：左「重新读取配置」，右「完成」
        int half = (WIDTH - 4) / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("elementmark.config.reload"), button -> {
                    ConfigLoader.reload();
                    BadgeResolver.invalidate();
                    // 电路识别同样按物品缓存，一起清；手写映射也在此后重新解析
                    CircuitDetector.invalidate();
                    // 文件被外部改过（手写成 1.2、换了个角、改了滚动方式）时，让三个控件的显示都跟上，
                    // 否则"界面上的值"与"真正生效的值"会悄悄分家
                    this.cornerButton.setMessage(cornerLabel(ConfigLoader.corner()));
                    this.scrollButton.setMessage(scrollLabel(ConfigLoader.scrollMode()));
                    this.scaleSlider.syncFromConfig(ConfigLoader.fontScale());
                })
                .bounds(left, y + 84, half, 20)
                .build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(left + half + 4, y + 84, half, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, TITLE, centerX, this.height / 2 - 50, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("elementmark.config.hint", CONFIG_DISPLAY_PATH),
                centerX, this.height / 2 - 36, 0xA0A0A0);

        renderPreview(graphics, centerX);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 样例物品预览。
     *
     * <p>只做两件事：把物品画出来（{@code graphics.renderItem}）、在下方标出解析结果。
     * 角标本身不在这里画——{@code renderItem} 会走 {@code ItemRenderer.render}，
     * 被本模组的 mixin 命中，由 {@link ElementMarkClient#drawBadge} 用与实机
     * 完全相同的代码画上去。所以这里既不需要抑制 mixin，也不可能出现"画两遍"。
     */
    private void renderPreview(GuiGraphics graphics, int centerX) {
        final int STRIDE = 44;
        int previewY = this.height / 2 - 96;
        int startX = centerX - (PREVIEW_STACKS.length * STRIDE) / 2 + STRIDE / 2 - 8;

        for (int i = 0; i < PREVIEW_STACKS.length; i++) {
            ItemStack stack = PREVIEW_STACKS[i];
            int x = startX + i * STRIDE;
            graphics.renderItem(stack, x, previewY);
            // 每个样例下面标出解析出的缩写，便于对照是哪个物品出的问题。
            // 走 ElementMarkClient.resolveBadge（与实机绘制同一份解析），
            // 于是电路板这里标的就是电压等级，而不是"没有元素"的空结果。
            ElementMarkClient.Badge badge = ElementMarkClient.resolveBadge(stack);
            graphics.drawCenteredString(this.font,
                    badge == null ? "-" : badge.text(),
                    x + 8, previewY + 20, 0x606060);
        }

        graphics.drawCenteredString(this.font,
                Component.translatable("elementmark.config.preview"),
                centerX, previewY + 34, 0xA0A0A0);
    }

    private static Component cornerLabel(Corner corner) {
        return CORNER_LABELS[corner.ordinal()];
    }

    private static Corner nextCorner(Corner current) {
        Corner[] values = Corner.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    private static Component scrollLabel(ScrollMode mode) {
        return Component.translatable("elementmark.config.scroll", SCROLL_LABELS[mode.ordinal()]);
    }

    private static ScrollMode nextScrollMode(ScrollMode current) {
        ScrollMode[] values = ScrollMode.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    /**
     * 尺寸（字号系数）滑动条：拖动即改，无需「应用」。
     *
     * <p><b>为什么不去覆写 setValue</b>：1.20.1 的 {@code AbstractSliderButton.setValue}
     * 是 <b>private</b>，覆写不了。所以这里只实现它的两个抽象回调——
     * {@link #updateMessage}（把当前值写进按钮文字）与 {@link #applyValue}（落盘），
     * 拖动流程交给父类。
     *
     * <p><b>同步而不回写</b>：{@link #syncFromConfig} 直接写父类 protected 的 {@code value}
     * 字段，绕开 {@code setValue → applyValue → 写文件} 这条链，免得"只是刷新一下显示"
     * 也去动用户的配置文件。
     */
    private static final class ScaleSlider extends AbstractSliderButton {

        /**
         * 滑动条位置 0~1 所映射的字号跨度。
         *
         * <p>区间取自 {@link ConfigLoader#FONT_SCALE_MIN} / {@link ConfigLoader#FONT_SCALE_MAX}，
         * 与 {@code saveFontScale} 的钳制同源，所以"滑动条能摆出的值"就是"文件里合法的值"，
         * 不存在两边各设一套区间、摆到端点却被悄悄改掉的情况。
         */
        private static final float SPAN = ConfigLoader.FONT_SCALE_MAX - ConfigLoader.FONT_SCALE_MIN;

        ScaleSlider(int x, int y, int width, int height, float initialScale) {
            super(x, y, width, height, Component.empty(), toFraction(initialScale));
            // 父类构造期可能已调过一次 updateMessage，这里再调一次以确保文案与初值一致
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("elementmark.config.font_scale", format(current())));
        }

        @Override
        protected void applyValue() {
            ConfigLoader.saveFontScale(current());
        }

        /** 把配置里的值搬到滑动条上，不触发 {@link #applyValue}（即不回写文件） */
        void syncFromConfig(float scale) {
            this.value = toFraction(scale);
            updateMessage();
        }

        /** 滑动条当前位置对应的字号系数 */
        private float current() {
            return ConfigLoader.FONT_SCALE_MIN + (float) this.value * SPAN;
        }

        /** 字号系数 -&gt; 滑动条位置（0~1） */
        private static double toFraction(float scale) {
            return (ConfigLoader.clampScale(scale) - ConfigLoader.FONT_SCALE_MIN) / SPAN;
        }

        private static String format(float scale) {
            return String.format("%.2f", scale);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** 让外部（配置按钮工厂）以统一签名构造本界面 */
    public static Consumer<Screen> opener() {
        return ConfigScreen::new;
    }
}
