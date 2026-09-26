package com.xingmot.elementmark.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.xingmot.elementmark.BadgeResolver;
import com.xingmot.elementmark.ConfigLoader;
import com.xingmot.elementmark.circuit.CircuitConfig;
import com.xingmot.elementmark.circuit.CircuitDetector;
import com.xingmot.elementmark.circuit.RainbowVertexConsumer;
import com.xingmot.elementmark.circuit.VoltageTier;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

/**
 * GUI 物品角标绘制（物品栏 / 容器等真实渲染路径）。
 *
 * <p><b>职责边界</b>：本类只负责"把 {@link BadgePlacement} 算出的定位映射进 3D 矩阵空间并绘制"。
 * 至于角标该画在图标哪个位置、多大，全部由 {@link BadgePlacement} 决定。
 * 配置界面的样例预览<b>不另写绘制代码</b>——它直接调 {@code GuiGraphics.renderItem}，
 * 由同一支 mixin 把角标画上去，所以预览与实机天然一致，不存在"对齐参数"这回事。
 *
 * <p><b>坐标数学</b>（结论逐条取自反汇编，不是推测；推导见
 * {@code .workbuddy/notes/matrix-derivation.md}）：
 *
 * <ol>
 *   <li><b>外层</b>（{@code GuiGraphics.renderItem} 字节码）施加的是：
 *       {@code translate(x+8, y+8, 0)} → {@code mulPoseMatrix(scaling(1,-1,1))}
 *       → {@code scale(16,16,16)}。
 *       注意 {@code scale} 的 <b>三分量全正</b>，y 翻转是靠那个独立的
 *       {@code mulPoseMatrix} 完成的。</li>
 *   <li><b>ItemRenderer.render</b> 先 {@code pushPose()}，再经
 *       {@code ForgeHooksClient.handleCameraTransforms}（对 generated 类贴图物品是恒等），
 *       然后执行一次 {@code translate(-0.5, -0.5, -0.5)}。该 translate 与 render 开头那次
 *       pushPose 配对（末尾 popPose），<b>不含</b>独立的 pop；foil 分支另有自己的 push/pop。</li>
 *   <li><b>我们的 TAIL 注入点</b>在末尾 popPose 之后，此时矩阵 =
 *       外层 × {@code T(-0.5,-0.5,-0.5)}。</li>
 * </ol>
 *
 * <p><b>原点落在图标中心</b>——{@link #ANCHOR_X} / {@link #ANCHOR_Y} 把这个结论固化为
 * {@code -ICON_SIZE/2}。它由实测反解而来，不能靠累加 translate 常量得出：外层那段
 * {@code translate(x+8, y+8, 0)} 里的 {@code +8} 是"槽位左上角到图标中心"的距离，
 * 与别的常量直接相加会得到错误的物理含义。
 *
 * <p>随后 {@code scale(SCALE, -SCALE, SCALE)} 把单位变成"字形单位"
 * （1 字形单位 = 16 × SCALE = 0.8 屏幕像素，图标即 {@link BadgePlacement#ICON_SIZE} 见方），
 * 该负号把 y 再翻一次，于是<b>本坐标系里 y 向下</b>，
 * 与 {@link BadgePlacement} 的约定一致。
 *
 * <p>于是第一步：把原点从"图标中心"平移到"图标左上角"，
 * 即沿两个轴的负向各走 {@code ICON_SIZE/2} 字形单位，再加上本角落的笔位
 * （{@code BadgePlacement} 给出）。
 *
 * <p><b>缩放锚点</b>：{@code PoseStack} 是右乘，先调用的变换作用在更外层。所以必须
 * <b>先 {@code translate} 到笔位、再 {@code scale}</b>，然后在缩放域里画在原点——
 * 这样锚点才是笔位本身。若反过来"先 translate 到图标原点、再 scale、然后在缩放域里给
 * {@code drawX/drawY}"，坐标会被再乘一次字号，位置随字号非线性漂移。
 *
 * <p>配合 {@code BadgePlacement} 里"右对齐用 {@code ICON_SIZE - PAD - width*ts}"这类
 * 按最终宽度算的锚点，四个角在任意字号下都严丝合缝：
 * 左对齐左缘恒为 {@code PAD}、右对齐右缘恒为 {@code ICON_SIZE - PAD}，
 * 纵向同理，因此改 {@code font_scale} 时文字始终贴在所选角落，不需要任何位置补偿参数。
 */
