package com.xingmot.elementmark.circuit;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;

import org.joml.Matrix4f;

/**
 * {@code OpV} / {@code MAX} 等级"文字当蒙版、蒙版下是流动彩图"的实现。
 *
 * <p><b>为什么不需要写 shader。</b>原版字体纹理是<b>灰度（intensity）纹理</b>，
 * {@code rendertype_text_intensity.fsh} 的算法是
 * {@code color = texture(...).rrrr * vertexColor}——也就是
 * <b>最终颜色 = 字形覆盖率 × 顶点色</b>。这正是"蒙版"的数学形式：
 * 字形覆盖率是蒙版，顶点色是蒙版下面透出来的颜色。
 * 于是只要让同一批顶点拿到不同的颜色，就能得到"彩色图片透过文字显形"的效果，
 * 不需要自定义着色器、不需要额外贴图。
 *
 * <p><b>为什么包装 {@code VertexConsumer} 而不是改字形。</b>{@code BakedGlyph.render}
 * 对每个顶点固定走 {@code vertex(...) → color(FFFF) → uv(...) → uv2(...) → endVertex()}，
 * 其中 {@code color} 是接口方法。包一层即可在"颜色即将写进缓冲"的那一刻换成彩虹色，
 * 而字形几何、UV、光照全部原样透传。这也是唯一能<b>逐顶点</b>着色的位置——
 * 若在 {@code drawInBatch} 层面传色，整段文字只能是一个颜色。
 *
 * <p><b>相位来自 x 与 y</b>：色相取 {@code (x + y*0.5) / wavelength + phase}，
 * 于是彩虹沿对角线流动，而不是一条竖直色带平移——后者在只有两三个字的角标上
 * 几乎看不出"在动"。{@code phase} 由绘制方按 {@code Util.getMillis()} 算出，
 * 与帧率无关。
 *
 * <p><b>色相区间与"折返"</b>：本类不写死"整圈彩虹"，而是接受一个区间
 * {@code [hueStart, hueEnd]}（由 {@code VoltageTier.hueStartOf/hueEndOf} 给出）。
 * <ul>
 *   <li>区间覆盖整圈（{@code end - start >= 1}，即 {@code MAX}）：色相位置直接
 *       取模循环。整圈是一个<b>闭合的圆</b>，{@code 1.0} 跳回 {@code 0.0} 时
 *       两端本来就是同一种红，看不出接缝。</li>
 *   <li>区间只是一个弧段（如 {@code OpV} 的 {@code [0.75, 1.0]}）：必须把位置
 *       <b>折成三角波</b>（{@code 0→1→0}）再压进区间。否则从 {@code end} 直接跳回
 *       {@code start} 就是"红直接跳紫"，会在流动时周期性闪出一条明显接缝。</li>
 * </ul>
 * 折返的代价是颜色在区间两端各有一个"停顿点"（导数反向），但在只有两三个字的
 * 角标上完全看不出来，而接缝是一眼可见的。
 *
 * <p>本类只做"换色"，不做任何裁剪、变换或状态管理；{@code endVertex()} 之前
 * 一个顶点都不会被改写，故与原版批处理完全兼容。
 */
public final class RainbowVertexConsumer implements VertexConsumer {

    /** 一个完整色相周期跨越的字形单位数。取 18 ≈ 图标内可用宽度，于是整块角标正好走完一轮彩虹 */
    private static final float WAVELENGTH = 18.0F;

    private final VertexConsumer delegate;
    /** 色相偏移（0~1），由时间决定；每帧算一次，同一次绘制内所有顶点共用 */
    private final float phase;
    /** 动态色相区间起点（0~1） */
    private final float hueStart;
    /** 动态色相区间终点（0~1） */
    private final float hueEnd;
    /**
     * 区间是否覆盖整圈（{@code hueEnd - hueStart >= 1}）。
     *
     * <p>构造时算一次而不是每顶点算：{@link #applyRainbow} 对每个顶点都要走一遍，
     * 而区间在同一次绘制里是常量。
     */
    private final boolean fullCircle;

    /**
     * 当前顶点的局部坐标。
     *
     * <p>必须在 {@code color(...)} 之前由 {@code vertex(...)} 记下：{@code BakedGlyph.render}
     * 的调用顺序是 vertex → color → uv → uv2 → endVertex，颜色阶段拿不到坐标，
     * 只能靠这里暂存。
     */
    private float x;
    private float y;

    private RainbowVertexConsumer(VertexConsumer delegate, float phase, float hueStart, float hueEnd) {
        this.delegate = delegate;
        this.phase = phase;
        this.hueStart = hueStart;
        this.hueEnd = hueEnd;
        this.fullCircle = (hueEnd - hueStart) >= 1.0F;
    }

