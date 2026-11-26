package net.wigle.wigleandroid.xlink;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import net.wigle.wigleandroid.MainActivity;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.model.NetworkType;
import net.wigle.wigleandroid.util.Logging;
import net.wigle.xlink.XLink;
import net.wigle.xlink.XLinkObservations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wigle-X: X-Link between WiGLE's database and the link (net.wigle.xlink).
 * <p>
 * On a node, every observation the database is asked to record is also queued and sent to the main phone
 * every couple of seconds while a linked phone is in range (kept while none is, up to a limit). On the main
 * phone, observations sent by the nodes are recorded into its own database, so its upload carries them.
 */
public final class XLinkBridge {

    private static final long FLUSH_MS = 2_000L;
    /** Observations per batch: about one batch every FLUSH_MS keeps well inside what a BLE link carries. */
    private static final int MAX_PER_BATCH = 300;
    /** Queued while no linked phone is in range; past this the newest are dropped (the node's own DB keeps them). */
    private static final int MAX_QUEUE = 50_000;
    /** A node counts as connected for the indicator while heard within this. */
    private static final long ACTIVE_MS = 60_000L;

    private static final ConcurrentLinkedQueue<XLinkObservations.Obs> outbox = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger outboxSize = new AtomicInteger();
    private static final AtomicLong sentCount = new AtomicLong();
    private static final AtomicLong receivedCount = new AtomicLong();
    /** Observations recorded per node (by name), this run. */
    private static final Map<String, AtomicLong> receivedByNode = new ConcurrentHashMap<>();
    private static final Set<String> receivedNetworks = ConcurrentHashMap.newKeySet();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static volatile boolean running = false;
    private static volatile boolean node = false;
    private static volatile Context app;

    private static final Runnable flush = new Runnable() {
        @Override
        public void run() {
            try {
                flushOutbox();
            } catch (final Exception ex) {
                Logging.error("X-Link flush failed: " + ex, ex);
            }
            final MainActivity m = MainActivity.getMainActivity();
            if (m != null) m.setExternalAdapterUI();
            if (running) main.postDelayed(this, FLUSH_MS);
        }
    };

    private XLinkBridge() {}

    /** Starts or stops X-Link: it runs while scanning, in a group, with sharing on. Main thread. */
    public static synchronized void update(final Context context, final boolean scanning) {
        app = context.getApplicationContext();
        final boolean want = scanning && XLink.hasGroup(app) && XLink.sharingOn(app);
        node = XLink.hasGroup(app) && !XLink.isMain(app);
        if (want && !running) {
            running = true;
            Logging.info("X-Link: starting as " + (node ? "node" : "main"));
            XLink.start(app, XLinkBridge::onPayload);
            main.removeCallbacks(flush);
            main.postDelayed(flush, FLUSH_MS);
        } else if (!want && running) {
            running = false;
            Logging.info("X-Link: stopping");
            main.removeCallbacks(flush);
            XLink.stop();
        }
        if (!XLink.hasGroup(app)) {
            outbox.clear();
            outboxSize.set(0);
        }
    }

    public static boolean isRunning() { return running; }
    public static boolean isNode() { return node; }
    /** Phones of the group heard lately (on the main: nodes connected). */
    public static int activeCount() { return running ? XLink.activeCount(ACTIVE_MS) : 0; }
    public static long sentCount() { return sentCount.get(); }
    public static long receivedCount() { return receivedCount.get(); }
    public static int queued() { return outboxSize.get(); }
    public static String status() { return running ? XLink.status() : ""; }

    /** What the main phone recorded from each node this run ("Pixel 7: 1,204"), for the indicator's detail. */
    public static String receivedSummary() {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, AtomicLong> e : receivedByNode.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append(": ").append(e.getValue().get());
        }
        return sb.toString();
    }

    // ---- node: queue what this phone records

    /** Called for every observation this phone's database is asked to record (any thread). */
    public static void offer(final Network n, final int level, final Location loc) {
        if (!running || !node || n == null || loc == null) return;
        if (outboxSize.get() >= MAX_QUEUE) return;
        final NetworkType type = n.getType();
        if (type == null) return;
        outbox.add(new XLinkObservations.Obs(type.getCode(), n.getBssid(), n.getSsid(), n.getFrequency(),
                n.getCapabilities(), level, n.getRcois(), copy(n.getBleServiceUuids()), n.getBleMfgrId(),
                n.getBleAddressType(), true, loc.getLatitude(), loc.getLongitude(), loc.getAltitude(),
                loc.getAccuracy(), loc.getTime()));
        outboxSize.incrementAndGet();
    }

    private static List<String> copy(final List<String> l) {
        return l == null || l.isEmpty() ? null : new ArrayList<>(l);
    }

    private static void flushOutbox() {
        final Context c = app;
        if (!running || !node || c == null || outbox.isEmpty()) return;
        // only while a phone of the group is linked: a batch sent with none is lost
        if (XLink.linkedCount() == 0) return;
        final List<XLinkObservations.Obs> batch = new ArrayList<>(MAX_PER_BATCH);
        XLinkObservations.Obs o;
        while (batch.size() < MAX_PER_BATCH && (o = outbox.poll()) != null) {
            outboxSize.decrementAndGet();
            batch.add(o);
        }
        if (batch.isEmpty()) return;
        XLink.send(c, XLinkObservations.encode(batch));
        sentCount.addAndGet(batch.size());
    }

    // ---- main: record what the nodes send

    /** A node's batch (link thread). */
    private static void onPayload(final String fromName, final long origin, final byte[] payload) {
        if (!running || node) return;
        final List<XLinkObservations.Obs> list = XLinkObservations.decode(payload);
        if (list.isEmpty()) return;
        main.post(() -> record(fromName, list));
    }

    private static void record(final String fromName, final List<XLinkObservations.Obs> list) {
        final MainActivity.State s = MainActivity.getStaticState();
        if (s == null || s.dbHelper == null || !running || node) return;
        int recorded = 0;
        for (final XLinkObservations.Obs o : list) {
            if (!o.hasLocation) continue;
            final NetworkType type = NetworkType.typeForCode(o.type);
            if (type == null) continue;
            final Network n = new Network(o.bssid, o.ssid, o.frequency, o.capabilities, o.level, type,
                    o.bleServiceUuids, o.bleMfgrId, null, o.bleAddressType);
            if (o.rcois != null && !o.rcois.isEmpty()) n.setRcois(o.rcois);
            final Location loc = new Location("xlink");
            loc.setLatitude(o.lat);
            loc.setLongitude(o.lon);
            loc.setAltitude(o.altitude);
            loc.setAccuracy(o.accuracy);
            loc.setTime(o.time);
            final boolean newForRun = receivedNetworks.add(type.getCode() + n.getBssid())
                    && !MainActivity.getNetworkCache().containsKey(n.getBssid());
            if (s.dbHelper.addObservation(n, loc, newForRun)) recorded++;
        }
        receivedCount.addAndGet(recorded);
        receivedByNode.computeIfAbsent(fromName, k -> new AtomicLong()).addAndGet(recorded);
    }
}
