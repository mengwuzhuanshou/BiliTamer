package com.tamer.bili.accel;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
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
 * 多 CDN 节点选择：候选地址展开、节点健康度（bps 指数平均 + 失败退避）、封禁表、
 * 跨会话的不可达账本与服务端限速信号。
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

        /** 本机当前网络身份（不可达账本的键），与稿件、码率、流类型都无关。 */
        String networkId();
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
            // httpStatus==0 意味着连 HTTP 响应头都没拿到：DNS 失败、连接被拒、握手后立刻
            // reset。这类失败的成因在本机链路，不在节点——一次断网/换网会同时打断所有在飞
            // 请求，于是每个节点各记一笔，两轮就把全部节点封死（真机账面：
            // UnknownHostException 当刻 3 个节点已 banned、随后 7/8 banned），而这张表又是
            // 全进程共享的，音频流跟着视频流一起没节点可用。退避照常，记账免谈。
            if (httpStatus <= 0) {
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

    /** 用来拨号的那份签名地址：播放器每重连一次就换一套 query，见 {@link #resignKey}。 */
    private volatile String primary;
    private final List<String> backups;
    private final Provider provider;
    private final BanList bans;
    private final NodeLedger ledger;
    private final CdnThrottle throttle;
    private final Map<String, Health> health = new LinkedHashMap<String, Health>();
    private int cursor;
    private int mediaRangeCount;
    private int rangeCursor;

    public CdnResolver(String primaryUrl, List<String> backupUrls, Provider provider, BanList bans) {
        this(primaryUrl, backupUrls, provider, bans, null, null);
    }

    /**
     * ledger/throttle 是进程级共享的（按地址缓存的解析器很多个，账只有一本）；传 null 即
     * 「只用退避和封禁表」，桌面自测与降级路径都走这一条。
     */
    public CdnResolver(String primaryUrl, List<String> backupUrls, Provider provider, BanList bans,
                       NodeLedger ledger, CdnThrottle throttle) {
        this.primary = primaryUrl;
        this.backups = new ArrayList<String>();
        addBackups(backupUrls);
        this.provider = provider;
        this.bans = bans == null ? new BanList(2) : bans;
        this.ledger = ledger;
        this.throttle = throttle;
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

    /**
     * 播放器每重连一次就把整套 query 重签一遍（路径不变）：这条流还是同一条流，
     * 健康度、退避、账本都得留着，只把用来拨号的签名换成新的。
     *
     * <p>不换就等于拿过期的 deadline/upsig 去问节点——那是必然 403，比重新建一个解析器更糟。
     */
    public synchronized void resignKey(String url) {
        if (url == null || url.equals(primary)) {
            return;
        }
        String next = RangeCore.fileKeyOf(url);
        if (!next.isEmpty() && next.equals(RangeCore.fileKeyOf(primary))) {
            primary = url;
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

    /**
     * 记账用的网络身份：整个进程一把，与稿件、码率、流类型无关（见 {@link NetworkId}）。
     *
     * <p>这里曾经是 {@code NodeLedger.fingerprint(originals())}——拿本次下发 host 集合做哈希。
     * 那个口径在线上是假的：改写锚点只返回一条 base_url，backups 恒空，于是「网络指纹」退化成
     * 「这条流母本域名的哈希」，同一次播放里视频流和音频流各记一本账，降级互相看不见。
     */
    public String fingerprint() {
        return provider == null ? "" : provider.networkId();
    }

    /**
     * 本网络里已被账本判死的节点数，取证用的一个读数。
     * 与降级判定同源（同一本账、同一个指纹），所以它不为 0 而候选照旧选中该节点，
     * 就说明降级没生效——这正是「投毒验证」要看的那一眼。
     */
    public synchronized int demotedCount() {
        NodeLedger l = ledger;
        String fp = fingerprint();
        return l == null || fp.isEmpty() ? 0 : l.penalizedCount(fp);
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
     *
     * <p>国内档（默认）不过滤原址的 host 家族：真机取证过一次播放下发
     * {@code upos-sz-mirrorcosov}（41 条）+{@code upos-hz-mirrorakam.akamaized}（12 条），
     * 旧写法按「国内节点表」筛原址，两条全被丢弃，只留 cosov 当母本派生 8 个大陆地址——
     * 候选 100% 变成「海外签名问大陆节点」，其中三个大陆镜像在本机根本没有 DNS 记录。
     * 官方下发的地址是这次网络里唯一被服务端认可过的入口，任何模式都不该把它扔掉；
     * 海外/自定义档仍然按用户点名筛（那是明确表达的偏好，不是默认值）。
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
                allowed = true;
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
        return downgradeUnreachable(out);
    }

    /**
     * 把本机已证实连不上的节点降到队尾（不删：全被降级时它们照样是候选）。
     * 排在前面的是「这次下发授权过的地址」，其次才是派生地址，稳定排序因此天然成立。
     */
    private List<String> downgradeUnreachable(List<String> urls) {
        NodeLedger l = ledger;
        if (l == null || urls.size() < 2) {
            return urls;
        }
        String fp = fingerprint();
        if (fp.isEmpty()) {
            return urls;
        }
        List<String> reachable = new ArrayList<String>(urls.size());
        List<String> unreachable = new ArrayList<String>();
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            if (l.penalized(fp, RangeCore.hostOf(url))) {
                unreachable.add(url);
            } else {
                reachable.add(url);
            }
        }
        if (unreachable.isEmpty()) {
            return urls;
        }
        reachable.addAll(unreachable);
        return reachable;
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
        merged.addAll(downgradeUnreachable(originals()));
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
        NodeLedger l = ledger;
        if (l != null) {
            l.noteSuccess(fingerprint(), RangeCore.hostOf(url));
        }
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
        failure(url, httpStatus, null);
    }

    /**
     * 失败退避 + 两张共享账。cause 必须如实传：只有「没拿到 HTTP 状态、也没拿到字节」的失败
     * 才可能进不可达账本，而且按成因分两种账（见 {@link #noteZeroByte}）；
     * 慢、被 4xx 拒、交付过一半都不是节点连不上的证据。
     */
    public synchronized void failure(String url, int httpStatus, IOException cause) {
        bans.record(url, 0L, httpStatus);
        CdnThrottle t = throttle;
        if (t != null) {
            t.noteLimited(httpStatus);
        }
        if (httpStatus <= 0) {
            noteZeroByte(url, cause);
        }
        applyBackoff(url);
    }

    /**
     * 播放器已经挂断（换清晰度、seek、流被砍尾）时的那一笔：只记不可达账，不碰封禁表也不退避。
     *
     * <p>为什么挂断了还要记：「这台机器叫不应它」是链路的性质，不是这一条流的性质——
     * 播放器掐不掐断都不影响它叫不应。真机投毒实测：五条被拒的 SYN 里只有两条记进了账，
     * 另外三条正好对上三条播放器挂断（{@code Broken pipe}），于是每次起播都要重新撞一遍
     * 同一个死节点，账本等于没写。
     */
    public synchronized void noteZeroByteFailure(String url, IOException cause) {
        noteZeroByte(url, cause);
    }

    /**
     * 一笔「零字节 + 没有 HTTP 状态」的失败，按成因分开记账：
     * DNS 查不到、连接被拒记成不可达（{@code strikes}）；连上了却在首字节超时内叫不应记成空转
     * （{@code spins}），后者证据弱一档、门槛也高一档。两类之外的成因（SSL、协议错、我们自己的
     * 「已取消」）一笔都不记——那是这一条流的事，不是这台网络的性质。
     *
     * <p>416/4xx 不在这里：节点答了状态码就说明它到得了，问题在「这个节点有没有这个文件」，
     * 那是（节点, 地址）二元的事，归封禁表的责任区分管，账本只有节点维度，记进去会毒化整个节点。
     */
    private void noteZeroByte(String url, IOException cause) {
        NodeLedger l = ledger;
        String fp = l == null ? "" : fingerprint();
        if (l == null || fp.isEmpty()) {
            return;
        }
        String host = RangeCore.hostOf(url);
        if (isLinkDead(cause)) {
            l.noteUnreachable(fp, host);
        } else if (isSilent(cause)) {
            l.noteIdleSpin(fp, host);
        }
    }

    /**
     * 记录「已经拿到字节后才中断」：这类失败不该判死节点，只退避。
     * receivedBytes 必须如实传——BanList 只封「一个字节都没拿到」的失败，传 0 等于把
     * 交付过数据的节点当空响应处理，两次就封；真机换连接风暴里镜像就是这样被打封的。
     */
    public synchronized void failurePartial(String url, long receivedBytes) {
        failurePartial(url, receivedBytes, null);
    }

    /** 同上，带上真实成因：一个字节都没拿到时，才按成因决定记哪种不可达账。 */
    public synchronized void failurePartial(String url, long receivedBytes, IOException cause) {
        bans.record(url, receivedBytes, 0);
        if (receivedBytes <= 0L) {
            noteZeroByte(url, cause);
        }
        applyBackoff(url);
    }

    /** 连 HTTP 响应头都没有，且成因是解析不到名字或被拒——这是本机链路的形状，不是节点行为的形状。 */
    private static boolean isLinkDead(IOException cause) {
        return causedBy(cause, true);
    }

    /**
     * 连上了、在首字节超时内一个字节都没给、也没给状态行：形状是「叫不应」。
     * {@code firstByteTimeoutMs} 同时是 connect 超时与等响应头的读超时，取连接前的排队不计进来
     * （{@code PieceDownloader} 三处都是先拿许可再拨号），所以这一笔确实是节点/链路没答应。
     */
    private static boolean isSilent(IOException cause) {
        return causedBy(cause, false);
    }

    private static boolean causedBy(IOException cause, boolean wantLinkDead) {
        Throwable t = cause;
        for (int depth = 0; t != null && depth < 4; depth++) {
            boolean hit = wantLinkDead
                    ? (t instanceof UnknownHostException || t instanceof ConnectException)
                    : t instanceof java.net.SocketTimeoutException;
            if (hit) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
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
        NodeLedger l = ledger;
        String fp = l == null ? "" : fingerprint();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            String url = all.get(i);
            Health h = health.get(url);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(RangeCore.hostOf(url)).append('[');
            // 不可达是本机网络层面的事实，和退避/封禁不是一回事，得独立印出来：
            // 真机上「blocked」既可能是「这条地址刚才慢」也可能是「本机根本连不上它」，
            // 混在一个标签里就看不出该不该换网络了。
            // unreachable（被拒/查不到）与 silent（叫不应）也要分开：前者该换网络，后者可能只是它此刻忙。
            String penalty = l == null ? null : l.penaltyTag(fp, RangeCore.hostOf(url));
            String tag;
            if (!bans.allowsNode(url)) {
                tag = "banned";
            } else if (h != null && h.blockedUntil > System.currentTimeMillis()) {
                tag = "blocked";
            } else if (h != null && h.lastSuccessAt > 0L) {
                // PieceDownloader.bps() 给的是 KiB/s，按 MB/s 印出来要先除一次 1024。
                tag = "healthy " + (Math.round(h.bps / 102.4D) / 10.0D) + "MB/s";
            } else {
                tag = penalty == null ? "untested" : penalty;
            }
            sb.append(tag);
            if (penalty != null && !penalty.equals(tag)) {
                sb.append(' ').append(penalty);
            }
            sb.append(']');
        }
        CdnThrottle t = throttle;
        if (t != null && t.limited()) {
            sb.append(" limited=").append(t.reason());
        }
        return sb.toString();
    }
}
