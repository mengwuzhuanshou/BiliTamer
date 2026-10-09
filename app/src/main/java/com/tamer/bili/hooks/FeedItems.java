package com.tamer.bili.hooks;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Pegasus 首页推荐流的共用反射定位工具（v1.8.0 抽出）。
 *
 * 为什么要抽出来：首页相关的功能都需要同两个接缝——
 *  ① 协议解析出口（{@code GeneralResponse} 返回口）：6.6.0 是
 *     {@code com.bilibili.pegasus.request.h.a(okhttp3.E)}，6.5.0 是同形状的
 *     {@code request.g}；单字母类名每版重排，且旧名会被 R8 复用给无关类
 *     （PITFALLS #16），所以必须按「形状」逐个试候选，而不是认类名。
 *  ② 卡片列表：{@code GeneralResponse.data}（字段类型就是 Object，无静态信息，只能按
 *     形状找）里那个「元素带 getCardType() 的 List」。在此原地移除/改写即覆盖
 *     刷新+加载更多+预载提交三条路径，下游 Store 与渲染同源一致。
 *     6.6.0 实证（dex + 现场 UI 对撞）：这一代首页卡的活模型是 gson 直解的混淆类，
 *     而且**每种 {@code card_type} 用的是不同的数据类**（现场探针：{@code XD0.u}=
 *     small_cover_v2/av、{@code XD0.w}=small_cover_v9/live、{@code YD0.c}=banner_v8；
 *     {@code Lra/c;} 是同代另一种卡）。它们 {@code @SerializedName} 齐全
 *     （{@code card_type/card_goto/desc/up/desc_button/left_bottom_rcmd_reason_style/}
 *     {@code rcmd_reason_style/cover_left_text_1} 等），并带 {@code getCardType()/}
 *     {@code getCardGoto()/getDescButton()} 真 getter，
 *     {@code LXD0/a;} 那族 holder 只是它的渲染层包装。字段名被 R8 改成单字母
 *     （{@code Y=y 行的 desc}、{@code Z=desc_button}、{@code a0=up}、{@code j=card_goto}），
 *     所以**读**走「getter → 同名字段 → 协议名注解」三路，**写**只有协议名注解那一路
 *     能落地（见 {@link #fieldByJsonName}）。6.4.0 那代直接读 {@code BasicIndexItem}
 *     的 public 字段的写法在这一代必然拿不到值，故本类统一提供 {@link #readProp}（读）
 *     和 {@link #writeJson}（按协议名写）。
 *
 * 与 {@link FeedTagHooks} 的关系：那边的同类逻辑（findResponseData/findItemsList）
 * 是产线已验证件，本次抽出只供新功能使用，**没有**回改 FeedTagHooks——避免把已实机
 * 验证过的功能卷进一次纯重构（要合并留到下一次统一验证时做）。
 */
public final class FeedItems {

    /** 解析出口：所属类 + 方法。 */
    public static final class ParseEntry {
        public final Class<?> owner;
        public final Method method;

        ParseEntry(Class<?> owner, Method method) {
            this.owner = owner;
            this.method = method;
        }
    }

    /** 候选解析器类名（按版本新→旧）；形状不符就换下一个。 */
    private static final String[] PARSER_CANDIDATES = {
            "com.bilibili.pegasus.request.h",
            "com.bilibili.pegasus.request.g",
            "com.bilibili.pegasus.request.PegasusGsonParser",
    };

    private FeedItems() {}

    /**
     * 按形状找解析出口：单参数且参数类型在 {@code okhttp3.*}、返回类型名含
     * {@code GeneralResponse}。找不到返回 null（调用方自行放弃安装）。
     */
    public static ParseEntry findParseEntry(HookApi api, ClassLoader cl) {
        StringBuilder seen = new StringBuilder();
        for (String cn : PARSER_CANDIDATES) {
            Class<?> c;
            try {
                c = api.load(cl, cn);
            } catch (Throwable t) {
                api.debug("feeditems: " + cn + " not loadable: " + t);
                continue;
            }
            for (Method mm : c.getDeclaredMethods()) {
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length != 1 || !ps[0].getName().startsWith("okhttp3.")) {
                    continue;
                }
                if (!mm.getReturnType().getName().contains("GeneralResponse")) {
                    continue;
                }
                seen.append(cn).append('.').append(mm.getName()).append('(')
                        .append(ps[0].getName()).append("), ");
                return new ParseEntry(c, mm);
            }
            api.debug("feeditems: no parse entry on " + cn + ", trying next candidate");
        }
        api.warn("feeditems: parse entry not found, seen shapes: "
                + (seen.length() == 0 ? "none" : seen));
        return null;
    }

    /**
     * 从解析结果里取卡片列表：先按形状找 {@code GeneralResponse.data}
     * （唯一持有非空卡片 List 的字段），再取其 List 字段。找不到返回 null。
     */
    public static List<?> findItems(HookApi api, Object response) {
        if (response == null) {
            return null;
        }
        try {
            for (Class<?> k = response.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            || f.getType().isPrimitive() || f.getType() == String.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object v = f.get(response);
                    if (v == null || isSimple(v)) {
                        continue; // 与 FeedTagHooks 的产线判据严格一致，不额外加跳过条件
                    }
                    List<?> items = itemsOf(v);
                    if (items != null && !items.isEmpty()) {
                        return items;
                    }
                }
            }
        } catch (Throwable t) {
            api.debug("feeditems: items lookup failed: " + t);
        }
        return null;
    }

    /** 对象里第一个非空、元素带 {@code getCardType()} 的 List（= Pegasus 卡片列表）。 */
    public static List<?> itemsOf(Object data) throws Exception {
        for (Class<?> k = data.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(data);
                if (!(v instanceof List) || ((List<?>) v).isEmpty()) {
                    continue;
                }
                Object first = ((List<?>) v).get(0);
                if (first != null && findMethod(first.getClass(), "getCardType") != null) {
                    return (List<?>) v;
                }
            }
        }
        return null;
    }

    /** 沿父类链找实例字段（Pegasus 卡片字段多声明在 BasicIndexItem 上）。 */
    public static Field field(Class<?> cls, String name) {
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 按「属性名」读值：先试 {@code getXxx()} 无参 getter，再退到同名字段。
     *
     * 为什么必须两路：6.4.0（9100300）首页列表元素是 {@code BasicIndexItem} 后代，
     * 判据走 public 字段 {@code cardGoto}；6.6.0（9130300）的列表元素换成了 holder
     * 类族（dex 实证 {@code LXD0/a;} 等实现 {@code LWD0/a;}，{@code PegasusHolderData}
     * 体系），值只能通过 {@code getCardGoto()/getCardType()/getUri()} 读出，
     * 内部字段名全是单字母。 getter 优先 + 字段兜底，两个版本各命中各的那一路。
     */
    public static Object readProp(Object holder, String name) {
        if (holder == null) {
            return null;
        }
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method m = findMethod(holder.getClass(), getter);
        if (m != null && m.getParameterTypes().length == 0) {
            try {
                return m.invoke(holder);
            } catch (Throwable t) {
                // 落到字段兜底
            }
        }
        Object v = read(holder, name);
        if (v != null) {
            return v;
        }
        // 第三路：协议名（6.6.0 的活模型字段名被 R8 改成单字母，只有注解名可用）
        for (String cand : jsonNames(name)) {
            Object j = readJson(holder, cand);
            if (j != null) {
                return j;
            }
        }
        return null;
    }

    /** 属性是否存在（getter / 同名字段 / 协议名字段任一可达）。 */
    public static boolean hasProp(Object holder, String name) {
        if (holder == null) {
            return false;
        }
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        if (findMethod(holder.getClass(), getter) != null || field(holder.getClass(), name) != null) {
            return true;
        }
        for (String cand : jsonNames(name)) {
            if (hasJson(holder, cand)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 属性名 → 候选协议名：原样 + camelCase 转 snake_case
     * （{@code cardGoto→card_goto}、{@code leftBottomRcmdReasonStyle→
     * left_bottom_rcmd_reason_style}）。宿主 JSON 里的例外（如 {@code up}）由调用方
     * 直接传协议名。
     */
    public static String[] jsonNames(String prop) {
        StringBuilder sb = new StringBuilder(prop.length() + 6);
        for (int i = 0; i < prop.length(); i++) {
            char ch = prop.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        String snake = sb.toString();
        return snake.equals(prop) ? new String[]{prop} : new String[]{prop, snake};
    }

    public static Object read(Object holder, String name) {
        if (holder == null) {
            return null;
        }
        try {
            Field f = field(holder.getClass(), name);
            return f == null ? null : f.get(holder);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 找无参 public 方法（含继承），找不到返回 null。 */
    public static Method findMethod(Class<?> cls, String name) {
        try {
            Method m = cls.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== 按协议名（@SerializedName）定位字段：混淆代唯一的写入口 =====

    private static volatile Class<?> sSerializedNameCls;
    private static volatile Method sSerializedNameValue;

    /** 注解类经宿主 loader 取（先填 value 方法再暴露类，避免半初始化）。 */
    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> annoClass(Object target) throws Throwable {
        Class<?> c = sSerializedNameCls;
        if (c == null) {
            ClassLoader cl = target.getClass().getClassLoader();
            c = Class.forName("com.google.gson.annotations.SerializedName", true, cl);
            sSerializedNameValue = c.getMethod("value");
            sSerializedNameCls = c;
        }
        return (Class<? extends Annotation>) c;
    }

    /**
     * 用 {@code @SerializedName(jsonName)} 找字段。
     *
     * 为什么必须有它：6.6.0 首页卡的活模型（dex 里是 {@code Lra/c;}，gson 直解，
     * 协议名 {@code card_type/card_goto/desc/up/desc_button/left_bottom_rcmd_reason_style}
     * 全在）字段名被 R8 改成了 {@code Y/Z/a0/b0/c0/j/c}，Java 字段名一条都读不到；
     * 而 {@code @SerializedName} 是 gson 反序列化的唯一依据，R8 必须保留它，值就是
     * 服务端 JSON 的 key。已发布的分区屏蔽靠同一个注解读 tname 工作到 6.6.0
     * （PITFALLS #39 补录），这条路的运行时可行性是被产线验证过的。
     * 读还可以走 getter，**写只有这里能落地**。
     */
    public static Field fieldByJsonName(Object target, String jsonName) {
        if (target == null) {
            return null;
        }
        try {
            Class<? extends Annotation> annoCls = annoClass(target);
            Method valueM = sSerializedNameValue;
            for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Annotation anno = f.getAnnotation(annoCls);
                    if (anno == null) {
                        continue;
                    }
                    if (jsonName.equals(valueM.invoke(anno))) {
                        f.setAccessible(true);
                        return f;
                    }
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    /** 读：同名字段 → 协议名注解字段，两代各命中一路。 */
    public static Object readJson(Object target, String jsonName) {
        try {
            Field f = fieldByJsonName(target, jsonName);
            return f == null ? null : f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写（置 null 或赋值）：只认协议名注解字段；成功返回 true。 */
    public static boolean writeJson(Object target, String jsonName, Object value) {
        try {
            Field f = fieldByJsonName(target, jsonName);
            if (f == null) {
                return false;
            }
            f.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 取「真正带协议名的卡片数据对象」。
     *
     * 为什么需要下钻：解析出口拿到的列表元素在两代宿主上不是同一个东西——6.4.0 是
     * {@code BasicIndexItem} 后代（字段就是数据），6.6.0 拿到的可能是渲染层包装
     * （holder {@code LXD0/a;} 与活模型 {@code Lra/c;} 互相持有：holder 有
     * {@code LWD0/a;} 字段，{@code ra.c} 又有 {@code transient XD0.a} 字段），
     * 真正的 {@code @SerializedName} 键在下层那个对象上。写角标必须写到数据对象上，
     * 写到包装上等于什么都没做。
     *
     * 判据用 {@code card_type} 这个每版都在的键；命中就原样返回，否则向下扫一层实例
     * 字段找带该键的对象，并把这次的下钻入口缓存起来（同一代宿主形状稳定）。
     */
    public static Object cardOf(Object item) {
        if (item == null) {
            return item;
        }
        if (hasJson(item, "card_type")) {
            return item;
        }
        Field cached = sCardField;
        if (cached != null) {
            try {
                Object v = cached.get(item);
                if (v != null && hasJson(v, "card_type")) {
                    return v;
                }
            } catch (Throwable t) {
                // 缓存的入口不适用，落到全量扫描
            }
        }
        try {
            for (Class<?> k = item.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            || f.getType().isPrimitive() || f.getType() == String.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object v = f.get(item);
                    if (v == null || v == item) {
                        continue;
                    }
                    if (hasJson(v, "card_type")) {
                        sCardField = f;
                        return v;
                    }
                }
            }
        } catch (Throwable t) {
            return item;
        }
        return item; // 下钻不到就原样返回：调用方的探针日志会说明键是否存在
    }

    private static volatile Field sCardField;

    /** 某协议名在这张卡上是否可达（有注解字段）——用于「锚点未命中」诊断。 */
    public static boolean hasJson(Object target, String jsonName) {
        return fieldByJsonName(target, jsonName) != null;
    }

    /** 把类上所有 {@code @SerializedName -> 字段类型} 拼成一行（一次性现场校准用）。 */
    public static String jsonFieldMap(Object target) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<? extends Annotation> annoCls = annoClass(target);
            Method valueM = sSerializedNameValue;
            for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Annotation anno = f.getAnnotation(annoCls);
                    if (anno == null) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(valueM.invoke(anno)).append(':').append(f.getType().getSimpleName());
                }
            }
        } catch (Throwable t) {
            return "<map failed: " + t + ">";
        }
        return sb.toString();
    }

    private static boolean isSimple(Object o) {
        return o instanceof String || o instanceof Number || o instanceof Boolean
                || o instanceof Character;
    }
}
