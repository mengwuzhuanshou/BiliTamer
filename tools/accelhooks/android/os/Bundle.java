package android.os;

import java.util.HashMap;
import java.util.Map;

/** 桌面自测用最小 Bundle：只实现 AccelHooks/假 binder 用到的字符串槽位。 */
public final class Bundle {
    private final Map<String, Object> map = new HashMap<String, Object>();

    public String getString(String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }

    public void putString(String key, String value) {
        map.put(key, value);
    }
}
