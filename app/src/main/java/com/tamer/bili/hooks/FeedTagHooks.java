package com.tamer.bili.hooks;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 首页推荐分区屏蔽（v1.7.0 正式版）：按 args.tname 词表过滤推荐卡。
 *
 * 管线（9100300 实测）：PegasusViewModel.y0 → Refresh/LoadMore/CommitPreloaded 三
 * action 共用解析器 com.bilibili.pegasus.request.g（@Singleton okhttp retro
 * Converter）→ a(Lokhttp3/E;)GeneralResponse（内部委托 PegasusGsonParser.g）→
 * GeneralResponse.data=ME0.e(PegasusResponse)→d(): List&lt;PegasusHolderData&gt;。在此
 * AFTER 原地移除命中卡即覆盖刷新+加载更多+预载提交，下游 Store/渲染同源一致。
 *
 * <p>6.6.0（9130300，dex 实证）：解析入口从 request.g 搬到 <b>request.h</b>，方法形状
 * 一字不差（a(Lokhttp3/E;)GeneralResponse，okhttp3.Response 仍是 E）；而 request.g 被 R8
 * 复用成只有一个 static a()Z 的无关类——「类名能加载」不再代表「类是对的」，故候选表按
 * 形状逐个尝试，miss 就换下一个（PITFALLS #16）。
 *
 * 词表：conf 键 feed_blocked_tnames（逗号分隔，设置页支持逗号/换行批量输入）。
 * 匹配语义 = tname 包含词（含「主机游戏」类长标签被「游戏」命中）；词表为空=功能关。
 * 字段定位用 @SerializedName 注解反射（协议名抗混淆漂移），同名字段只作兜底。
 * 6.6.0（9130300）实证这个**顺序不能颠倒**：args 模型被 R8 改成 {@code LbE0/a;}
 * （toString 自证仍是 {@code ArgsData(aid=…, tname=…)}），字段名全成单字母
 * {@code a}..{@code p}，但 {@code @SerializedName("tname")} 原样保留——本功能命中的
 * 是注解那一路，字段名兜底在这一代必然落空。反过来若先按字段名找，功能会静默变成
 * 空操作而日志毫无异样（PITFALLS #39 补录）。
 *
 * 渐进式 chunk（PegasusResponseTypeAdapter 每 5 条回调）未经此口，若实测有漏卡
 * 再补 ME0.e.a BEFORE 挂点。
 */
public final class FeedTagHooks {

    private final HookApi api;
    private final ClassLoader cl;

    private volatile String rawWords = null;
    private volatile String[] words = new String[0];

    private volatile Class<?> serializedNameCls;
    private volatile Method annoValueMethod;

