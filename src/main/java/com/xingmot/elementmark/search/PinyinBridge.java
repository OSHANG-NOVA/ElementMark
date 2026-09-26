package com.xingmot.elementmark.search;

import java.lang.reflect.Method;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.fml.ModList;

/**
 * JustEnoughCharacters（拼音搜索）的<b>反射桥</b>。
 *
 * <p><b>为什么用反射而不是编译期依赖。</b>JEC 是<b>可选</b>的客户端模组，整合包里不一定装。
 * 若直接 {@code import me.towdium.jecharacters.utils.Match}，没装 JEC 的整合包会在
 * 类解析阶段抛 {@code NoClassDefFoundError}，把整个模组列表界面打崩。反射则让"装了就用、
 * 没装就静默退化成原文匹配"，两种情形都不需要用户配置。
 *
 * <p><b>参数顺序是本类唯一容易写错的地方，已用实机字节码 + 运行时验证钉死。</b>
 * JEC 的入口是 {@code Match.contains(String, CharSequence)}，它把参数原样转发给
 * {@code PinIn.contains}；而对 PinIn 的实测结果是：
 *
 * <pre>
 * contains("钢铁", "gt") = true      ← 第一个参数是被搜索的文本
 * contains("gt", "钢铁") = false     ← 反过来就永远搜不到
 * </pre>
 *
 * 即签名是 <b>{@code contains(文本, 查询串)}</b>。因此本类调用时传 {@code (text, query)}。
 * （公开资料里对此有相反的说法，以实测为准。）
 *
 * <p><b>PinIn 的边界</b>：它只做拼音层面的匹配，对纯 ASCII 文本<b>不做</b>子序列匹配——
 * 实测 {@code contains("GregTech", "gt")} 返回 {@code false}。所以"忽略字母位置"这件事
 * 不能指望 JEC，必须由 {@link ModSearchMatcher} 自己实现。
 */
public final class PinyinBridge {

    private static final String JEC_MOD_ID = "jecharacters";
    private static final String MATCH_CLASS = "me.towdium.jecharacters.utils.Match";

    /** 解析只做一次；用 volatile + 双检锁，避免每帧重复反射 */
    private static volatile boolean resolved;
    private static volatile Method containsMethod;

    /**
     * 日志器<b>惰性</b>获取，且失败时返回 {@code null}。
     *
     * <p>不写成 {@code private static final Logger LOGGER = LogUtils.getLogger()}：那是一条
     * <b>静态初始化</b>语句，一旦它抛异常（{@code ExceptionInInitializerError}），此后每次触碰
     * 本类都会立刻抛 {@code NoClassDefFoundError}——而本类是被"模组列表搜索"逐帧调用的，
     * 结果就是<b>整个搜索功能连带界面一起崩</b>。本类只是拼音的<b>可选</b>桥，绝不能有这种权力。
     *
     * <p>（这不是假想：本类的离线单元测试正是在缺 {@code com.mojang.logging} 的环境里跑，
     * 静态字段那条语句直接让搜索判定抛异常。游戏里 {@code LogUtils} 必然存在，但"可选桥"
     * 就该按最坏情况防御——代价只是几行代码。）
     */
    private static Logger logger() {
        try {
            return LogUtils.getLogger();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 记日志；日志设施不可用时静默跳过，绝不影响搜索 */
    private static void log(boolean warn, String message, Object... args) {
        try {
            Logger logger = logger();
            if (logger == null) {
                return;
            }
            if (warn) {
                logger.warn(message, args);
            } else {
                logger.info(message, args);
            }
        } catch (Throwable ignored) {
            // 日志永远不该影响功能
        }
    }

    private PinyinBridge() {}

    /** 拼音匹配是否可用（仅用于日志与排查） */
    public static boolean available() {
        return resolve() != null;
    }

    /**
     * 用拼音规则判断 {@code text} 是否匹配 {@code query}。
     *
     * @param text  被搜索的文本（模组显示名原文，中文要原样保留）
     * @param query 用户输入的查询串
     * @return 命中返回 {@code true}；JEC 未安装、解析失败或调用出错一律返回 {@code false}
     *         （即"退化成没有拼音"），绝不向上抛异常
     */
    public static boolean contains(String text, String query) {
        Method method = resolve();
        if (method == null) {
            return false;
        }
        try {
            // 顺序是 (文本, 查询串)，见类注释里的实测记录
            Object result = method.invoke(null, text, query);
            return result instanceof Boolean hit && hit;
        } catch (Throwable t) {
            // 反射调用失败只应导致"这次没匹配上"，不应影响整轮搜索
            return false;
        }
    }

    /** 惰性解析一次 JEC 入口方法；失败结果也会被缓存，避免反复尝试 */
    private static Method resolve() {
        if (!resolved) {
            synchronized (PinyinBridge.class) {
                if (!resolved) {
                    containsMethod = find();
                    resolved = true;
                }
            }
        }
        return containsMethod;
    }

    /**
     * 探测并取出 JEC 的入口方法。
     *
     * <p>先查模组 ID 再取类：{@code ModList} 查询是零类加载的安全操作，
     * 没装 JEC 时连 {@code Class.forName} 都不会执行。
     *
     * <p>取的是精确重载 {@code (String, CharSequence)}，而不是宽松的
     * {@code getMethod("contains", ...)}——JEC 另有 {@code (CharSequence, CharSequence)}
     * 重载，写错会取到非预期的那一个。
     */
    private static Method find() {
        try {
            ModList modList = ModList.get();
            if (modList == null || !modList.isLoaded(JEC_MOD_ID)) {
                log(false, "[ElementMark] 未检测到 JustEnoughCharacters，模组搜索将只做原文匹配（无拼音）");
                return null;
            }
            Class<?> matchClass = Class.forName(MATCH_CLASS);
            Method method = matchClass.getMethod("contains", String.class, CharSequence.class);
            log(false, "[ElementMark] 已接入 JustEnoughCharacters，模组搜索支持拼音");
            return method;
        } catch (Throwable t) {
            log(true, "[ElementMark] JustEnoughCharacters 接入失败，模组搜索退化为原文匹配：{}", t.toString());
            return null;
        }
    }
}