public final class ElementMarkClient {

    /** 字形单位 -&gt; 物品单位换算；1 字形单位 = 0.8 屏幕像素 */
    private static final float SCALE = 0.05F;
    /**
     * GUI 像素空间里 1 字形单位的大小（屏幕像素）：{@code 16 × SCALE}。
     * JEI 流体角标的绘制直接运行在 GUI 像素空间（没有物品渲染的 16 倍缩放），故用它做换算。
     */
    private static final float PX_PER_GLYPH = 16.0F * SCALE;
    /**
     * 流体条目角标的 z 抬升（GUI 像素单位，JEI/EMI 两条路径共用）：流体贴图画在 z≈0
     * （EMI 侧内部多抬 100，仍远低于本值），取 200 稳定在贴图之上、
     * 又不至于盖过工具提示层（约 400）。
     */
    private static final float FLUID_Z_LIFT = 200.0F;
    /**
     * 把矩阵原点从"图标中心"移到"图标左上角"所需的本地位移（字形单位），即 {@code -ICON_SIZE / 2}。
     *
     * <p>这里只做"原点搬家"，<b>不参与</b>角落对齐——那是 {@link BadgePlacement}
     * 的职责。纵向的对齐公式还额外依赖 {@code drawInBatch} 的 y 是"字形框顶边"
     * 这一事实，见 {@link BadgePlacement#ASCENT}。
     */
    private static final float ANCHOR_X = -BadgePlacement.ICON_SIZE / 2.0F;
    private static final float ANCHOR_Y = -BadgePlacement.ICON_SIZE / 2.0F;
    /** z 抬升（字形单位）。flat 模型朝前表面在 z=+0.5 单位，方块模型 GUI 旋转后顶点最多 ~0.87，取 1.5 物品单位（=30 字形单位）稳定在前 */
    private static final float Z_LIFT = 30.0F;
    /**
     * 元素角标的主体色（白）。
     *
     * <p>只有元素角标用它；电路角标改用 {@link VoltageTier#COLORS} 的等级配色，
     * {@code OpV} / {@code MAX} 两档再改成动态色相（连这个常量都不参与）。
     */
    private static final int COLOR_FILL = 0xFFFFFF;
    /** 描边色（浅色主体用） */
    private static final int COLOR_EDGE = 0x333333;
    /**
     * 描边色（深色主体用）。
     *
     * <p>原本描边恒为深灰——那时主体恒为白色，深灰垫在白色底下正好勾出轮廓。
     * 改成按电压等级配色后，{@code UEV}（{@code 1E40D8}，深蓝）这类暗色主体
     * 再垫深灰就完全没有轮廓了，于是按主体明度二选一（见 {@link #edgeColorFor}）。
     * 用户自定义的这套配色里只有 {@code UEV} 一档落在"需要浅描边"的那一侧。
     */
    private static final int COLOR_EDGE_LIGHT = 0xE8E8E8;
    /**
     * 滚动的横向速度（字形单位 / 秒）。1 字形单位 = 0.8 屏幕像素，即约 19 屏幕像素/秒。
     *
     * <p>两种方式（{@link ConfigLoader.ScrollMode 摆动 / 单向循环}）共用它。
     *
     * <p>这是<b>屏幕上的</b>速度：绘制发生在已经缩放过的坐标域里，故实际换算时还要除以字号。
     */
    private static final float SCROLL_SPEED = 24.0F;

    /**
     * {@link ConfigLoader.ScrollMode#SWAY 往返摆动}在两端时的停留时长（秒）。
     *
     * <p>这正是摆动方式存在的意义：单向循环时长内容一直在动，每个字都一闪而过，读起来是断的；
     * 两端各停一下，才真正给出"把开头和结尾看清"的时间。
     * （默认已是 {@code scroll:loop} 单向循环，想要这种"停一停"的节奏就在配置里切成 {@code sway}。）
     */
    private static final float SCROLL_DWELL = 0.6F;

    /**
     * {@link ConfigLoader.ScrollMode#LOOP 单向循环}模式下的空档（字形单位）：文字完全滚出窗口
     * 之后、下一遍滚入之前的间隔。
     *
     * <p>取 {@code 0}——<b>无缝循环</b>：末尾刚离开左缘，开头就紧接着从右缘进来。
     * 要注意用户实际感知到的"看不到字"时长并不等于 {@code 空档 / 速度}：逐字裁剪用的是
     * "字形<b>中心</b>落在窗口内"，于是末尾最后一个字要在中心越过左缘时才算消失、
     * 开头第一个字也要等中心进入右缘才出现，两端各多出半个字宽，
     * 真正的空白时长是 {@code (空档 + 一个字宽) / 速度}（约 0.22 秒，全部来自裁剪判据）。
     */
    private static final float SCROLL_GAP = 0.0F;