    public FeedTagHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() throws Throwable {
        // 解析器类名单字母名随构建漂移，且旧名会被 R8 复用（6.6.0 实测：
        // com.bilibili.pegasus.request.g 只剩一个 static a()Z，真正的解析入口搬到了
        // request.h，形状与 6.5.0 的 request.g 完全一致 a(Lokhttp3/E;)GeneralResponse）。
        // 因此「类能加载」不等于「类是对的」：必须按形状找入口，找不到就换下一个候选。
        Class<?> parser = null;
        String parserName = null;
        Method target = null;
        StringBuilder cands = new StringBuilder();
        for (String cn : new String[]{"com.bilibili.pegasus.request.h",
                "com.bilibili.pegasus.request.g",
                "com.bilibili.pegasus.request.PegasusGsonParser"}) {
            Class<?> c;
            try {
                c = api.load(cl, cn);
            } catch (Throwable t) {
                api.debug("feedtag: " + cn + " not loadable: " + t);
                continue;
            }
            Method found = null;
            for (Method mm : c.getDeclaredMethods()) {
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length != 1 || !ps[0].getName().startsWith("okhttp3.")) {
                    continue;
                }
                if (!mm.getReturnType().getName().contains("GeneralResponse")) {
                    continue;
                }
                cands.append(cn).append(".").append(mm.getName()).append("(")
                        .append(ps[0].getName()).append("), ");
                if (found == null) {
                    found = mm;
                }
            }
            if (found != null) {
                parser = c;
                parserName = cn;
                target = found;
                break;
            }
            api.debug("feedtag: no parse entry on " + cn + ", trying next candidate");
        }
        if (target == null) {
            api.error("feedtag: parse entry not found on any candidate (request.h / request.g"
                    + " / PegasusGsonParser), seen shapes: "
                    + (cands.length() == 0 ? "none" : cands), null);
            return;
        }
        api.deoptimize(target);
        api.addHook("feedtag: " + parserName + "." + target.getName(), target, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    applyFilter(result);
                } catch (Throwable t) {
                    api.error("feedtag: filter failed", t);
                }
                return result;
            }
        });
        api.info("feedtag: hook ok -> " + parser.getName() + "." + target.getName());
    }

    /** 词表热更新（设置页改词表 → 用户强停 B 站重开后新值生效）。 */
    private void syncWords() {
        String raw = api.getFeedBlockedTnames();
        if (raw == null) {
            raw = "";
        }
        if (raw.equals(rawWords)) {
            return;
        }
        rawWords = raw;
        ArrayList<String> list = new ArrayList<String>();
        for (String w : raw.split("[，,;；、\\r\\n]+")) {
            String t = w.trim();
            if (t.length() > 0 && !list.contains(t)) {
                list.add(t);
            }
        }
        words = list.toArray(new String[0]);
        api.info("feedtag: wordlist " + words.length + " word(s)");
    }

    /** 解析出口原地移除 tname 命中卡（Gson 反序列化 List 可变）。 */
    private void applyFilter(Object response) throws Exception {
        if (response == null) {
            return;
        }
        syncWords();
        if (words.length == 0) {
            return;
        }
        Object data = findResponseData(response);
        if (data == null) {
            return;
        }
        List<?> items = findItemsList(data);
        if (items == null || items.isEmpty()) {
            return;
        }
        int removed = 0;
        StringBuilder names = new StringBuilder();
        for (int i = items.size() - 1; i >= 0; i--) {
            Object item = items.get(i);
            String tn = readTname(item);
            if (tn == null) {
                continue;
            }
            for (String w : words) {
                if (tn.contains(w)) {
                    items.remove(i);
                    removed++;
                    if (names.length() > 0) {
                        names.append(",");
                    }
                    names.append(tn);
                    break;
                }
            }
        }
        if (removed > 0) {
            api.info("feedtag: blocked " + removed + " card(s) [" + names + "]");
        }
    }

    /** GeneralResponse.data：按形状找唯一带 items 列表的对象字段。 */
    private Object findResponseData(Object resp) throws Exception {
        for (Class<?> k = resp.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || f.getType().isPrimitive() || f.getType() == String.class) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(resp);
                if (v == null || isSimple(v)) {
                    continue;
                }
                List<?> items = findItemsList(v);
                if (items != null && !items.isEmpty()) {
                    return v;
                }
            }
        }
        return null;
    }

    /** ME0.e.items：对象里第一个非空 List，且元素响应 getCardType。 */
    private List<?> findItemsList(Object data) throws Exception {
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
                if (first != null && findNoArg(first.getClass(), "getCardType") != null) {
                    return (List<?>) v;
                }
            }
        }
        return null;
    }

    /** args.tname：getArgs() → @SerializedName("tname") 或同名字段。 */
    private String readTname(Object item) {
        try {
            Method getArgs = findNoArg(item.getClass(), "getArgs");
            if (getArgs == null) {
                return null;
            }
            Object args = getArgs.invoke(item);
            if (args == null) {
                return null;
            }
            Object v = findBySerializedName(args, "tname");
            if (v == null) {
                v = findStringFieldByName(args, "tname");
            }
            return v instanceof String && ((String) v).length() > 0 ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 找 @SerializedName(value) 标注的字段值（gson 注解类经目标 loader 反射拿）。 */
    private Object findBySerializedName(Object holder, String jsonName) throws Exception {
        Class<?> annoCls = serializedNameCls;
        Method annoValue = annoValueMethod;
        if (annoCls == null) {
            annoCls = Class.forName("com.google.gson.annotations.SerializedName", true, cl);
            annoValue = annoCls.getMethod("value");
            serializedNameCls = annoCls;
            annoValueMethod = annoValue;
        }
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Annotation anno = f.getAnnotation((Class<? extends Annotation>) annoCls);
                if (anno == null) {
                    continue;
                }
                Object val = annoValue.invoke(anno);
                if (jsonName.equals(val)) {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

    /** 无注解兜底：按字段名找 String 实例字段。 */
    private Object findStringFieldByName(Object holder, String name) {
        try {
            for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                try {
                    Field f = k.getDeclaredField(name);
                    if (f.getType() == String.class
                            && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        return f.get(holder);
                    }
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Method findNoArg(Class<?> cls, String name) {
        try {
            Method m = cls.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isSimple(Object o) {
        return o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Character;
    }
}
