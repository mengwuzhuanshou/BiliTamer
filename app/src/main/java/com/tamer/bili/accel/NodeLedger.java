package com.tamer.bili.accel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 不可达节点账本：把「这台机器现在叫不应的节点」记下来，下次起播别再把它排在第一位。
 *
 * <p>为什么要跨进程持久化：真机取证（国际版 6.6.0，一次播放 183 条流）里 19 条
 * {@code out=0 disk=0 net=0}，全部来自「本机 DNS 对 mirrorbos/mirrorcos 无记录、mirrorhw
 * 拒绝连接」这类**链路侧**失败。它们只在封禁表之外退避（退避不记账、进程一换就归零），
 * 于是每次起播都要重新撞一遍同样的死节点，首字节 max 被拖到 2962 ms。
 *
 * <p>怎么知道「换网络了」——不注册监听、不轮询、不申请权限：键里的网络身份由
 * {@link NetworkId} 在被问到时现读一次本机网卡。换网络必然换网卡名或换局域网地址，
 * 身份一变旧账本自然不匹配＝全部失效；判错的误差也只是「排序 hint 晚十分钟才更新」，
 * 由 {@link #ENTRY_TTL_MS} 兜底。
 *
 * <p>记两种账，因为两种失败的证据强度不同：
 * {@link #noteUnreachable} 记「连 HTTP 响应头都没拿到、成因是 DNS 查不到或连接被拒」——
 * 这是本机链路的形状，攒 2 笔就降级；{@link #noteIdleSpin} 记「拨了、在 {@code firstByteTimeoutMs}
 * 内叫不应」——同样没字节，但拥塞的链路也会让活节点超时，所以门槛故意比前者高一档（3 笔）。
 *
 * <p>这条账只许降级排序，绝不允许删候选：指纹命中不了、账本读坏了、节点全被降级，
 * 结果都只是回到「按健康度排」，不会让流没地址可要。这也正是慢链路最坏情况下的兜底——
 * 一次全网卡顿会让 8 个候选各攒一笔空转，全被降级＝排序不变，等谁交付过字节再各自销账。
 */
public final class NodeLedger {

    /** 攒够这么多次「连 HTTP 响应头都没拿到」才降级：一次抖动不算证据。 */
    private static final int PENALTY_STRIKES = 2;

    /**
     * 攒够这么多次「叫不应」才降级。比 {@link #PENALTY_STRIKES} 多一次不是随手加的：
     * 连接被拒/DNS 无记录只有「这台机器到不了它」一种解释，而超时还可能是自己这头拥塞、
     * 节点正忙——真机一轮正常播放里就有 3 次 mirrorali 的 SocketTimeoutException，
     * 拿 2 笔的门槛去判，一个卡顿就把好节点踢出首发候选。
     */
    private static final int PENALTY_SPINS = 3;

    /** 降级有效期：到期当作没测过，换网/DNS 恢复都不需要谁去清表。 */
    private static final long ENTRY_TTL_MS = 600000L;

    /** 落盘最小间隔：失败风暴时也不许每笔都写盘。 */
    private static final long WRITE_MIN_INTERVAL_MS = 2000L;

    private static final int MAX_ENTRIES = 512;

    private static final String FILE_NAME = "cdn_node_ledger.txt";

    /**
     * 两种失败各记一笔，并且各带各的时刻。共用一个时刻就并不了账：另一进程「更晚写的一小笔」
     * 会把本地一大笔整笔顶掉（真机就是这个形状，主进程 9 笔被顶回 1 笔）；简单取大又不行，
     * 盘上那条记录本来就是自己上一轮写出去的，读回来等于自己乘自己。
     */
    private static final class Entry {
        int strikes;
        long strikesAt;
        int spins;
        long spinsAt;
    }

    private final File file;
    private final PieceDownloader.Logger logger;
    private final Map<String, Entry> entries = new HashMap<String, Entry>();
    private boolean loaded;
    private long lastWriteAt;

    /** dir 为 null（宿主目录不可写/桌面自测）时退化成进程内账本，行为与不持久化一致。 */
    public NodeLedger(File dir, PieceDownloader.Logger logger) {
        this.file = dir == null ? null : new File(dir, FILE_NAME);
        this.logger = logger;
    }

    /** 这个网络里这个节点是否已被记成「连不上」。 */
    public synchronized boolean penalized(String fingerprint, String host) {
        return inPenalty(entryFor(fingerprint, host));
    }

    /**
     * 这个节点是靠哪一笔账降下去的，供状态行区分：{@code unreachable}＝被拒/查不到，
     * {@code silent}＝叫不应。两者都算降级，但「这台机器到不了它」和「它此刻不理人」
     * 该不该换网络是两件事，印成同一个词就没法判断。
     */
    public synchronized String penaltyTag(String fingerprint, String host) {
        Entry e = entryFor(fingerprint, host);
        if (e == null) {
            return null;
        }
        if (e.strikes >= PENALTY_STRIKES && freshAt(e.strikesAt)) {
            return "unreachable";
        }
        return e.spins >= PENALTY_SPINS && freshAt(e.spinsAt) ? "silent" : null;
    }

    /**
     * 记一笔链路侧失败（DNS 无记录、连接被拒——不是节点答了 4xx）。
     * 刚跨过降级线时印一行：设备上「哪些节点被降级了」只有从这里看得见。
     */
    public synchronized void noteUnreachable(String fingerprint, String host) {
        note(fingerprint, host, true);
    }

    /** 记一笔「拨了但叫不应」：连上了、在首字节超时内一个字节没给，也没有 HTTP 状态。 */
    public synchronized void noteIdleSpin(String fingerprint, String host) {
        note(fingerprint, host, false);
    }

    private void note(String fingerprint, String host, boolean linkDead) {
        if (fingerprint == null || fingerprint.isEmpty() || host == null || host.isEmpty()) {
            return;
        }
        ensureLoaded();
        // 每记一笔都先把盘上的账并回来，再决定要不要印「刚降级」：另一进程可能也在撞同一个节点，
        // 接上它那一本账才是这台机器的总账。读盘比这一笔本身便宜得多——能走到这里说明已经白等了
        // 一整个 firstByteTimeout，而文件最多 512 行。
        mergeFromDisk();
        String key = key(fingerprint, host);
        Entry e = entries.get(key);
        if (e == null) {
            e = new Entry();
            entries.put(key, e);
        }
        boolean wasPenalized = inPenalty(e);
        long now = System.currentTimeMillis();
        if (linkDead) {
            // 那本账已经过期就从头数，不借旧账续命
            e.strikes = freshAt(e.strikesAt) ? e.strikes + 1 : 1;
            e.strikesAt = now;
        } else {
            e.spins = freshAt(e.spinsAt) ? e.spins + 1 : 1;
            e.spinsAt = now;
        }
        boolean nowPenalized = inPenalty(e);
        if (nowPenalized && !wasPenalized) {
            log("node ledger: downgrade " + host + " after " + (linkDead ? e.strikes : e.spins)
                    + (linkDead ? " unreachable tries" : " idle tries")
                    + " (net " + fingerprint + ")");
        }
        // 降级这一笔必须活过进程死亡：下次起播靠它才不再撞同一个死节点，不能被写盘节流转掉。
        persist(nowPenalized && !wasPenalized);
    }

    /** 节点交付过字节就等于「这条网络里它其实通」：账当场销掉，不等 TTL。 */
    public synchronized void noteSuccess(String fingerprint, String host) {
        if (fingerprint == null || fingerprint.isEmpty() || host == null || host.isEmpty()) {
            return;
        }
        ensureLoaded();
        String key = key(fingerprint, host);
        Entry e = entries.get(key);
        boolean wasPenalized = inPenalty(e);
        if (e == null) {
            e = new Entry();
            entries.put(key, e);
        }
        // 销账写成「0 笔 + 新时刻」而不是删掉：删掉的条目会在下一次读盘合并里被另一进程
        // 那份旧账原样复活，恢复就等于没发生。留一条 0 笔记住「这一刻它通过」才压得住。
        // 没失过账的节点也记这一笔（成本只是一个条目）：另一进程记过的降级，靠这条墓碑
        // 按时刻压掉——成功是每秒都在发生的事，不能为它去读盘。
        long now = System.currentTimeMillis();
        e.strikes = 0;
        e.strikesAt = now;
        e.spins = 0;
        e.spinsAt = now;
        if (wasPenalized) {
            log("node ledger: restore " + host + " (delivered bytes, net " + fingerprint + ")");
        }
        // 恢复同样不许只留在内存：否则进程一换，已经交付过字节的节点又被排回第一位。
        persist(wasPenalized);
    }

    /** 账面一行（供 status() 之类观测用）：只数「现在生效」的降级条目。 */
    public synchronized int penalizedCount(String fingerprint) {
        ensureLoaded();
        int n = 0;
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> me = it.next();
            if (!me.getKey().startsWith(fingerprint + "\n")) {
                continue;
            }
            Entry e = me.getValue();
            if (inPenalty(e)) {
                n++;
            } else if (!fresh(e)) {
                it.remove();
            }
        }
        return n;
    }

    private synchronized Entry entryFor(String fingerprint, String host) {
        if (fingerprint == null || fingerprint.isEmpty() || host == null || host.isEmpty()) {
            return null;
        }
        ensureLoaded();
        Entry e = entries.get(key(fingerprint, host));
        if (e == null) {
            return null;
        }
        if (!fresh(e)) {
            entries.remove(key(fingerprint, host));
            return null;
        }
        return e;
    }

    private static String key(String fingerprint, String host) {
        return fingerprint + "\n" + host;
    }

    private static boolean freshAt(long at) {
        return at > 0L && System.currentTimeMillis() - at < ENTRY_TTL_MS;
    }

    /** 条目里还有没有没过期的账——两本账都没到期才算这条记录还说话。 */
    private static boolean fresh(Entry e) {
        return e != null && (freshAt(e.strikesAt) || freshAt(e.spinsAt));
    }

    /** 任一计数器过自己的线（且那笔账还没到期）就算降级。 */
    private static boolean inPenalty(Entry e) {
        return e != null && ((e.strikes >= PENALTY_STRIKES && freshAt(e.strikesAt))
                || (e.spins >= PENALTY_SPINS && freshAt(e.spinsAt)));
    }

    /** 这个节点最近一次「撞到」或「通过」是什么时候，只用于条目超限淘汰排序。 */
    private static long lastSeen(Entry e) {
        return Math.max(e.strikesAt, e.spinsAt);
    }

    private void log(String message) {
        PieceDownloader.Logger l = logger;
        if (l == null) {
            return;
        }
        try {
            l.log(message);
        } catch (RuntimeException ignored) {
            // 账本的日志不参与控制流。
        }
    }

    /** 读坏就当空表：下次写盘会整体覆盖，绝不因为账本而让取数失败。 */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        mergeFromDisk();
    }

    private void merge(String key, Entry incoming) {
        Entry old = entries.get(key);
        if (old == null) {
            entries.put(key, incoming);
            return;
        }
        // 两本账各比各的时刻：那一笔说的是「我到这会儿一共撞了几次」，比本地新就顶掉本地，
        // 比本地旧就留着。整笔覆盖过不行（另一进程更晚写的一小笔会把本地一大笔清零），
        // 单纯取大也不行（这行盘上记录本来就是自己上一轮写出去的，读回来等于自己乘自己）。
        if (takes(old.strikes, old.strikesAt, incoming.strikes, incoming.strikesAt)) {
            old.strikes = incoming.strikes;
            old.strikesAt = incoming.strikesAt;
        }
        if (takes(old.spins, old.spinsAt, incoming.spins, incoming.spinsAt)) {
            old.spins = incoming.spins;
            old.spinsAt = incoming.spinsAt;
        }
    }

    /**
     * 时刻更新才算数；同一毫秒并列时取笔数大的那一边。并列不是抬杠：写盘前还要再读一次盘，
     * 上一毫秒自己写的那行完全可能带着更小的笔数回来，当时测到 {@code 2 笔被并回 1 笔}，
     * 于是同一个进程里又多印一条「刚降级」。
     */
    private static boolean takes(int oldCount, long oldAt, int incomingCount, long incomingAt) {
        return incomingAt > oldAt || (incomingAt == oldAt && incomingCount > oldCount);
    }

    /**
     * 落盘：平时按 {@link #WRITE_MIN_INTERVAL_MS} 合并（失败风暴不许每笔都写盘），
     * 但「刚降级」「刚恢复」这两个必须活过进程死亡的时刻强制写。
     */
    private void persist(boolean force) {
        File f = file;
        if (f == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastWriteAt < WRITE_MIN_INTERVAL_MS) {
            return;
        }
        lastWriteAt = now;
        prune();
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        mergeFromDisk();
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream out = null;
        boolean ok = false;
        try {
            out = new FileOutputStream(tmp);
            StringBuilder sb = new StringBuilder();
            Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Entry> me = it.next();
                String[] parts = me.getKey().split("\n");
                if (parts.length != 2) {
                    continue;
                }
                sb.append(parts[0]).append(' ').append(parts[1]).append(' ')
                        .append(me.getValue().strikes).append(' ').append(me.getValue().strikesAt)
                        .append(' ').append(me.getValue().spins).append(' ').append(me.getValue().spinsAt)
                        .append('\n');
            }
            out.write(sb.toString().getBytes("US-ASCII"));
            out.flush();
            ok = true;
        } catch (IOException e) {
            // 写不进去就只当没写过：账本只是排序 hint，不值得为此打扰取数。
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 已经写完或已经失败，关闭失败都不影响下一步。
                }
            }
        }
        if (!ok) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) {
                tmp.delete();
            }
        }
    }

    /** 只把盘上「更新过」的条目并进内存，不触发整表加载：另一进程记过的账不能被我这次覆盖抹掉。 */
    private void mergeFromDisk() {
        File f = file;
        if (f == null || !f.isFile()) {
            return;
        }
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "US-ASCII"));
            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.split(" ");
                // 六段是现在的格式（两种账各带自己的时刻）；四段是旧账，当时只有不可达这一本，
                // 空转按「同一时刻 0 笔」收——它既不会顶掉新写的空转账，也不会被当成永远新鲜。
                if (parts.length != 4 && parts.length != 6) {
                    continue;
                }
                Entry parsed = new Entry();
                try {
                    parsed.strikes = Integer.parseInt(parts[2]);
                    parsed.strikesAt = Long.parseLong(parts[3]);
                    if (parts.length > 4) {
                        parsed.spins = Integer.parseInt(parts[4]);
                        parsed.spinsAt = Long.parseLong(parts[5]);
                    } else {
                        parsed.spins = 0;
                        parsed.spinsAt = parsed.strikesAt;
                    }
                } catch (NumberFormatException e) {
                    continue;
                }
                // 全 0 是「恢复」那条墓碑，必须收进来；两本账都过期的条目直接丢，免得写回盘上续命。
                // 只记了一本书的条目另一个时刻就是 0（纯空转、纯被拒都合法），不能拿它当非法行。
                if (parsed.strikes < 0 || parsed.spins < 0 || !fresh(parsed)) {
                    continue;
                }
                merge(key(parts[0], parts[1]), parsed);
            }
        } catch (IOException e) {
            // 读不到就当盘上没有：下一次写会覆盖成内存里的账。
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 读过了就够了。
                }
            }
        }
    }

    private void prune() {
        List<String> dead = new ArrayList<String>();
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> me = it.next();
            if (!fresh(me.getValue())) {
                dead.add(me.getKey());
            }
        }
        for (int i = 0; i < dead.size(); i++) {
            entries.remove(dead.get(i));
        }
        if (entries.size() <= MAX_ENTRIES) {
            return;
        }
        // 超上限时按「最久没再失败」淘汰：降级只该影响最近的这台网络。
        List<Map.Entry<String, Entry>> all = new ArrayList<Map.Entry<String, Entry>>(entries.entrySet());
        Collections.sort(all, new java.util.Comparator<Map.Entry<String, Entry>>() {
            @Override public int compare(Map.Entry<String, Entry> a, Map.Entry<String, Entry> b) {
                return Long.compare(lastSeen(a.getValue()), lastSeen(b.getValue()));
            }
        });
        int drop = entries.size() - MAX_ENTRIES;
        for (int i = 0; i < drop; i++) {
            entries.remove(all.get(i).getKey());
        }
    }
}