    private ElementMarkClient() {}

    /**
     * 一次角标解析的完整结果。
     *
     * @param text     要画的文字（元素缩写，或电路电压等级名）
     * @param animated 是否走动态色相蒙版（{@code OpV} / {@code MAX}）。
     *                 为 {@code true} 时 {@code color} 不生效——颜色由
     *                 {@code RainbowVertexConsumer} 逐顶点生成。
     * @param color    主体文字色（{@code 0xRRGGBB}）。元素角标恒为白，电路角标取
     *                 {@link VoltageTier#COLORS} 的等级配色——"颜色"必须由本 record 承载，
     *                 才能让绘制与配置界面预览拿到同一个值，不至于出现"预览白、实机彩"。
     * @param hueStart 动态色相的区间起点（0~1）。{@code animated} 为 {@code false} 时无意义
     * @param hueEnd   动态色相的区间终点（0~1）。{@code animated} 为 {@code false} 时无意义
     * @param corner   画在哪个角
     */
    public record Badge(String text, boolean animated, int color, float hueStart, float hueEnd,
                        ConfigLoader.Corner corner) {}

    /**
     * 解析某个物品最终该显示什么角标。
     *
     * <p><b>唯一出处</b>：绘制（{@link #drawBadge}）与配置界面的预览标注都调它，
     * 于是"预览里写的字"与"实机画出来的字"不可能分家——与 {@link BadgePlacement}
     * 之于定位是同一个设计原则。
     *
     * <p><b>电路优先于元素</b>：电路板不可能带化学元素，故一旦认出电压等级就
     * <b>整个取代</b>元素角标（用户明确要求"如果显示了这个电压等级 就直接覆盖元素"）。
     * 电路角标固定左上角，元素角标才跟用户的 corner 设置走——电压等级是
     * "一眼扫过去找最高档"的用途，固定在左上比跟着角落设置飘更实用。
     *
     * @return 该物品的角标；两者都没有时返回 {@code null}（不画）
     */
    public static Badge resolveBadge(ItemStack stack) {
        int tier = CircuitConfig.enabled() ? CircuitDetector.detect(stack) : CircuitDetector.NOT_CIRCUIT;
        if (tier != CircuitDetector.NOT_CIRCUIT) {
            String name = VoltageTier.nameOf(tier);
            return name == null ? null
                    : new Badge(name, VoltageTier.isAnimated(tier), VoltageTier.colorOf(tier),
                                VoltageTier.hueStartOf(tier), VoltageTier.hueEndOf(tier),
                                ConfigLoader.Corner.TOP_LEFT);
        }
        String symbol = BadgeResolver.resolve(stack);
        return symbol == null
                ? null
                : new Badge(symbol, false, COLOR_FILL, 0.0F, 1.0F, ConfigLoader.corner());
    }

    public static void drawBadge(ItemStack stack, PoseStack pose, MultiBufferSource buffer, int light) {
        Badge badge = resolveBadge(stack);
        if (badge == null) {
            return;
        }
        String symbol = badge.text();
        boolean animated = badge.animated();

        Font font = Minecraft.getInstance().font;
        // 定位与字号统一由 BadgePlacement 决定（其中已含用户的 font_scale）。
        // 本路径的 scale(SCALE, ...) 已承担"字形单位 -> 屏幕像素"的换算，
        // 故这里直接取 place.textScale() 用，不要再乘任何换算系数。
        BadgePlacement.Placement place = BadgePlacement.compute(font, symbol, badge.corner());

        // OpV / MAX 走"文字当蒙版、蒙版下流动彩图"：把数据源包成逐顶点染动态色相的版本。
        // 包在 getBuffer 层，于是下面的 drawInBatch 调用一个字都不用改。
        // 色相区间由 VoltageTier 给出（MAX = 整圈，OpV = 紫→红弧段），
        // 弧段的折返逻辑在 RainbowVertexConsumer 内部，这里不必关心。
        MultiBufferSource target = animated
                ? RainbowVertexConsumer.wrap(buffer,
                        RainbowVertexConsumer.phaseFor(CircuitConfig.rainbowCycleSeconds()),
                        badge.hueStart(), badge.hueEnd())
                : buffer;

        pose.pushPose();
        pose.scale(SCALE, -SCALE, SCALE);

        // 两段平移合成一次：原点先从"图标中心"搬到"图标左上角"，z 抬到物品模型前面。
        // 平移必须在 scale 之前施加——PoseStack 是右乘，先调用的变换作用在更外层，
        // 于是"先平移后缩放"= T(笔位) × S(字号)，缩放锚点落在笔位上。
        pose.translate(ANCHOR_X, ANCHOR_Y, Z_LIFT);
        drawBadgeText(font, symbol, badge.color(), place, pose, target, light, animated);
        pose.popPose();
    }

