package ru.anarchy.core.ac;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Всё, что античит знает об игроке. Меняется только в главном потоке, кроме очереди пакетов. */
final class ACPlayer {
    static final int F = Feature.ALL.length, LOG_MAX = 300, HIST = 20;

    final UUID id;
    String name;
    long joined = System.currentTimeMillis(), quitAt;

    // ---------- очередь пакетов: Netty -> главный поток ----------
    private final ConcurrentLinkedQueue<Pk> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    volatile boolean tapped, overflow;

    void offer(Pk k) {
        if (queued.incrementAndGet() > 5000) { queued.decrementAndGet(); overflow = true; return; }
        queue.add(k);
    }

    Pk poll() {
        Pk k = queue.poll();
        if (k != null) queued.decrementAndGet();
        return k;
    }

    // ---------- улики и оценка ИИ ----------
    final double[] score = new double[F], events = new double[F], xs = new double[F];
    final int[] total = new int[F], silent = new int[F];
    final long[] lastLine = new long[F];
    double prob, rel = 1;
    int status;                        // 0 - чист, 1 - наблюдение, 2 - вызван на проверку
    long statusSince, lowSince, nextCallAt, exemptUntil, noStaffNote;
    int caseId;
    boolean active, clientChecked;
    long dbgPackets, dbgAttacks, dbgUnresolved, dbgResolved;
    String brand;
    final ArrayDeque<String> log = new ArrayDeque<>();

    void addLog(String line) {
        log.addLast(line);
        while (log.size() > LOG_MAX) log.pollFirst();
    }

    void scale(double k) {
        for (int i = 0; i < F; i++) { score[i] *= k; events[i] *= k; }
    }

    // ---------- тики клиента, взгляд и позиция из пакетов ----------
    long ctick;
    boolean tickEnd;
    float yaw, pitch;
    boolean rotKnown;
    int skipRot;
    double px, py, pz;
    boolean posKnown;
    int input;
    // накопители текущего тика клиента
    float tdy, tdp;
    boolean trot, ttp, tuse, tclickCont, tclose, tstab;
    int tswing, tattacks, ttn;
    final int[] ttargets = new int[6];
    final long[] ttargetNanos = new long[6];
    // прошлый тик
    float pdy, pdp;
    boolean prot;
    // взгляд в конце последних 4 тиков (для silent-aim)
    final float[] oy = new float[4], op = new float[4];
    final boolean[] ov = new boolean[4];
    final long[] ot = new long[4];
    boolean spikePending;
    long spikeTick = -10;
    Entity spikeTarget;
    // снапы в последних ударах
    final boolean[] snaps = new boolean[20];
    int snapIdx, snapFill;
    // одинаковые повороты и шаг сенсы
    int robTotal, robRep;
    final double[] sens = new double[50];
    int sensN, sensBad;
    // точка прицела по высоте цели
    final double[] lkPitch = new double[24], lkHd = new double[24], lkRel = new double[24], lkH = new double[24];
    int lkN;
    // тайминг ударов по игрокам
    final double[] hitGaps = new double[16];
    int hitN;
    long lastPlayerHitTick = -1000, lastAttackTick = -1000;
    // клики
    final double[] clicks = new double[64];
    int clickN, clickSince;
    long lastClickTick = -1000, lastDigTick = -1000, digEndTick = -1000;
    boolean digging;
    // спринт: в ванилле клиент шлёт смену спринта не чаще раза за тик
    boolean sprinting, thitLiving;
    int tsprint;
    long lastSprintStop = -1000, lastSprintStart = -1000, wtapTick = -1000;
    int wtapStopOff;
    final int[] wtaps = new int[12];
    int wtapN;
    // точность в бою: true - клик попал по цели
    final boolean[] acc = new boolean[60];
    int accN, accSince, accSpeedN;
    double accSpeedSum;
    // слежение прицела за целью: взгляд и направление на цель по тикам клиента
    final float[] trYaw = new float[80], trBear = new float[80];
    int trN, trSince;
    long trLastTick = -1000;
    UUID trTarget;

    void resetTick() {
        tdy = tdp = 0;
        trot = ttp = tuse = tclickCont = tclose = tstab = thitLiving = false;
        tswing = tattacks = ttn = tsprint = 0;
    }

    void resetStream() {
        rotKnown = posKnown = spikePending = false;
        spikeTarget = null;
        resetTick();
        clickN = clickSince = 0;
        robTotal = robRep = sensN = sensBad = lkN = hitN = 0;
        accN = accSince = accSpeedN = wtapN = trN = trSince = 0;
        accSpeedSum = 0;
        trTarget = null;
        java.util.Arrays.fill(ov, false);
    }

    // ---------- история позиции (лаг-компенсация, когда этот игрок - цель) ----------
    final double[] hx = new double[HIST], hy = new double[HIST], hz = new double[HIST];
    int hIdx, hFill;

    void recordPos(Location l) {
        hx[hIdx] = l.getX(); hy[hIdx] = l.getY(); hz[hIdx] = l.getZ();
        hIdx = (hIdx + 1) % HIST;
        if (hFill < HIST) hFill++;
    }

    /** Индекс позиции ago тиков назад (0 - последняя), -1 если истории нет. */
    int histIndex(int ago) {
        if (hFill == 0) return -1;
        ago = Math.min(ago, hFill - 1);
        return Math.floorMod(hIdx - 1 - ago, HIST);
    }

    boolean stableY(int ticks) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int a = 0; a < ticks; a++) {
            int i = histIndex(a);
            if (i < 0) return false;
            min = Math.min(min, hy[i]); max = Math.max(max, hy[i]);
        }
        return max - min < 0.02;
    }

    // ---------- предметы и инвентарь ----------
    boolean popPending;
    long popNanos;
    EquipmentSlot popHand;
    int totemPops, totemFast;
    long totemReactSum;
    int totemReactN;
    boolean contOpen, contBlock;
    long contOpenNanos, contFirstNanos, contLastTick;
    int contClicks;
    final double[] contGaps = new double[32];
    int contGapN;
    final ArrayDeque<Long> crystalHits = new ArrayDeque<>();
    long crystalRateFlag;

    ACPlayer(UUID id, String name) { this.id = id; this.name = name; }
}
