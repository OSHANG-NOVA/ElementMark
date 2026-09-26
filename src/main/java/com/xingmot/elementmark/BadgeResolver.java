package com.xingmot.elementmark;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.xingmot.elementmark.compat.GtCompat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 物品 -&gt; 缩写解析器。
 *
 * <p><b>查表优先级</b>（先命中先用）：
 * <ol>
 *   <li>{@code config/elementmark.txt} 的用户配置——用户显式写的键一律以用户为准，
 *       包括"值留空即隐藏"；</li>
 *   <li>{@link BuiltinNames 内置中文名表}——就是仓库根目录 {@code elementmark.txt}
 *       打进 jar 的那一份（118 元素 + 654 条 GT 材料，共 700+ 条）；</li>
 *   <li>内置 118 元素<b>符号</b>表（{@code H} / {@code He} / …），最后兜底。</li>
 * </ol>
 *
 * <p><b>解析途径</b>（按序，先命中先用）：
 * <ol>
 *   <li><b>物品 tag</b>：只认 c: / forge: 命名空间、路径形如 "&lt;form&gt;/&lt;material&gt;"
 *       的 tag（如 c:plates/zinc），取 "/" 后段作为材料名；</li>
 *   <li><b>GT 材料体系</b>（装了格雷科技时）：{@code ChemicalHelper.getMaterialEntry}
 *       能认出物品属于哪个材料，于是拿到<b>材料注册名</b>再走一次查表；查表未命中时，
 *       直接取 GT 语言文件里的<b>本地化名称</b>（中文环境即"铁""青铜"），
 *       最后才退回元素符号。这一路正是"新注册的元素/材料也能自动显示中文名"的来源——
 *       它覆盖 GTM 本体、所有 {@code IGTAddon} 附属模组、以及 KubeJS 脚本注册的内容，
 *       不依赖物品有没有 {@code c:/forge:} tag；</li>
 *   <li><b>流体容器</b>：桶这类物品不带材料类 tag，改用桶内流体的注册 ID path
 *       （如 gtceu:soldering_alloy_bucket 装的流体 gtceu:soldering_alloy → "soldering_alloy"）。</li>
 * </ol>
 * 材料名查表时除原名外还尝试<b>形态变体</b>（见 {@link #lookupWithVariants}）：
 * 粗矿块的 tag 段是 raw_x、熔融/液化流体是 molten_x / liquid_x、等离子体是 x_plasma，
 * 剥掉形态标记后仍是同一个材料。
 *
 * <p>合金、虚构材料等全部途径都无命中 -&gt; 缓存空串，不显示角标。
 *
 * <p>tag 集合在 datapack 加载后不再变化；但用户配置可变，
 * 故 {@link #invalidate()} 供配置重载时清空缓存。
 */
public final class BadgeResolver {

    /** 118 元素 + 三组英式/美式拼写别名（aluminium/aluminum、sulfur/sulphur、caesium/cesium） */
    private static final String RAW = """
            hydrogen:H,helium:He,lithium:Li,beryllium:Be,boron:B,carbon:C,nitrogen:N,oxygen:O,fluorine:F,neon:Ne,
            sodium:Na,magnesium:Mg,aluminium:Al,aluminum:Al,silicon:Si,phosphorus:P,sulfur:S,sulphur:S,chlorine:Cl,argon:Ar,
            potassium:K,calcium:Ca,scandium:Sc,titanium:Ti,vanadium:V,chromium:Cr,manganese:Mn,iron:Fe,cobalt:Co,nickel:Ni,
            copper:Cu,zinc:Zn,gallium:Ga,germanium:Ge,arsenic:As,selenium:Se,bromine:Br,krypton:Kr,rubidium:Rb,strontium:Sr,
            yttrium:Y,zirconium:Zr,niobium:Nb,molybdenum:Mo,technetium:Tc,ruthenium:Ru,rhodium:Rh,palladium:Pd,silver:Ag,cadmium:Cd,
            indium:In,tin:Sn,antimony:Sb,tellurium:Te,iodine:I,xenon:Xe,caesium:Cs,cesium:Cs,barium:Ba,lanthanum:La,
            cerium:Ce,praseodymium:Pr,neodymium:Nd,promethium:Pm,samarium:Sm,europium:Eu,gadolinium:Gd,terbium:Tb,dysprosium:Dy,holmium:Ho,
            erbium:Er,thulium:Tm,ytterbium:Yb,lutetium:Lu,hafnium:Hf,tantalum:Ta,tungsten:W,rhenium:Re,osmium:Os,iridium:Ir,
            platinum:Pt,gold:Au,mercury:Hg,thallium:Tl,lead:Pb,bismuth:Bi,polonium:Po,astatine:At,radon:Rn,francium:Fr,
            radium:Ra,actinium:Ac,thorium:Th,protactinium:Pa,uranium:U,neptunium:Np,plutonium:Pu,americium:Am,curium:Cm,berkelium:Bk,
            californium:Cf,einsteinium:Es,fermium:Fm,mendelevium:Md,nobelium:No,lawrencium:Lr,rutherfordium:Rf,dubnium:Db,seaborgium:Sg,bohrium:Bh,
            hassium:Hs,meitnerium:Mt,darmstadtium:Ds,roentgenium:Rg,copernicium:Cn,nihonium:Nh,flerovium:Fl,moscovium:Mc,livermorium:Lv,tennessine:Ts,
            oganesson:Og
            """;

    private static final Map<String, String> ELEMENTS = new HashMap<>();
    /** value 为空串表示"已知无符号"；ConcurrentHashMap 不允许 null value，用空串避免重复解析 */
    private static final Map<Item, String> CACHE = new ConcurrentHashMap<>();
    /** 流体注册 ID 的解析缓存，键为 Fluid 实例（JEI 流体角标与桶共用） */
    private static final Map<Fluid, String> FLUID_CACHE = new ConcurrentHashMap<>();

    /**
     * 材料名里可剥掉的<b>形态前缀</b>：粗矿块的 tag 段（{@code raw_lead}）、
     * 熔融与液化流体的注册 ID（{@code molten_steel} / {@code liquid_oxygen}）。
     * 只在原名未命中时才尝试剥——{@code black_bronze}、{@code red_steel} 这类
     * "前缀是材料名一部分"的真材料必须按全名命中，不能被误剥。
     */
    private static final String[] STRIPPABLE_PREFIXES = {"raw_", "molten_", "liquid_"};

    /** GT 等离子体流体的形态后缀（{@code americium_plasma}），本身不是材料 */
    private static final String SUFFIX_PLASMA = "_plasma";

    /**
     * 格雷科技是否存在。<b>惰性求值一次</b>并缓存：{@code ModList} 在渲染期必然已就绪，
     * 但本类可能在更早的时机被触碰，故不放进静态初始化块，避免拿到未就绪的模组列表。
     *
     * <p>只查模组 ID、<b>不查类</b>——查 ID 是零类加载的安全操作，即便 GT 没装也绝不会
     * 因为这次探测而把 {@code com.gregtechceu.*} 拉进 JVM。
     */
    private static volatile Boolean gtPresent;

    /**
     * GT 材料表是否<b>已经可以查询</b>。只朝"真"这一个方向翻转：一旦就绪便永远是就绪。
     *
     * <p>与 {@link #gtPresent}（装没装）是两件事——装了不等于注册已收口。分开存是为了让
     * "未就绪"这一状态<b>不被固化</b>：{@link #gtSettled()} 每次都重新探测，直到真的就绪。
     */
    private static volatile boolean gtReady;

    static {
        for (String pair : RAW.split(",")) {
            String[] kv = pair.trim().split(":");
            if (kv.length == 2) {
                ELEMENTS.put(kv[0], kv[1]);
            }
        }
    }

    private BadgeResolver() {}

    /**
     * @return 缩写；所有途径都无命中时返回 null
     *
     * <p><b>为什么这里不直接用 {@code computeIfAbsent}</b>：GT 的材料表要在<b>全部材料注册
     * 收口之后</b>才可查询。若在收口前就渲染到某个物品，{@code ChemicalHelper} 会抛
     * {@link IllegalStateException}，几条 GT 途径于是全部落空——但那只表示"还没就绪"，
     * 不表示"这个物品没有材料"。若把这次落空当否定结论缓存下来，该物品此后<b>永远</b>
     * 不再显示角标（且用户重载配置也救不回来，因为清缓存后仍会撞上同样的时序）。
     * 故 GT 已装载但尚未就绪时，本次返回 null 且<b>不写缓存</b>，下一帧自然重算。
     */
    public static String resolve(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        String cached = CACHE.get(item);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String symbol = compute(item);
        if (symbol.isEmpty() && !gtSettled()) {
            return null;
        }
        CACHE.put(item, symbol);
        return symbol.isEmpty() ? null : symbol;
    }

    /** 清空按物品的解析缓存。配置内容变化后必须调用，否则旧结果不会刷新 */
    public static void invalidate() {
        CACHE.clear();
        FLUID_CACHE.clear();
    }

    /**
     * 按流体注册 ID 解析缩写（配置里同一批材料键）。
     * 供两条途径共用：桶（桶内流体）与 JEI 的流体条目。
     *
     * @return 缩写；未命中返回 null
     */
    public static String resolveFluid(Fluid fluid) {
        if (fluid == null || fluid.isSame(Fluids.EMPTY)) {
            return null;
        }
        String cached = FLUID_CACHE.get(fluid);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String symbol = computeFluid(fluid);
        // 与 resolve(ItemStack) 同理：GT 未就绪时的落空不能固化成否定结论
        if (symbol.isEmpty() && !gtSettled()) {
            return null;
        }
        FLUID_CACHE.put(fluid, symbol);
        return symbol.isEmpty() ? null : symbol;
    }

    /** 单股流体的解析：注册 ID path 查表 → GT 材料名查表 → GT 本地化名 */
    private static String computeFluid(Fluid fluid) {
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
        if (id != null) {
            String hit = lookupWithVariants(id.getPath());
            if (hit != null) {
                return hit;
            }
        }
        if (isGtPresent()) {
            String materialName = GtCompat.materialNameOf(fluid);
            if (materialName != null) {
                String hit = lookupWithVariants(materialName);
                if (hit != null) {
                    return hit;
                }
            }
            String localized = GtCompat.localizedNameOf(fluid);
            if (localized != null) {
                return localized;
            }
        }
        return "";
    }

    // 1.20.1 中 builtInRegistryHolder() 即 Item -> Holder 的唯一途径（1.21 才换成 getItemHolder()），
    // 弃用标记无法规避，局部压制；1.20.1 起 Holder.tags() 返回 Stream<TagKey<T>>，烧成一条流式管道
    @SuppressWarnings("deprecation")
    private static String compute(Item item) {
        // 途径一：材料类 item tag，取 <form>/<material> 的尾段
        String byTag = item.builtInRegistryHolder().tags()
                .map(TagKey::location)
                .filter(tag -> tag.getNamespace().equals("c") || tag.getNamespace().equals("forge"))
                .map(ResourceLocation::getPath)
                // 只认 <form>/<material> 形态，跳过 c:ingots 这类集合 tag
                .filter(path -> path.indexOf('/') >= 0)
                .map(path -> path.substring(path.lastIndexOf('/') + 1))
                .map(BadgeResolver::lookupWithVariants)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (byTag != null) {
            return byTag;
        }

        // 途径二：GT 材料体系。先拿材料注册名再查一次表（让配置/内置表能覆盖 GT 物品），
        // 未命中才直接采用 GT 语言文件里的本地化名称——这是"新注册材料自动出中文名"的主路径。
        if (isGtPresent()) {
            String materialName = GtCompat.materialNameOf(item);
            if (materialName != null) {
                String hit = lookupWithVariants(materialName);
                if (hit != null) {
                    return hit;
                }
            }
            String localized = GtCompat.localizedNameOf(item);
            if (localized != null) {
                return localized;
            }
            // 材料有元素归属、但语言文件里没有它的名字：显示元素符号总好过什么都不显示
            String elementSymbol = GtCompat.elementSymbolOf(item);
            if (elementSymbol != null) {
                return elementSymbol;
            }
        }

        // 途径三：流体容器。桶没有材料类 tag，用桶内流体的注册 ID path 反查；
        // 流体 ID 取的是 still 版（桶里装的不会是 flowing 版）
        if (item instanceof BucketItem bucket) {
            String hit = resolveFluid(bucket.getFluid());
            if (hit != null) {
                return hit;
            }
        }
        return "";
    }

    /**
     * 格雷科技是否已装载（结果缓存一次）。
     *
     * <p>探测只读模组列表，不触碰 GT 的类，因此没装 GT 的整合包在整条路径上
     * 都不会加载 {@link GtCompat}，也就不会抛 {@code NoClassDefFoundError}。
     */
    private static boolean isGtPresent() {
        Boolean cached = gtPresent;
        if (cached == null) {
            boolean present;
            try {
                ModList modList = ModList.get();
                present = modList != null && modList.isLoaded("gtceu");
            } catch (Throwable t) {
                present = false;
            }
            gtPresent = cached = present;
        }
        return cached;
    }

    /**
     * GT 的材料体系是否<b>已经可以查询</b>（未装 GT 时恒为真）。
     *
     * <p>存在的理由是 {@link #resolve} 里那条"落空不缓存"的规则：GT 的材料注册表要在
     * 全部材料收口之后才能查，收口前查询会抛异常，几条 GT 途径于是全部落空。
     * 那时必须<b>区分</b>两种落空：
     * <ul>
     *   <li>GT 还没就绪 → 本次落空<b>不算数</b>，不写缓存，下一帧重算；</li>
     *   <li>GT 已就绪但仍无命中 → 这是真的"这个物品没有材料"，缓存空串，不再重复计算。</li>
     * </ul>
     *
     * <p>一旦就绪便<b>永远</b>就绪，故只缓存"真"这一个方向；未就绪时每次都重新探测
     * （探测本身是读一个已 freeze 的注册表，开销可忽略）。
     */
    private static boolean gtSettled() {
        if (!isGtPresent()) {
            return true;
        }
        if (gtReady) {
            return true;
        }
        boolean ready = GtCompat.probe();
        if (ready) {
            gtReady = true;
        }
        return ready;
    }

    /**
     * 按候选序列查第一个命中：原名 → 剥 {@link #STRIPPABLE_PREFIXES} 前缀 →
     * 剥 {@link #SUFFIX_PLASMA} 后缀。全名优先，见前缀常量上的说明。
     *
     * @return 缩写；所有候选都未命中返回 null
     */
    private static String lookupWithVariants(String name) {
        String hit = lookupName(name);
        if (hit != null) {
            return hit;
        }
        for (String prefix : STRIPPABLE_PREFIXES) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                hit = lookupName(name.substring(prefix.length()));
                if (hit != null) {
                    return hit;
                }
            }
        }
        if (name.endsWith(SUFFIX_PLASMA) && name.length() > SUFFIX_PLASMA.length()) {
            return lookupName(name.substring(0, name.length() - SUFFIX_PLASMA.length()));
        }
        return null;
    }

    /**
     * 单个名字的查表：<b>用户配置 → 内置中文名表 → 内置元素符号表</b>。
     *
     * <p>三者的先后顺序就是"谁说了算"的顺序：配置里写了这个键（哪怕是留空表示隐藏），
     * 就以配置为准；否则查内置中文名表；再否则退回元素符号。
     *
     * <p>返回空串是<b>合法结果</b>，表示"显式隐藏该材料的角标"，调用方需与"未命中"
     * （返回 {@code null}）区分开。
     */
    private static String lookupName(String name) {
        String fromConfig = ConfigLoader.lookup(name);
        if (fromConfig != null) {
            return fromConfig;
        }
        String fromBuiltin = BuiltinNames.get(name);
        if (fromBuiltin != null) {
            return fromBuiltin;
        }
        return ELEMENTS.get(name);
    }
}