    /**
     * JEI 流体条目的角标入口，由 {@code FluidTankRendererMixin} 在其
     * {@code FluidTankRenderer.render} 的 TAIL 调用。
     *
     * @param ingredient JEI 传来的原始配料（泛型擦除后是 Object），仅处理 FluidStack
     */
    public static void drawJeiFluidBadge(GuiGraphics guiGraphics, Object ingredient, int x, int y) {
        if (!(ingredient instanceof FluidStack stack) || stack.isEmpty()) {
            return;
        }
        drawFluidBadgeAt(guiGraphics, stack.getFluid(), x, y);
    }

    /**
     * EMI 流体条目的角标入口，由 {@code FluidEmiStackMixin} 在
     * {@code FluidEmiStack.render} 的 TAIL 调用。
     *
     * <p>EMI 的流体图标不走 {@code ItemRenderer.render}，也不走 JEI 的
     * {@code FluidTankRenderer}，而是由 {@code FluidEmiStack} 直接绘制流体贴图——
     * 索引页、配方槽、侧栏全部经过这一个方法，故它是 EMI 侧的唯一注入点。
     * 流体栈未被 EMI 批处理烘焙（{@code StackBatcher$Batchable} 只由物品栈实现），
     * 该方法按帧执行，角标随之持续可见。
     *
     * @param fluid EMI 流体栈内的流体本体
     */
    public static void drawEmiFluidBadge(GuiGraphics guiGraphics, Fluid fluid, int x, int y) {
        if (fluid == null || fluid.isSame(Fluids.EMPTY)) {
            return;
        }
        drawFluidBadgeAt(guiGraphics, fluid, x, y);
    }

