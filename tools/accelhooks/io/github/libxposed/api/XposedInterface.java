package io.github.libxposed.api;

import java.util.List;

/**
 * 桌面自测用的最小 libxposed 形状：只保留 AccelHooks/HookApi 引用的成员。
 * 真机侧编译兼容由 build_module.py（真 stub）保证。
 */
public interface XposedInterface {

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    interface Chain {
        Object getThisObject();

        List<Object> getArgs();

        Object getArg(int index);

        Object proceed() throws Throwable;

        Object proceed(Object[] args) throws Throwable;
    }
}
