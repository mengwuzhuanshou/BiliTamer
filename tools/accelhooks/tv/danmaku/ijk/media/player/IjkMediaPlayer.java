package tv.danmaku.ijk.media.player;

import android.os.Bundle;

/** 替身：模拟旧管线 binder 的同步系 case——原逻辑把「最终 url」写进 bundle 出参。 */
public class IjkMediaPlayer {

    public static class IjkMediaPlayerBinder {
        /** 每个 case 下发的最终地址由测试注入（免流/白名单外等场景都能演）。 */
        public String urlForCase = "https://upz.m.bilivideo.com/legacy/f.m4s?deadline=7&sign=q";

        public int onNativeInvoke(int what, Bundle data) {
            if (what == 131075 || what == 131077 || what == 131079 || what == 131081) {
                data.putString("url", urlForCase);
            }
            return 0;
        }
    }
}
