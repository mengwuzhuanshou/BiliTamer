package com.tamer.bili.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多 CDN 节点选择：候选地址展开、节点健康度（bps 指数平均 + 失败退避）、封禁表。
 * 移植自参考实现 Bilibili-thread-ripper 的 src/cdn-resolver.js。
 *
 * <p>关键前提（国际版 6.5.0 静态复核结论）：官方离线缓存走 SingleSpec，一个流只有一条连接；
 * 加速的全部收益来自这里「同一份签名地址可以问任何节点要」这一点。
 */
public final class CdnResolver {

    /** 国内节点（upos 镜像族）。 */
    public static final String[] MAINLAND_HOSTS = {
            "upos-sz-mirrorali.bilivideo.com",
            "upos-sz-mirrorhw.bilivideo.com",
            "upos-sz-mirrorbos.bilivideo.com",
            "upos-sz-mirror08c.bilivideo.com",
            "upos-sz-mirrorbd.bilivideo.com",
            "upos-sz-mirror14b.bilivideo.com",
            "upos-sz-estgoss.bilivideo.com",
            "upos-sz-mirrorcos.bilivideo.com"
    };

    /** 海外/港澳节点。 */
    public static final String[] OVERSEAS_HOSTS = {
            "upos-sz-mirrorcosov.bilivideo.com",
            "upos-sz-mirroraliov.bilivideo.com",
            "cn-hk-eq-01-01.bilivideo.com",
            "cn-hk-eq-01-03.bilivideo.com"
    };

    public static final int MODE_MAINLAND = 0;
    public static final int MODE_OVERSEAS = 1;
    public static final int MODE_CUSTOM = 2;

    /** 每次取候选都重新读一遍（改配置立即生效，不做启动期快照）。 */
    public interface Provider {
        int mode();

        /** 自定义模式的节点列表（已 normalize）；空表示未配置。 */
        String[] customHosts();
    }

    /** 一条地址的观测健康度。 */
    private static final class Health {
        int failures;
        long blockedUntil;
        long lastSuccessAt;
        double bps;
    }

    /**
     * 封禁表：只统计「一个字节都没拿到」的失败，并按「节点/地址/节点+地址」三种责任区分摊。
     * HTTP 4xx 既可能是节点缺文件，也可能是这份签名地址被所有节点拒绝——谁交付过数据谁说话。
     */
    public static final class BanList {
        /**
         * 封禁时效：一次网络抖动不该把节点永久毒化到进程重启。真机实测宿主主进程连跑两天，
         * {@code emptyReplies} 只增不减 → 每个节点攒够 2 次空响应就永久封 → 可用节点越缩越少，
         * 最终代理被饿死（界面 0 KB/s，强停重启才恢复）。给 strike 记时间戳并按时效淘汰，
         * 让长命进程能自愈。
         */
        private static final long STRIKE_TTL_MS = 120000L;

        private final int limit;
        private final Map<String, Integer> emptyReplies = new HashMap<String, Integer>();
        private final Map<String, Long> lastStrikeAt = new HashMap<String, Long>();
        private final Set<String> goodNodes = new HashSet<String>();
        private final Set<String> goodAddresses = new HashSet<String>();
        private final Set<String> reported = new HashSet<String>();
        private Set<String> banned = new HashSet<String>();

        public BanList(int limit) {
            this.limit = limit < 1 ? 2 : limit;
        }

        private static String nodeKey(String host) {
            return "node:" + host;
        }

        private static String addressKey(String address) {
            return "address:" + address;
        }

        private static String pairKey(String host, String address) {
            return "pair:" + host + " " + address;
        }