    /**
     * 把一个 {@code MultiBufferSource} 包成"所有顶点都染动态色相"的版本。
     *
     * <p>包装发生在 {@code getBuffer} 层，是因为 {@code Font.drawInBatch} 只接受
     * {@code MultiBufferSource} 而不接受裸 {@code VertexConsumer}；这样调用方可以
     * 完全按原样调用 {@code drawInBatch}，一个字都不用改。
     *
     * @param delegate 被包装的数据源（通常是 {@code GuiGraphics.bufferSource()}）
     * @param phase    色相偏移（0~1）
     * @param hueStart 色相区间起点（0~1）
     * @param hueEnd   色相区间终点（0~1）
     */
    public static MultiBufferSource wrap(MultiBufferSource delegate, float phase, float hueStart, float hueEnd) {
        return new RainbowSource(delegate, phase, hueStart, hueEnd);
    }

    /** 整圈彩虹（{@code MAX}）的便捷写法：区间 {@code [0, 1]} */
    public static MultiBufferSource wrap(MultiBufferSource delegate, float phase) {
        return wrap(delegate, phase, 0.0F, 1.0F);
    }

    /** 按时间算出色相偏移；{@code cycleSeconds} 是走完一整轮色相所需的秒数 */
    public static float phaseFor(float cycleSeconds) {
        float cycle = cycleSeconds <= 0.0F ? 4.0F : cycleSeconds;
        float seconds = (System.currentTimeMillis() % 0x7FFFFFFFL) / 1000.0F;
        float p = (seconds / cycle) % 1.0F;
        return p < 0.0F ? p + 1.0F : p;
    }

    /**
     * 色相位置 -&gt; 实际色相。
     *
     * <p>拆出来是为了让"整圈取模"与"弧段折返"两条分支各自可读，
     * 也方便单独验证折返数学（三角波在 {@code t = 0.5} 处折回）。
     *
     * @param t 色相位置（已归一化到 {@code [0, 1)}）
     */
    private float hueAt(float t) {
        if (fullCircle) {
            // 整圈：t 本身就是一个完整周期的位置，加起点即可无缝循环
            return hueStart + t;
        }
        // 弧段：折成三角波 0→1→0，再压进 [start, end]。
        // 不能直接 hueStart + t*(end-start)——那在 t 从 1 回到 0 时会从 end 硬跳回 start。
        float tri = t <= 0.5F ? t * 2.0F : (1.0F - t) * 2.0F;
        return hueStart + tri * (hueEnd - hueStart);
    }

    /** 色相 -> 该顶点应写入的颜色 */
    private void applyRainbow(float alpha) {
        float u = (x + y * 0.5F) / WAVELENGTH + phase;
        float t = u % 1.0F;
        if (t < 0.0F) {
            t += 1.0F;
        }
        float hue = hueAt(t) % 1.0F;
        if (hue < 0.0F) {
            hue += 1.0F;
        }
        // 饱和度略低于 1、明度满值：纯色相在暗色物品贴图上会显得脏
        int rgb = Mth.hsvToRgb(hue, 0.85F, 1.0F);
        delegate.color(
                ((rgb >> 16) & 0xFF) / 255.0F,
                ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F,
                alpha);
    }

    // ---------------------------------------------------------------- 顶点

    @Override
    public VertexConsumer vertex(Matrix4f matrix, float x, float y, float z) {
        // 记下局部坐标：颜色阶段要用它算色相（此处 x/y 就是字形坐标系里的位置）
        this.x = x;
        this.y = y;
        delegate.vertex(matrix, x, y, z);
        return this;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        this.x = (float) x;
        this.y = (float) y;
        delegate.vertex(x, y, z);
        return this;
    }

    // ---------------------------------------------------------------- 颜色

    @Override
    public VertexConsumer color(float red, float green, float blue, float alpha) {
        // 丢弃原色（深灰描边 / 白色主体），换成彩虹；alpha 原样保留
        applyRainbow(alpha);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        applyRainbow(alpha / 255.0F);
        return this;
    }

    @Override
    public void defaultColor(int red, int green, int blue, int alpha) {
        // 不设默认色：默认色会在顶点未显式上色时补写，那会绕过上面的彩虹逻辑
    }

    @Override
    public void unsetDefaultColor() {
        delegate.unsetDefaultColor();
    }

    // ---------------------------------------------------------------- 透传

    @Override
    public VertexConsumer uv(float u, float v) {
        delegate.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        delegate.overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        delegate.uv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        delegate.normal(x, y, z);
        return this;
    }

    @Override
    public void endVertex() {
        delegate.endVertex();
    }

    /** {@link MultiBufferSource} 的包装层：把每个 {@code VertexConsumer} 换成动态色相版 */
    private record RainbowSource(MultiBufferSource delegate, float phase, float hueStart, float hueEnd)
            implements MultiBufferSource {

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return new RainbowVertexConsumer(delegate.getBuffer(type), phase, hueStart, hueEnd);
        }
    }
}
