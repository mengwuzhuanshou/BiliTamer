package com.tamer.bili.accel;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 本机当前所在网络的身份：谁要记账谁来问，不注册监听、不轮询、不申请任何权限。
 *
 * <p>为什么不用「playurl 下发的 host 集合」当指纹（那是这行的第一版写法）：在线通路唯一改得到
 * 地址的锚点 {@code handleDashBaseUrl} 返回值只有一个 String，backup_url 根本进不到
 * {@link CdnResolver}，于是那把「网络指纹」实际等于「这条流母本域名的哈希」。真机一次播放里
 * 视频流母本 cosov、音频流母本 akamai，两把指纹两本账，在 cosov 那本上记下的死节点
 * akamai 那条流一个都读不到——降级看着生效，跨流跨次播放却全是空的。网络身份必须与稿件、
 * 码率、流类型无关，否则它记的就不是「这台机器现在连不上什么」。
 *
 * <p>口径：只看局域网网卡（wlan/eth）的「网卡名 + v4 地址」——换 wifi 就是换网络；不在任何
 * 局域网里就算一个蜂窝桶（常量），因为运营商每次拨号都换出口 IP，按地址记会让账本在蜂窝下
 * 永远攒不起来。蜂窝/热点/p2p 这些网卡本身不进哈希：它们起落是基带的事，不是「换网络」。
 *
 * <p>判错的代价是有界的：最多十分钟内在错的那本书上排序，而 {@link NodeLedger} 只把节点降到
 * 队尾、绝不删候选，节点一交付字节就当场销账。
 */
final class NetworkId {

    /** 重新问一次内核的最小间隔：起播时几十条流会连着问，没必要每次都走一遍系统调用。 */
    private static final long REFRESH_MS = 30000L;

    private String cached = "";
    private long cachedAt;

    /** 拿不到任何网卡时返回空串——调用方据此当作「没有网络身份」，不记也不降级。 */
    synchronized String get() {
        long now = System.currentTimeMillis();
        if (cachedAt != 0L && now - cachedAt < REFRESH_MS) {
            return cached;
        }
        String next = read();
        cachedAt = now;
        if (!next.isEmpty()) {
            cached = next;
        }
        return cached;
    }

    private static String read() {
        List<String> parts = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics == null) {
                return "";
            }
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                String name = nic.getName();
                if (name == null || !isLan(name) || nic.isLoopback() || !nic.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        parts.add(name + "=" + addr.getHostAddress());
                    }
                }
            }
        } catch (Throwable t) {
            // 读不到就不换身份：这一步只决定「记在哪本书上」，不该打扰取数。
            return "";
        }
        if (parts.isEmpty()) {
            // 不在任何局域网里＝蜂窝那一本。蜂窝必须是个常量桶：运营商每次拨号都换出口 IP，
            // 按地址算会让账本在蜂窝下永远攒不起来，而蜂窝恰恰是最需要「别再撞死节点」的场景。
            parts.add("cellular");
        }
        Collections.sort(parts);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            sb.append(parts.get(i)).append('\n');
        }
        String text = sb.toString();
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            h ^= (long) text.charAt(i) & 0xFFL;
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    /**
     * 只认局域网网卡。蜂窝（rmnet*）、热点（ap）、p2p、蓝牙共享这些一律不看：
     * 真机第一版把这些也进了哈希，结果同一张 wifi 上两次起播算出两把身份
     * （{@code 9ffa3b5e…} → {@code 57f8231b…}），盘上刚记的降级下一趟就查无此书——
     * rmnet_data* 是跟着基带拨号起落的，它变不等于网络变。
     */
    private static boolean isLan(String name) {
        return name.startsWith("wlan") || name.startsWith("eth");
    }
}