        /** 记录一次空响应；返回是否产生了新封禁。 */
        public synchronized boolean record(String url, long receivedBytes, int httpStatus) {
            if (receivedBytes > 0) {
                return false;
            }
            String host = RangeCore.hostOf(url);
            if (host.isEmpty()) {
                return false;
            }
            boolean refused = httpStatus >= 400 && httpStatus < 500;
            String key = host + "\n" + RangeCore.addressOf(url) + "\n" + (refused ? "refused" : "");
            Integer old = emptyReplies.get(key);
            emptyReplies.put(key, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
            lastStrikeAt.put(key, Long.valueOf(System.currentTimeMillis()));
            return judge();
        }

        /** 交付过数据：把该节点与该地址记为「可用」，用于反过来判定 4xx 该怪谁。 */
        public synchronized void success(String url) {
            String host = RangeCore.hostOf(url);
            String address = RangeCore.addressOf(url);
            if (host.isEmpty() || (goodNodes.contains(host) && goodAddresses.contains(address))) {
                return;
            }
            goodNodes.add(host);
            goodAddresses.add(address);
            judge();
        }

        private boolean judge() {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, Long>> lit = lastStrikeAt.entrySet().iterator();
            while (lit.hasNext()) {
                Map.Entry<String, Long> e = lit.next();
                if (now - e.getValue().longValue() > STRIKE_TTL_MS) {
                    lit.remove();
                    emptyReplies.remove(e.getKey());
                }
            }
            Map<String, Integer> strikes = new HashMap<String, Integer>();
            Iterator<Map.Entry<String, Integer>> it = emptyReplies.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Integer> entry = it.next();
                String[] parts = entry.getKey().split("\n");
                String node = parts[0];
                String address = parts.length > 1 ? parts[1] : "";
                boolean refused = parts.length > 2 && "refused".equals(parts[2]);
                String blamed;
                if (!refused) {
                    blamed = nodeKey(node);
                } else if (goodNodes.contains(node)) {
                    blamed = goodAddresses.contains(address) ? pairKey(node, address) : addressKey(address);
                } else if (goodAddresses.contains(address)) {
                    blamed = nodeKey(node);
                } else {
                    blamed = null;
                }
                if (blamed == null) {
                    continue;
                }
                Integer prior = strikes.get(blamed);
                strikes.put(blamed, Integer.valueOf(prior == null ? 0 : prior.intValue()) + entry.getValue());
            }
            Set<String> next = new HashSet<String>();
            Iterator<Map.Entry<String, Integer>> sit = strikes.entrySet().iterator();
            while (sit.hasNext()) {
                Map.Entry<String, Integer> entry = sit.next();
                if (entry.getValue().intValue() >= limit) {
                    next.add(entry.getKey());
                }
            }
            banned = next;
            boolean added = false;
            Iterator<String> bit = next.iterator();
            while (bit.hasNext()) {
                String key = bit.next();
                if (reported.contains(key)) {
                    continue;
                }
                reported.add(key);
                added = true;
            }
            return added;
        }

        /** 惰性淘汰：距上次 strike 超过时效就重算封禁表，让节点在长命进程里能自动解禁。 */
        private void expireLocked() {
            if (banned.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, Long>> it = lastStrikeAt.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue().longValue() > STRIKE_TTL_MS) {
                    judge();
                    return;
                }
            }
        }

        public synchronized boolean allows(String url) {
            expireLocked();
            String host = RangeCore.hostOf(url);
            String address = RangeCore.addressOf(url);
            return !banned.contains(nodeKey(host)) && !banned.contains(addressKey(address))
                    && !banned.contains(pairKey(host, address));
        }

        public synchronized boolean allowsNode(String url) {
            expireLocked();
            return !banned.contains(nodeKey(RangeCore.hostOf(url)));
        }

        public synchronized List<String> bannedHosts() {
            List<String> hosts = new ArrayList<String>();
            Iterator<String> it = banned.iterator();
            while (it.hasNext()) {
                String key = it.next();
                if (key.startsWith("node:")) {
                    hosts.add(key.substring(5));
                }
            }
            return hosts;
        }