    /**
     * 模组自有 GUI widget（GT / lowdraglib 流体槽）里的流体角标入口。
     *
     * <p>与 {@link #drawEmiFluidBadge} 的区别在于<b>绘制时机与场景</b>：这类 widget 的
     * {@code drawInBackground} 既服务配方查看器里的配方界面，也服务机器自己的 GUI
     * （储罐、处理器的流体格），而角标只应出现在配方查看器里——故先按当前屏幕的
     * 类名前缀判断是否为 EMI / JEI 的界面，不是则直接跳过。
     *
     * <p>屏幕类型用<b>类名前缀</b>判断而不是 instanceof：本方法可能在 EMI / JEI
     * 未安装时被调用（mixin 已按条件加载，但 LDLib 存在而 EMI 不存在是合法组合），
     * 直接引用它们的类会在解析时抛 {@code NoClassDefFoundError}。
     *
     * @param x,y 流体槽内居中 16×16 图标区的左上角（槽位坐标，非屏幕坐标）
     */
    public static void drawGuiFluidBadge(GuiGraphics guiGraphics, Fluid fluid, int x, int y) {
        if (fluid == null || fluid.isSame(Fluids.EMPTY)) {
            return;
        }
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) {
            return;
        }
        String name = screen.getClass().getName();
        if (!name.startsWith("dev.emi.emi.") && !name.startsWith("mezz.jei.")) {
            return;
        }
        drawFluidBadgeAt(guiGraphics, fluid, x, y);
    }

    /**
     * 配方查看器（JEI/EMI 共用）流体角标的实际绘制。
     *
     * <p>坐标直接是 GUI 像素空间：{@code (x, y)} 是流体图标的左上角，
     * 图标区按 16×16 像素 = {@link BadgePlacement#ICON_SIZE} 字形单位见方，
     * 于是 {@code PX_PER_GLYPH} 的缩放让这套路径与物品路径共用同一份
     * {@link BadgePlacement} 定位。GUI 空间 y 本来就向下，
     * 不需要物品路径里那次负号翻转。
     */
    private static void drawFluidBadgeAt(GuiGraphics guiGraphics, Fluid fluid, int x, int y) {
        String symbol = BadgeResolver.resolveFluid(fluid);
        if (symbol == null) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        BadgePlacement.Placement place = BadgePlacement.compute(font, symbol, ConfigLoader.corner());

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(x, y, FLUID_Z_LIFT);
        pose.scale(PX_PER_GLYPH, PX_PER_GLYPH, PX_PER_GLYPH);
        drawBadgeText(font, symbol, COLOR_FILL, place, pose, guiGraphics.bufferSource(), LightTexture.FULL_BRIGHT, false);
        pose.popPose();
        // 立刻结算，保证文字盖在流体贴图上，不受后续渲染状态影响
        guiGraphics.bufferSource().endBatch();
    }

    /**
     * 在"图标左上角为原点、单位 = 字形单位、y 向下"的坐标域里绘制角标文字
     * （物品路径与 JEI 路径各自先变换到这个域，此后共用同一份定位与滚动/裁剪逻辑）。
     *
     * @param color   主体文字色（{@code 0xRRGGBB}）。元素角标为白，电路角标为
     *                {@link VoltageTier#COLORS} 的等级配色；
     *                {@code animated} 为 {@code true} 时不生效（颜色由
     *                {@code RainbowVertexConsumer} 逐顶点生成）
     * @param animated 是否为 {@code OpV} / {@code MAX} 的动态色相蒙版模式
     */
    private static void drawBadgeText(Font font, String symbol, int color, BadgePlacement.Placement place,
                                      PoseStack pose, MultiBufferSource buffer, int light, boolean animated) {
        pose.pushPose();
        pose.translate(place.drawX(), place.drawY(), 0.0F);
        pose.scale(place.textScale(), place.textScale(), 1.0F);

        // 在缩放域里画在原点，于是缩放锚点就是上面的笔位。
        Matrix4f matrix = pose.last().pose();
        if (place.scrolling()) {
            // 文字放不下时不再缩小，改为在图标框内横向独占一行地滚动（方式见 scroll 配置项）
            drawScrolling(font, symbol, color, place, matrix, buffer, light, animated);
        } else if (animated) {
            // OpV / MAX：只画一遍。描边那层是把同一段文字在偏移处再画一次，若也套动态色相，
            // 两层色相在半透明边缘上叠加会糊成一团，反而看不出"字是蒙版"。
            // 传进去的颜色会被 RainbowVertexConsumer 丢弃，这里给什么都不影响。
            font.drawInBatch(symbol, 0.0F, 0.0F, color, false, matrix, buffer, Font.DisplayMode.NORMAL, 0, light);
        } else {
            // 描边偏移要除以字号，才能在屏幕上恒为 1 字形单位。
            float edge = 1.0F / place.textScale();
            // 双层画法：描边色偏移垫底勾出轮廓，主体色压在上面。
            // 描边色按主体明度自动选深浅——主体深就用浅描边，否则轮廓会消失在主体里。
            font.drawInBatch(symbol, edge, edge, edgeColorFor(color), false, matrix, buffer, Font.DisplayMode.NORMAL, 0, light);
            font.drawInBatch(symbol, 0.0F, 0.0F, color, false, matrix, buffer, Font.DisplayMode.NORMAL, 0, light);
        }
        pose.popPose();
    }

    /**
     * 按主体色的明度挑描边色：亮主体配深描边，暗主体配浅描边。
     *
     * <p>亮度用 ITU-R BT.601 的加权和（人眼对绿最敏感、对蓝最迟钝），
     * 阈值 140 是按 {@link VoltageTier#COLORS} 这套用户配色实测定的：
     * 全表只有 {@code UEV}（{@code 1E40D8}，亮度 71）落在阈值以下——
     * 它是唯一的深蓝，浅描边才能勾出轮廓；其余各档亮度都在 142 以上
     * （最接近阈值的是 {@code UXV} 的 142），一律用深描边。
     */
    private static int edgeColorFor(int fill) {
        int r = (fill >> 16) & 0xFF;
        int g = (fill >> 8) & 0xFF;
        int b = fill & 0xFF;
        int luma = (r * 299 + g * 587 + b * 114) / 1000;
        return luma >= 140 ? COLOR_EDGE : COLOR_EDGE_LIGHT;
    }

    /**
     * 超长缩写的绘制：文字在<b>图标框内</b>"上半或下半横向独占的一行"里按配置的方式移动
     * （左右往返摆动，或单向循环滚动）。
     *
     * <p>进入条件见 {@link BadgePlacement#compute}——文字长到即使按最小适配也贴不进角落时，
     * 字号就停在 {@link BadgePlacement#MIN_FIT_SCALE}，位置从角落对齐改为框内独占一行，
     * 然后把完整内容摆出来（缩到看不清不如让它动起来）。
     *
     * <p><b>两种动作方式</b>（由配置项 {@code scroll} 决定，两者都要裁掉窗口外的部分）：
     * <ul>
     *   <li>{@link ConfigLoader.ScrollMode#LOOP}（默认）：<b>单向循环滚动</b>，自窗口右缘滚入、
     *       向左滚出，隔 {@link #SCROLL_GAP} 空档后重来，移动方向恒定但每个字都不停；</li>
     *   <li>{@link ConfigLoader.ScrollMode#SWAY}：<b>左右往返摆动</b>，两端各停
     *       {@link #SCROLL_DWELL} 秒——开头与结尾都看得清，代价是节奏有停顿。</li>
     * </ul>
     * 两条路都只在"已经必须动起来"时才有意义——放得下的角标贴角落、放不太下的静态居中（见下）。
     *
     * <p><b>为什么用"逐字裁剪"而不是 scissor</b>：本方法运行在
     * {@code ItemRenderer.render} 的 TAIL，手上只有已经变换过的 3D 矩阵，拿不到槽位的屏幕坐标；
     * 要用 {@code RenderSystem.enableScissor} 就得把矩阵反推回屏幕像素（GUI 缩放、投影矩阵
     * 都得自己再算一遍）。逐字裁剪只在本地度量里比较数字，不引入任何新的坐标系假设。
     *
     * <p><b>裁剪只用于"动起来"，不用于静态居中</b>：静态居中的语义是"内容基本装得下、
     * 两侧各压出不到半格"，那半格本就落在图标外侧，属于有意为之；若也套上窗口裁剪，
     * 首尾两字会因为"字形中心恰好落在窗口外"而被整块吃掉。滚动则必须裁——那时文字大部分
     * 在窗口外，不裁会压到相邻槽位的物品。
     *
     * <p>裁剪判据取"字形<b>中心</b>落在窗口内"，而不是"字形完整落在窗口内"：后者会让一个
     * 尚有八成正落在窗口里的字被整块抹掉，文字移动时这个突跳很扎眼；前者把显隐挪到
     * 露出/遮住一半的时刻，观感接近自然的滑入滑出。代价是字形最多越出裁剪范围半个字宽——
     * 裁剪范围已收到整块图标，那半格越过槽位间约 2 屏幕像素的空隙后只剩 0.2 ~ 0.6 像素
     * （见 {@link BadgePlacement#CLIP_SLACK}），看不出压边。
     *
     * <p>这里的"窗口"指<b>裁剪范围</b>，比绘制窗口 {@link BadgePlacement#SCROLL_WINDOW} 每侧
     * 宽出 {@link BadgePlacement#CLIP_SLACK}（恰好等于整块图标）；绘制窗口只有 18 字形单位、
     * 滚动字号又被钉在下限，不额外放宽的话一次只看得见约三个字。放宽只作用在裁剪上，
     * 文字移动的起止仍按绘制窗口算。
     *
     * <p><b>坐标系换算</b>：本方法运行在 {@code pose.scale(textScale)} <b>之后的</b>坐标域里，
     * 绘制坐标比"屏幕上的字形单位"小了 {@code textScale} 倍（{@code toDraw = 1/textScale}
     * 把它换回来）。但<b>并非所有量都要换算</b>：
     *
     * <ul>
     *   <li>窗口宽度、移动速度、最小摆幅、空档、描边粗细——本类按"屏幕上多大"定义的量，
     *       换算成绘制单位要 {@code × toDraw}；</li>
     *   <li>{@code font.width(...)}——MC 摆放字形本就是"1 度量单位 = 1 绘制单位"，
     *       它的返回值<b>已是绘制单位</b>，直接用。</li>
     * </ul>
     *
     * <p>两类量混为一谈的后果各不相同：漏乘（第一类）会让窗口只剩应有的 {@code textScale} 倍宽，
     * 文字从窗口内部起步而不是从边缘移入；给第二类<b>多乘</b>一次则会让字距被放大
     * {@code 1/textScale} 倍，每个字之间凭空多出约半个字宽的空隙。
     */
    private static void drawScrolling(Font font, String text, int color, BadgePlacement.Placement place,
                                      Matrix4f matrix, MultiBufferSource buffer, int light, boolean animated) {
        float textScale = place.textScale();
        // 字形单位 -> 绘制单位：本方法拿到的矩阵已经带上了 textScale，故 1 字形单位 = 1/textScale
        float toDraw = 1.0F / textScale;

        // 【换算只有两类量，务必分清】
        //  1. 本类自己定义的长度量（窗口宽度、速度、最小摆幅、描边粗细）：以"屏幕上多大"（字形单位）
        //     表述，而绘制坐标已被 textScale 缩过，所以要 × toDraw 换回绘制单位。
        //  2. 字体度量 font.width(...)：MC 的 drawInBatch 摆放字形时本就是"1 度量单位 = 1 绘制单位"，
        //     所以它返回的<b>已经是绘制单位</b>，直接用，绝不能再乘 toDraw。
        // 第二类多乘一次会让字距被放大 1/textScale 倍（0.667 时每字之间凭空多出约半个字宽的空隙）。
        float window = BadgePlacement.SCROLL_WINDOW * toDraw;
        float textWidth = font.width(text);
        // 需要摆动的距离（仅 sway 模式用）：文字左缘从 0（开头对齐窗口左缘）走到
        // -(textWidth - window)（末尾对齐窗口右缘）。文字本来就装得下时该值为负，取 0。
        float swing = Math.max(0.0F, textWidth - window);
        // 描边偏移要除以字号，才能在屏幕上恒为 1 字形单位
        float edge = toDraw;

        // 溢出不足一个全角字宽 → 静态居中、把内容整个摆在那里，<b>不裁剪</b>。
        // 摆不摆由 BadgePlacement.SCROLL_MIN_SWING 判定（四个字在 font_scale 0.9~1.1
        // 全档位都落在这个区间，静止即可完整看清）。
        // 这里不能套窗口裁剪：居中后首尾两字各有半个字探出窗口，中心落在窗口外就会被整块抹掉。
        if (swing * textScale < BadgePlacement.SCROLL_MIN_SWING) {
            drawGlyphs(font, text, color, (window - textWidth) * 0.5F, edge,
                    Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, matrix, buffer, light, animated);
            return;
        }

        // 真要动起来：动作方式由配置项 scroll 决定，两条路都必须裁掉窗口外的部分
        // （不裁会压到相邻槽位的物品）。速度与空档都是"屏幕上多大"的量，故 × toDraw。
        float speed = SCROLL_SPEED * toDraw;
        float x = switch (ConfigLoader.scrollMode()) {
            case SWAY -> -swing * swingProgress(swing, speed);
            case LOOP -> loopLeftEdge(textWidth, window, SCROLL_GAP * toDraw, speed);
        };
        // 裁剪范围比绘制窗口每侧宽出 CLIP_SLACK（= PAD，即整块图标）：否则一次只看得见约 3 个字。
        // 只在裁剪上放宽——文字移动的起止仍按 SCROLL_WINDOW 算，两端对齐的语义不变。
        float slack = BadgePlacement.CLIP_SLACK * toDraw;
        drawGlyphs(font, text, color, x, edge, -slack, window + slack, matrix, buffer, light, animated);
    }

    /**
     * 单向循环（{@code scroll:loop}）模式下文字左缘的当前位置。
     *
     * <p>一个循环 = 窗口滚入（{@code window}）+ 文字通过（{@code textWidth}）+ 空档（{@code gap}）：
     * 文字左缘自窗口右缘起步（此刻一个字形都看不见），一路左移到自己左缘越过窗口左缘
     * （即完全离开窗口），随后的空档里窗口内所有字形都被裁掉，然后回到起点重来。
     *
     * <p>空档 {@link #SCROLL_GAP} 取 {@code 0}，即<b>无缝循环</b>：末尾刚离开，开头就接上。
     * 感知到的"看不到字"的时间并非 {@code gap / speed}，而是
     * {@code (gap + 一个字宽) / speed}——裁剪判据是"字形中心落在窗口内"，两端各多出半个字宽。
     *
     * <p>相位用"当前毫秒数对周期取模"求，与帧率无关。
     *
     * @param textWidth 文本宽度（绘制单位）
     * @param window    窗口宽度（绘制单位）
     * @param gap       空档宽度（绘制单位）
     * @param speed     移动速度（绘制单位 / 秒），须为正
     * @return 文字左缘的 x（绘制单位）
     */
    private static float loopLeftEdge(float textWidth, float window, float gap, float speed) {
        float cycle = window + textWidth + gap;
        // 周期至少是 window/speed（约 0.8 秒），不会是 0
        long periodMillis = (long) (cycle / speed * 1000.0F);
        float travelled = (Util.getMillis() % periodMillis) / 1000.0F * speed;
        return window - travelled;
    }

    /**
     * 逐字绘制一行角标文字（各字自带描边），按需裁剪。
     *
     * <p>拆出来是因为两条路径的差别只在"裁不裁"：静态居中要完整显示（不裁），
     * 滚动要挡住窗口外的部分（裁）。裁剪窗口传无穷即不裁。
     *
     * @param color     主体文字色（{@code 0xRRGGBB}），描边色由 {@link #edgeColorFor} 按它推出
     * @param x         文字左缘（绘制单位；绘制域 = 已含 {@code textScale} 的那个域）
     * @param edge      描边偏移（绘制单位，= {@code 1/textScale} 才能在屏幕上恒为 1 字形单位）
     * @param clipLeft  裁剪窗口左缘（绘制单位），{@code NEGATIVE_INFINITY} 表示不裁
     * @param clipRight 裁剪窗口右缘（绘制单位），{@code POSITIVE_INFINITY} 表示不裁
     * @param animated  是否为 {@code OpV} / {@code MAX} 的动态色相蒙版模式：{@code true} 时只画一遍主体
     *                  （描边那层会在偏移处再画一次，两层色相叠加会糊掉，见
     *                  {@link #drawBadgeText} 里的说明）
     */
    private static void drawGlyphs(Font font, String text, int color, float x, float edge,
                                   float clipLeft, float clipRight,
                                   Matrix4f matrix, MultiBufferSource buffer, int light, boolean animated) {
        // 描边色只跟主体色有关，逐字算一遍是浪费——提到循环外
        int edgeColor = edgeColorFor(color);
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String glyph = new String(Character.toChars(codePoint));
            i += Character.charCount(codePoint);
            // 字体度量已是绘制单位，不要再乘 toDraw（见 drawScrolling 里的分类说明）
            float advance = font.width(glyph);
            // 字形中心落在窗口内才画；不裁时两端是无穷，判据恒真
            float center = x + advance * 0.5F;
            if (center >= clipLeft && center <= clipRight) {
                if (!animated) {
                    font.drawInBatch(glyph, x + edge, edge, edgeColor, false, matrix, buffer, Font.DisplayMode.NORMAL, 0, light);
                }
                font.drawInBatch(glyph, x, 0.0F, color, false, matrix, buffer, Font.DisplayMode.NORMAL, 0, light);
            }
            x += advance;
        }
    }

    /**
     * 往返摆动（{@code scroll:sway}）的相位：{@code 0 → 1 → 0} 的三角波，
     * 两端各停留 {@link #SCROLL_DWELL} 秒。
     *
     * <p>用"当前毫秒数对周期取模"求相位，与帧率无关。
     *
     * @param swing 单程摆动的距离（绘制单位），须为正
     * @param speed 移动速度（绘制单位 / 秒）
     * @return {@code 0} 表示开头对齐窗口左缘，{@code 1} 表示末尾对齐窗口右缘
     */
    private static float swingProgress(float swing, float speed) {
        float travel = swing / speed;                       // 单程秒数
        float cycle = 2.0F * (travel + SCROLL_DWELL);       // 一个来回 + 两端停留
        float t = (Util.getMillis() % (long) (cycle * 1000.0F)) / 1000.0F;
        if (t < SCROLL_DWELL) {
            return 0.0F;                                    // 停在开头
        }
        if (t < SCROLL_DWELL + travel) {
            return (t - SCROLL_DWELL) / travel;             // 向左摆
        }
        if (t < travel + SCROLL_DWELL * 2.0F) {
            return 1.0F;                                    // 停在结尾
        }
        return 1.0F - (t - travel - SCROLL_DWELL * 2.0F) / travel;   // 向右摆回
    }
}