        public synchronized void reset() {
            emptyReplies.clear();
            lastStrikeAt.clear();
            goodNodes.clear();
            goodAddresses.clear();
            reported.clear();
            banned = new HashSet<String>();
        }
    }

    private final String primary;
    private final List<String> backups;
    private final Provider provider;
    private final BanList bans;
    private final Map<String, Health> health = new LinkedHashMap<String, Health>();
    private int cursor;
    private int mediaRangeCount;
    private int rangeCursor;

    public CdnResolver(String primaryUrl, List<String> backupUrls, Provider provider, BanList bans) {
        this.primary = primaryUrl;
        this.backups = new ArrayList<String>();
        addBackups(backupUrls);
        this.provider = provider;
        this.bans = bans == null ? new BanList(2) : bans;
    }

    /** 后续拿到更多下发地址（playurl 的 backupUrl / 重试地址）时并入，不丢已有健康度。 */
    public synchronized void addBackups(List<String> urls) {
        if (urls == null) {
            return;
        }
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            if (url != null && RangeCore.isMediaUrl(url) && !url.equals(primary) && !backups.contains(url)) {
                backups.add(url);
            }
        }
    }

    /** 原始下发地址（primary + backup，已去重且必须是媒体地址）。 */
    public List<String> originals() {
        List<String> out = new ArrayList<String>();
        addMedia(out, primary);
        for (int i = 0; i < backups.size(); i++) {
            addMedia(out, backups.get(i));
        }
        return out;
    }

    private static void addMedia(List<String> out, String url) {
        if (url != null && RangeCore.isMediaUrl(url) && !out.contains(url)) {
            out.add(url);
        }
    }

    private String[] hostsFor(int mode, String[] custom) {
        if (mode == MODE_CUSTOM && custom.length > 0) {
            return custom;
        }
        return mode == MODE_OVERSEAS ? OVERSEAS_HOSTS : MAINLAND_HOSTS;
    }

    /**
     * 展开全部可用地址：先保留模式允许的下发原址，再补上「同一签名换节点」的合成地址。
     * 只有下发全是 akamaized 时才允许拿 akamai 地址当 donor，否则 akamai 一律不派生
     * （其它节点不认 akamai 的签名）。节点优先展开，首批请求天然分散在不同节点上。
     */
    public List<String> allUrls() {
        int mode = provider == null ? MODE_MAINLAND : provider.mode();
        String[] custom = provider == null ? new String[0] : provider.customHosts();
        List<String> originals = originals();
        String[] hosts = hostsFor(mode, custom);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < originals.size(); i++) {
            String url = originals.get(i);
            String host = RangeCore.hostOf(url);
            boolean allowed;
            if (mode == MODE_CUSTOM && custom.length > 0) {
                allowed = contains(custom, host);
            } else if (mode == MODE_OVERSEAS) {
                allowed = !containsHost(MAINLAND_HOSTS, host);
            } else {
                allowed = containsHost(MAINLAND_HOSTS, host);
            }
            if (allowed && !out.contains(url)) {
                out.add(url);
            }
        }
        String donor = null;
        for (int i = 0; i < originals.size(); i++) {
            if (!RangeCore.isAkamaiUrl(originals.get(i))) {
                donor = originals.get(i);
                break;
            }
        }
        if (donor != null) {
            for (int i = 0; i < hosts.length; i++) {
                String url = RangeCore.swapHost(donor, hosts[i], false);
                if (url != null && RangeCore.isMediaUrl(url) && !out.contains(url)) {
                    out.add(url);
                }
            }
        } else {
            for (int i = 0; i < hosts.length; i++) {
                for (int j = 0; j < originals.size(); j++) {
                    String url = RangeCore.swapHost(originals.get(j), hosts[i], true);
                    if (url != null && RangeCore.isMediaUrl(url) && !out.contains(url)) {
                        out.add(url);
                    }
                }
            }
        }
        return out;
    }

    private static boolean contains(String[] hosts, String host) {
        for (int i = 0; i < hosts.length; i++) {
            if (hosts[i].equals(host)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHost(String[] known, String host) {
        return contains(known, host);
    }

    /** 去掉被封禁节点后的候选；全被封时退回全集，至少保证有地址可用。 */
    public List<String> urls() {
        List<String> all = allUrls();
        List<String> allowed = new ArrayList<String>();
        for (int i = 0; i < all.size(); i++) {
            if (bans.allows(all.get(i))) {
                allowed.add(all.get(i));
            }
        }
        return allowed.isEmpty() ? all : allowed;
    }

    /** 轮转起点随子块号偏移：并行的多个子块不会挤在同一个节点上。 */
    public synchronized List<String> ordered(int pieceIndex) {
        long now = System.currentTimeMillis();
        List<String> candidates = new ArrayList<String>();
        List<String> available = new ArrayList<String>();
        List<String> pool = urls();
        for (int i = 0; i < pool.size(); i++) {
            String url = pool.get(i);
            candidates.add(url);
            Health h = health.get(url);
            if (h == null || h.blockedUntil <= now) {
                available.add(url);
            }
        }
        List<String> chosen = available.isEmpty() ? candidates : available;
        List<String> rotated = new ArrayList<String>();
        if (chosen.isEmpty()) {
            return rotated;
        }
        int offset = (cursor + pieceIndex) % chosen.size();
        for (int i = 0; i < chosen.size(); i++) {
            rotated.add(chosen.get((offset + i) % chosen.size()));
        }
        cursor = (cursor + 1) % chosen.size();
        return rotated;
    }

    /** 子块取址：先按健康度排序，预热阶段全量摊开，之后按游标每次取 3 个。 */
    public synchronized List<String> rangeCandidates() {
        long now = System.currentTimeMillis();
        List<String> pool = new ArrayList<String>();
        List<String> all = urls();
        for (int i = 0; i < all.size(); i++) {
            Health h = health.get(all.get(i));
            if (h == null || h.blockedUntil <= now) {
                pool.add(all.get(i));
            }
        }
        if (pool.isEmpty()) {
            return all;
        }
        sortByHealth(pool);
        boolean firstRange = mediaRangeCount == 0;
        int width = Math.min(firstRange ? pool.size() : 3, pool.size());
        List<String> selected = new ArrayList<String>();
        int warmup = (provider == null || provider.mode() == MODE_MAINLAND) ? 1 : 4;
        if (mediaRangeCount < warmup) {
            for (int i = 0; i < width; i++) {
                selected.add(pool.get(i));
            }
            rangeCursor = width % pool.size();
        } else {
            int offset = rangeCursor % pool.size();
            for (int i = 0; i < width; i++) {
                selected.add(pool.get((offset + i) % pool.size()));
            }
            rangeCursor = (rangeCursor + width) % pool.size();
        }
        mediaRangeCount++;
        return selected;
    }

    /** 起播探测：把下发的原址排在派生地址前面，最多 8 条一起抢。 */
    public synchronized List<String> startupCandidates() {
        long now = System.currentTimeMillis();
        LinkedHashSet<String> merged = new LinkedHashSet<String>();
        merged.addAll(originals());
        merged.addAll(allUrls());
        List<String> out = new ArrayList<String>();
        Iterator<String> it = merged.iterator();
        while (it.hasNext() && out.size() < 8) {
            String url = it.next();
            Health h = health.get(url);
            if (h == null || h.blockedUntil <= now) {
                out.add(url);
            }
        }
        return out;
    }

    /** 兜底（抢救）候选：健康度优先，用于子块首轮全败后重建列表。 */
    public synchronized List<String> rescueCandidates() {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<String>();
        List<String> all = urls();
        for (int i = 0; i < all.size(); i++) {
            Health h = health.get(all.get(i));
            if (h == null || h.blockedUntil <= now) {
                out.add(all.get(i));
            }
        }
        sortByHealth(out);
        return out;
    }

    private void sortByHealth(List<String> urls) {
        final Map<String, Health> snapshot = health;
        Collections.sort(urls, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                Health ha = snapshot.get(a);
                Health hb = snapshot.get(b);
                long sa = ha == null ? 0L : ha.lastSuccessAt;
                long sb = hb == null ? 0L : hb.lastSuccessAt;
                if (sa != sb) {
                    return sa > sb ? -1 : 1;
                }
                double ba = ha == null ? 0D : ha.bps;
                double bb = hb == null ? 0D : hb.bps;
                if (ba == bb) {
                    return 0;
                }
                return ba > bb ? -1 : 1;
            }
        });
    }

    public synchronized void success(String url, double bps) {
        bans.success(url);
        Health h = health.get(url);
        if (h == null) {
            h = new Health();
            health.put(url, h);
        }
        h.failures = 0;
        h.blockedUntil = 0L;
        h.lastSuccessAt = System.currentTimeMillis();
        h.bps = h.bps > 0D ? h.bps * 0.65D + bps * 0.35D : bps;
    }

    /** 失败退避：3s 起指数翻倍，最多 60s；拿到过字节的失败同样计入（与参考实现一致）。 */
    public synchronized void failure(String url, int httpStatus) {
        bans.record(url, 0L, httpStatus);
        applyBackoff(url);
    }

    /**
     * 记录「已经拿到字节后才中断」：这类失败不该判死节点，只退避。
     * receivedBytes 必须如实传——BanList 只封「一个字节都没拿到」的失败，传 0 等于把
     * 交付过数据的节点当空响应处理，两次就封；真机换连接风暴里镜像就是这样被打封的。
     */
    public synchronized void failurePartial(String url, long receivedBytes) {
        bans.record(url, receivedBytes, 0);
        applyBackoff(url);
    }

    private void applyBackoff(String url) {
        Health h = health.get(url);
        if (h == null) {
            h = new Health();
            health.put(url, h);
        }
        h.failures++;
        int shift = Math.min(h.failures, 4);
        long backoff = 3000L * (1L << shift);
        h.blockedUntil = System.currentTimeMillis() + Math.min(60000L, backoff);
    }

    public synchronized boolean allows(String url) {
        return bans.allows(url);
    }

    /** 供日志/面板用的一行状态。 */
    public synchronized String status() {
        List<String> all = allUrls();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            String url = all.get(i);
            Health h = health.get(url);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(RangeCore.hostOf(url)).append('[');
            if (!bans.allowsNode(url)) {
                sb.append("banned");
            } else if (h != null && h.blockedUntil > System.currentTimeMillis()) {
                sb.append("blocked");
            } else if (h != null && h.lastSuccessAt > 0L) {
                // PieceDownloader.bps() 给的是 KiB/s，按 MB/s 印出来要先除一次 1024。
                sb.append("healthy ");
                sb.append(Math.round(h.bps / 102.4D) / 10.0D).append("MB/s");
            } else {
                sb.append("untested");
            }
            sb.append(']');
        }
        return sb.toString();
    }
}
