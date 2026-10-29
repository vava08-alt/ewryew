package ru.anarchy.core.ac;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * Разбор пакетов игрока по тикам клиента (граница тика - ServerboundClientTickEndPacket).
 * В одном тике клиент сначала шлёт удары и взмахи, потом пакет движения с поворотом, поэтому
 * удары проверяются в конце тика с тем поворотом, которым они были сделаны.
 */
final class Processor {
    private final AntiCheat ac;
    private final ItemChecks items;

    Processor(AntiCheat ac, ItemChecks items) {
        this.ac = ac;
        this.items = items;
    }

    private static String fmt(String f, Object... a) { return String.format(Locale.ROOT, f, a); }

    void drain(Player p, ACPlayer d, boolean active) {
        if (d.overflow) { d.overflow = false; d.resetStream(); }
        Pk k;
        int guard = 0;
        while (guard++ < 8000 && (k = d.poll()) != null) handle(p, d, k, active);
    }

    private void handle(Player p, ACPlayer d, Pk k, boolean active) {
        d.dbgPackets++;
        switch (k.type) {
            case Pk.MOVE -> { move(d, k); if (!d.tickEnd) endTick(p, d, active); }
            case Pk.TICK -> { d.tickEnd = true; endTick(p, d, active); }
            case Pk.SWING -> { if (k.a == 0) d.tswing++; }
            case Pk.ATTACK -> attackPacket(d, k);
            case Pk.INTERACT, Pk.USE, Pk.USE_ON -> d.tuse = true;
            case Pk.DIG -> {
                if (k.a == Pk.DIG_START) { d.digging = true; d.lastDigTick = d.ctick; }
                else { d.digging = false; d.digEndTick = d.ctick; }
            }
            case Pk.CLICK -> { if (k.a != 0) d.tclickCont = true; if (active) items.click(p, d, k); }
            case Pk.CLOSE -> d.tclose = true;
            case Pk.SLOT -> { if (active) items.slot(p, d, k); }
            case Pk.SWAP -> { if (active) items.swap(p, d, k); }
            case Pk.INPUT -> d.input = k.a;
            case Pk.TP -> { d.skipRot = 1; d.ttp = true; }
            case Pk.STAB -> d.tstab = true;
            case Pk.SPRINT -> sprint(p, d, k, active);
            default -> {}
        }
    }

    /** Смена спринта. Ванильный клиент шлёт её раз за тик; бот-ресет перед ударом даёт одинаковый тайминг каждый раз. */
    private void sprint(Player p, ACPlayer d, Pk k, boolean active) {
        long tick = d.ctick + 1;   // этот тик получит номер ctick + 1 в endTick
        d.tsprint++;
        if (k.a == 0) { d.sprinting = false; d.lastSprintStop = tick; return; }
        d.sprinting = true;
        d.lastSprintStart = tick;
        if (!active || d.wtapTick < 0 || tick - d.wtapTick < 1 || tick - d.wtapTick > 4) return;
        d.wtaps[d.wtapN % d.wtaps.length] = d.wtapStopOff * 10 + (int) (tick - d.wtapTick);
        d.wtapN++;
        d.wtapTick = -1000;
        if (d.wtapN < d.wtaps.length) return;
        int best = 0, code = 0;
        for (int a : d.wtaps) {
            int c = 0;
            for (int b : d.wtaps) if (a == b) c++;
            if (c > best) { best = c; code = a; }
        }
        if (best >= 11) ac.flag(p, d, Feature.AURA_SPRINT, 0.3, fmt("%d из 12 спринт-ресетов с одним таймингом: стоп за %d тик. до удара, снова спринт через %d", best, code / 10, code % 10));
        d.wtapN = 0;
    }

    private static void attackPacket(ACPlayer d, Pk k) {
        d.tattacks++;
        d.dbgAttacks++;
        for (int i = 0; i < d.ttn; i++) if (d.ttargets[i] == k.a) return;
        if (d.ttn < d.ttargets.length) {
            d.ttargets[d.ttn] = k.a;
            d.ttargetNanos[d.ttn] = k.nanos;
            d.ttn++;
        }
    }

    private static void move(ACPlayer d, Pk k) {
        if (k.pos) { d.px = k.x; d.py = k.y; d.pz = k.z; d.posKnown = true; }
        if (!k.rot) return;
        if (!d.rotKnown) { d.yaw = k.yaw; d.pitch = k.pitch; d.rotKnown = true; d.ttp = true; return; }
        float dy = k.yaw - d.yaw, dp = k.pitch - d.pitch;
        d.yaw = k.yaw;
        d.pitch = k.pitch;
        if (d.skipRot > 0) { d.skipRot--; d.ttp = true; return; }   // поворот от телепорта сервера, не от мыши
        d.tdy += dy;
        d.tdp += dp;
        d.trot = true;
    }

    private void endTick(Player p, ACPlayer d, boolean active) {
        d.ctick++;
        boolean valid = d.rotKnown && !d.ttp && !p.isInsideVehicle();
        boolean turned = valid && d.trot;
        float dy = turned ? (float) MathX.wrap(d.tdy) : 0f, dp = turned ? d.tdp : 0f;
        int oi = (int) (d.ctick & 3);
        d.oy[oi] = d.yaw; d.op[oi] = d.pitch; d.ov[oi] = valid; d.ot[oi] = d.ctick;
        if (active && d.rotKnown) {
            if (d.spikePending && d.ctick == d.spikeTick + 1) spike(p, d);
            if (d.ctick > d.spikeTick + 1) { d.spikePending = false; d.spikeTarget = null; }
            if (turned && d.ctick - d.lastAttackTick <= 60) rotation(p, d, dy, dp);
            if (d.tickEnd && d.tsprint >= 2)
                ac.flag(p, d, Feature.AURA_SPRINT, 0.45, fmt("спринт переключён %d раза за один тик (пакетный спринт; ванильный клиент - не больше раза)", d.tsprint));
            if (d.tattacks > 0) attacks(p, d, dy, dp, turned);
            clicks(p, d);
            track(p, d, valid);
        }
        d.pdy = dy;
        d.pdp = dp;
        d.prot = turned;
        d.resetTick();
    }

    // ============================== повороты ==============================
    private void rotation(Player p, ACPlayer d, float dy, float dp) {
        if (Math.abs(dy) >= 1.0f && Math.abs(dp) >= 0.1f) {
            d.robTotal++;
            float tolY = Math.max(1e-4f, Math.ulp(d.yaw) * 4), tolP = Math.max(1e-4f, Math.ulp(d.pitch) * 4);
            if (d.prot && Math.abs(dy - d.pdy) <= tolY && Math.abs(dp - d.pdp) <= tolP) d.robRep++;
            if (d.robTotal >= 50) {
                double r = d.robRep / (double) d.robTotal;
                if (r >= 0.25) ac.flag(p, d, Feature.AIM_ROBOTIC, 0.5, fmt("%d из %d поворотов головы в бою в точности повторяют предыдущий", d.robRep, d.robTotal));
                else if (r >= 0.12) ac.flag(p, d, Feature.AIM_ROBOTIC, 0.25, fmt("%d из %d поворотов головы в точности повторяют предыдущий", d.robRep, d.robTotal));
                d.robTotal = d.robRep = 0;
            }
        }
        double ap = Math.abs(dp);
        if (ap >= 0.008 && ap <= 20 && Math.abs(d.pitch) < 85) {
            d.sens[d.sensN++] = ap;
            if (d.sensN == d.sens.length) {
                double fit = MathX.sensFit(d.sens, d.sensN, ac.grimStep(p));
                if (fit < 0.45) {
                    if (++d.sensBad >= 2) {
                        ac.flag(p, d, Feature.AIM_SENS, 0.35, fmt("лишь %.0f%% поворотов кратны шагу мыши - камеру крутит программа", fit * 100));
                        d.sensBad = 0;
                    }
                } else d.sensBad = 0;
                d.sensN = 0;
            }
        }
    }

    /** Silent-aim: взгляд за 1 тик прыгает на цель и следующим тиком возвращается обратно. */
    private void spike(Player p, ACPlayer d) {
        d.spikePending = false;
        Entity t = d.spikeTarget;
        d.spikeTarget = null;
        if (t == null || !t.isValid() || !t.getWorld().equals(p.getWorld())) return;
        Vector eye = eye(p, d);
        BoundingBox bb = t.getBoundingBox();
        if (eye.distance(bb.getCenter()) < 1.8) return;
        for (long s = d.spikeTick - 2; s <= d.spikeTick - 1; s++) {
            int a = (int) (s & 3), b = (int) ((s + 1) & 3), c = (int) ((s + 2) & 3);
            if (d.ot[a] != s || d.ot[b] != s + 1 || d.ot[c] != s + 2 || !d.ov[a] || !d.ov[b] || !d.ov[c]) continue;
            double ab = MathX.angle(d.oy[a], d.op[a], d.oy[b], d.op[b]);
            double bc = MathX.angle(d.oy[b], d.op[b], d.oy[c], d.op[c]);
            double ca = MathX.angle(d.oy[a], d.op[a], d.oy[c], d.op[c]);
            if (ab < 20 || bc < 20 || ca > 0.2 * Math.min(ab, bc)) continue;
            double e = 0.2;
            double aim = MathX.angleToBox(eye, MathX.dir(d.oy[b], d.op[b]), bb.getMinX() - e, bb.getMinY() - e, bb.getMinZ() - e, bb.getMaxX() + e, bb.getMaxY() + e, bb.getMaxZ() + e);
            if (aim > 6) continue;
            ac.flag(p, d, Feature.AIM_SPIKE, 0.5, fmt("взгляд рывком %.0f° на цель и %.0f° обратно за 2 тика", ab, bc));
            return;
        }
    }

    // ============================== удары ==============================
    private void attacks(Player p, ACPlayer d, float dy, float dp, boolean turned) {
        World w = p.getWorld();
        Vector eye = eye(p, d), look = MathX.dir(d.yaw, d.pitch);
        int delay = ac.viewDelay(p, d);
        int living = 0;
        for (int i = 0; i < d.ttn; i++) {
            Entity t = ac.entity(p, d.ttargets[i]);
            if (t == null) { d.dbgUnresolved++; items.unresolved(p, d.ttargets[i], d.ttargetNanos[i]); continue; }
            d.dbgResolved++;
            if (t instanceof EnderCrystal) {
                items.crystalHit(p, d, t, d.ttargetNanos[i], minAngle(eye, look, t, null, 0));
                if (turned && !d.spikePending) { d.spikePending = true; d.spikeTick = d.ctick; d.spikeTarget = t; }
                continue;
            }
            if (!(t instanceof LivingEntity) || t.isDead()) continue;
            living++;
            ACPlayer td = t instanceof Player tp ? ac.peek(tp.getUniqueId()) : null;
            double ang = minAngle(eye, look, t, td, delay + 3);
            double dist = eye.distance(t.getBoundingBox().getCenter());
            if (dist >= 1.0 && ang > 25)
                ac.flag(p, d, Feature.AURA_FOV, 0.25 + 0.5 * Math.min(1, (ang - 25) / 65), fmt("удар по %s под углом %.0f° от прицела (%.1f бл.)", name(t), ang, dist));
            boolean snap = false;
            double turn = 0;
            if (turned && ang <= 3) {
                float by = d.yaw - dy, bp = d.pitch - dp;
                turn = MathX.angle(by, bp, d.yaw, d.pitch);
                if (turn >= 30 && minAngle(eye, MathX.dir(by, bp), t, td, delay + 3) >= 25) snap = true;
            }
            d.snaps[d.snapIdx] = snap;
            d.snapIdx = (d.snapIdx + 1) % d.snaps.length;
            if (d.snapFill < d.snaps.length) d.snapFill++;
            if (snap) {
                int n = 0;
                for (int j = 0; j < d.snapFill; j++) if (d.snaps[j]) n++;
                double sev = 0.25 + (n >= 4 && n >= 0.3 * d.snapFill ? 0.35 : 0);
                ac.flag(p, d, Feature.AIM_SNAP, sev, fmt("доворот %.0f° за 1 тик точно на %s (снапов %d из %d ударов)", turn, name(t), n, d.snapFill));
            }
            if (t instanceof Player) {
                if (d.lastPlayerHitTick != d.ctick && !d.sprinting && d.lastSprintStop > d.lastSprintStart
                        && d.ctick - d.lastSprintStop >= 1 && d.ctick - d.lastSprintStop <= 3) {
                    d.wtapTick = d.ctick;
                    d.wtapStopOff = (int) (d.ctick - d.lastSprintStop);
                }
                timing(p, d);
                d.trTarget = t.getUniqueId();
                if (td != null) {
                    lock(p, d, eye, t, td, delay);
                    int i0 = td.histIndex(0), i4 = td.histIndex(4);
                    if (i0 >= 0 && i4 >= 0) { d.accSpeedSum += Math.hypot(td.hx[i0] - td.hx[i4], td.hz[i0] - td.hz[i4]) / 4; d.accSpeedN++; }
                }
            }
            if (dist >= 1.2 && throughWall(w, eye, t, td, delay))
                ac.flag(p, d, Feature.AURA_WALL, 0.35, fmt("удар по %s, которого не видно из-за блоков (%.1f бл.)", name(t), dist));
            if (turned && !d.spikePending) { d.spikePending = true; d.spikeTick = d.ctick; d.spikeTarget = t; }
        }
        if (living >= 2 && d.tickEnd)
            ac.flag(p, d, Feature.AURA_MULTI, 0.6 * (living - 1), fmt("удары по %d разным целям за один тик (в ванилле - максимум одна)", living));
        if (living > 0) {
            if (d.tickEnd && d.tswing == 0 && !d.tstab) ac.flag(p, d, Feature.AURA_NOSWING, 0.35, "удар без взмаха рукой (клиент не отправил анимацию)");
            if (d.tclickCont && !d.tclose) ac.flag(p, d, Feature.AURA_GUI, 0.4, "удар в тот же тик, что и клик в открытом сундуке");
            else if (d.contOpen && !d.tclose && (System.nanoTime() - d.contOpenNanos) / 1e6 > ac.rtt(p, d) + 300)
                ac.flag(p, d, Feature.AURA_GUI, 0.4, "удар при открытом сундуке/меню");
            d.lastAttackTick = d.ctick;
            d.thitLiving = true;
        }
    }

    /** Удары по игрокам точно по откату оружия с почти нулевым разбросом. */
    private void timing(Player p, ACPlayer d) {
        long gap = d.ctick - d.lastPlayerHitTick;
        d.lastPlayerHitTick = d.ctick;
        if (gap < 2 || gap > 40) return;
        d.hitGaps[d.hitN % d.hitGaps.length] = gap;
        d.hitN++;
        if (d.hitN < 12) return;
        double[] g = MathX.lastOf(d.hitGaps, d.hitN, 12);
        double mean = MathX.mean(g, g.length), sd = MathX.std(g, g.length);
        AttributeInstance as = p.getAttribute(Attribute.ATTACK_SPEED);
        double cd = as == null ? 12.5 : 20.0 / Math.max(0.25, as.getValue());
        if (sd <= 0.45 && Math.abs(mean - cd) <= 1.5) {
            ac.flag(p, d, Feature.AURA_TIMING, 0.4, fmt("12 ударов подряд ровно по откату оружия: каждые %.1f тика, разброс %.2f", mean, sd));
            d.hitN = 0;
        } else if (sd <= 0.3) {
            ac.flag(p, d, Feature.AURA_TIMING, 0.3, fmt("12 ударов подряд с одинаковым интервалом %.1f тика", mean));
            d.hitN = 0;
        } else if (d.hitN >= 16) {
            // бот бьёт, как только откат готов (с небольшим случайным разбросом), человек - то раньше, то сильно позже
            double[] g16 = MathX.lastOf(d.hitGaps, d.hitN, 16);
            int in = 0;
            for (double v : g16) if (v >= cd * 0.85 && v <= cd + 1.5) in++;
            if (in >= 14 && MathX.std(g16, 16) <= 1.2) {
                ac.flag(p, d, Feature.AURA_TIMING, 0.35, fmt("%d из 16 ударов ровно по откату оружия (откат %.1f тика, средний интервал %.1f)", in, cd, MathX.mean(g16, 16)));
                d.hitN = 0;
            }
        }
    }

    /**
     * Наводка повторяет движение цели: направление взгляда совпадает с направлением на цель,
     * сдвинутым на постоянную задержку. У человека прицел отстаёт, перелетает и дёргается.
     */
    private void track(Player p, ACPlayer d, boolean valid) {
        if (d.trTarget == null || !valid || d.ctick - d.lastPlayerHitTick > 40) { d.trN = 0; d.trSince = 0; return; }
        Player t = Bukkit.getPlayer(d.trTarget);
        ACPlayer td = t == null ? null : ac.peek(t.getUniqueId());
        if (td == null || !t.getWorld().equals(p.getWorld())) { d.trN = 0; return; }
        int i0 = td.histIndex(0);
        if (i0 < 0) return;
        Vector eye = eye(p, d);
        double dx = td.hx[i0] - eye.getX(), dz = td.hz[i0] - eye.getZ(), dist = Math.hypot(dx, dz);
        if (dist < 1.2 || dist > 6) { d.trN = 0; return; }
        if (d.trLastTick != d.ctick - 1) { d.trN = 0; d.trSince = 0; }
        d.trLastTick = d.ctick;
        int len = d.trYaw.length, slot = d.trN % len;
        d.trYaw[slot] = d.yaw;
        d.trBear[slot] = (float) Math.toDegrees(Math.atan2(-dx, dz));
        d.trN++;
        d.trSince++;
        if (d.trN < len || d.trSince < len / 2) return;
        d.trSince = 0;
        double[] yaw = new double[len], bear = new double[len];
        for (int k = 0; k < len; k++) {
            int idx = Math.floorMod(d.trN - len + k, len);
            yaw[k] = d.trYaw[idx];
            bear[k] = d.trBear[idx];
        }
        double[] rel = new double[len];
        for (int k = 0; k < len; k++) rel[k] = MathX.wrap(bear[k] - bear[0]);
        double spread = MathX.std(rel, len);
        if (spread < 20) return;
        double best = Double.MAX_VALUE;
        int bestLag = 0;
        for (int lag = 0; lag <= 14; lag++) {
            int n = len - lag;
            double[] e = new double[n];
            for (int k = lag; k < len; k++) e[k - lag] = MathX.wrap(yaw[k] - bear[k - lag]);
            double sd = MathX.std(e, n);
            if (sd < best) { best = sd; bestLag = lag; }
        }
        if (best < 2.0)
            ac.flag(p, d, Feature.AIM_TRACK, 0.4, fmt("4 сек прицел повторяет движение цели с задержкой %d тик.: отклонение %.1f° при разбросе цели %.0f°", bestLag, best, spread));
        else if (best < 3.0 && spread >= 40)
            ac.flag(p, d, Feature.AIM_TRACK, 0.15, fmt("прицел почти точно повторяет движение цели: отклонение %.1f° при разбросе %.0f°", best, spread));
    }

    /**
     * Прицел "приклеен" к одной точке цели: бот держит одну и ту же высоту на теле цели при любой дистанции
     * (наклон камеры подстраивается под расстояние), а человек обычно держит наклон, и точка гуляет.
     */
    private void lock(Player p, ACPlayer d, Vector eye, Entity t, ACPlayer td, int delay) {
        int hi = td.histIndex(delay);
        if (hi < 0 || !td.stableY(6)) return;
        double tx = td.hx[hi], ty = td.hy[hi], tz = td.hz[hi];
        double hd = Math.hypot(tx - eye.getX(), tz - eye.getZ());
        if (hd < 1.5 || hd > 4.5) return;
        double h = t.getBoundingBox().getHeight();
        double rel = eye.getY() - ty;
        double r = (rel - Math.tan(Math.toRadians(d.pitch)) * hd) / h;
        if (r < -0.05 || r > 1.1) return;
        int n = d.lkN;
        d.lkPitch[n] = d.pitch; d.lkHd[n] = hd; d.lkRel[n] = rel; d.lkH[n] = h;
        d.lkN = n + 1;
        if (d.lkN < d.lkPitch.length) return;
        double rs = 0;
        for (int i = 0; i < d.lkN; i++) rs += (d.lkRel[i] - Math.tan(Math.toRadians(d.lkPitch[i])) * d.lkHd[i]) / d.lkH[i];
        double rMean = rs / d.lkN;
        double[] res = new double[d.lkN];
        for (int i = 0; i < d.lkN; i++) res[i] = d.lkPitch[i] - Math.toDegrees(Math.atan((d.lkRel[i] - rMean * d.lkH[i]) / d.lkHd[i]));
        double bot = MathX.std(res, d.lkN), human = MathX.std(d.lkPitch, d.lkN);
        if (bot < 0.8 && human > 3.0)
            ac.flag(p, d, Feature.AIM_LOCK, 0.45, fmt("прицел держится на %.0f%% высоты цели (откл. %.2f°), хотя наклон менялся на %.1f°", rMean * 100, bot, human));
        else if (bot < 1.2 && human > 4.5)
            ac.flag(p, d, Feature.AIM_LOCK, 0.2, fmt("прицел почти всегда на %.0f%% высоты цели (откл. %.2f°)", rMean * 100, bot));
        d.lkN = 0;
    }

    /** Цель не видна ни в одной точке хитбокса ни в одном из недавних положений (с учётом лага). */
    private static boolean throughWall(World w, Vector eye, Entity t, ACPlayer td, int delay) {
        BoundingBox bb = t.getBoundingBox();
        double hw = bb.getWidthX() / 2, h = bb.getHeight();
        if (visible(w, eye, bb.getCenterX(), bb.getMinY(), bb.getCenterZ(), hw, h)) return false;
        if (td != null) for (int ago = 1; ago <= delay + 3; ago += 2) {
            int i = td.histIndex(ago);
            if (i < 0) break;
            if (visible(w, eye, td.hx[i], td.hy[i], td.hz[i], hw, h)) return false;
        }
        return true;
    }

    /** Хотя бы один луч от глаз до точки хитбокса не упирается в блок (по контуру блока, как прицел клиента). */
    private static boolean visible(World w, Vector eye, double x, double minY, double z, double hw, double h) {
        double s = hw * 0.8, mid = minY + h * 0.5;
        double[][] pts = {{x, mid, z}, {x, minY + h - 0.1, z}, {x, minY + 0.15, z}, {x + s, mid, z}, {x - s, mid, z}, {x, mid, z + s}, {x, mid, z - s}};
        Location from = new Location(w, eye.getX(), eye.getY(), eye.getZ());
        for (double[] pt : pts) {
            Vector to = new Vector(pt[0] - eye.getX(), pt[1] - eye.getY(), pt[2] - eye.getZ());
            double len = to.length();
            if (len < 0.3) return true;
            RayTraceResult r = w.rayTraceBlocks(from, to.multiply(1 / len), len, FluidCollisionMode.NEVER, false);
            if (r == null || r.getHitPosition().distance(eye) >= len - 0.15) return true;
        }
        return false;
    }

    // ============================== клики ==============================
    private void clicks(Player p, ACPlayer d) {
        if (d.tswing == 0 || d.tuse || d.tstab) return;
        if (d.digging) {
            if (d.ctick - d.lastDigTick <= 10 || lookingAtBlock(p, d)) return;
            d.digging = false;   // START без STOP после мгновенной ломки блока
        }
        if (d.ctick - d.digEndTick <= 6) return;
        if (d.ctick - d.lastPlayerHitTick <= 60) accuracy(p, d, Math.min(d.tswing, 4));
        long gap = d.ctick - d.lastClickTick;
        d.lastClickTick = d.ctick;
        if (gap > 8) { d.clickN = 0; d.clickSince = 0; gap = -1; }
        int n = Math.min(d.tswing, 4);
        for (int i = 0; i < n; i++) {
            if (i == 0 && gap < 0) continue;
            d.clicks[d.clickN % d.clicks.length] = i == 0 ? gap : 0;
            d.clickN++;
            d.clickSince++;
        }
        if (d.clickN < 40 || d.clickSince < 20) return;
        d.clickSince = 0;
        double[] g = MathX.lastOf(d.clicks, d.clickN, 40);
        double sum = 0;
        for (double v : g) sum += v;
        double cps = sum > 0 ? g.length * 20.0 / sum : 99, sd = MathX.std(g, g.length);
        if (cps >= 8 && sd < 0.25) {
            ac.flag(p, d, Feature.CLICKER, 0.5, fmt("40 кликов с неизменным интервалом: %.1f CPS, разброс %.2f тика", cps, sd));
            return;
        }
        if (cps >= 8 && d.clickN >= 64 && sd >= 0.3) {
            double[] h = MathX.lastOf(d.clicks, d.clickN, 64);
            double best = 0;
            int bestLag = 0;
            for (int lag = 2; lag <= 16; lag++) {
                int same = 0;
                for (int i = 0; i + lag < h.length; i++) if (h[i] == h[i + lag]) same++;
                double r = same / (double) (h.length - lag);
                if (r > best) { best = r; bestLag = lag; }
            }
            if (best >= 0.93) {
                ac.flag(p, d, Feature.CLICKER, 0.4, fmt("клики повторяют один шаблон с периодом %d (%.0f%% совпадений), %.1f CPS", bestLag, best * 100, cps));
                return;
            }
        }
        if (cps >= 22 && sd < 0.55) ac.flag(p, d, Feature.CLICKER, 0.2, fmt("стабильные %.0f CPS без пауз", cps));
    }

    /** В бою человек промахивается (каждый промах сбрасывает откат), аура бьёт только когда цель в прицеле. */
    private void accuracy(Player p, ACPlayer d, int n) {
        for (int i = 0; i < n; i++) {
            d.acc[d.accN % d.acc.length] = i == 0 && d.thitLiving;
            d.accN++;
            d.accSince++;
        }
        if (d.accN < 40 || d.accSince < 20) return;
        d.accSince = 0;
        double speed = d.accSpeedN == 0 ? 0 : d.accSpeedSum / d.accSpeedN;
        d.accSpeedSum = 0;
        d.accSpeedN = 0;
        if (speed < 0.1) return;   // по стоящей цели промахнуться трудно и человеку
        int miss40 = 0, miss60 = 0;
        for (int i = 0; i < Math.min(60, d.accN); i++) {
            if (d.acc[Math.floorMod(d.accN - 1 - i, d.acc.length)]) continue;
            if (i < 40) miss40++;
            miss60++;
        }
        if (d.accN >= 60 && miss60 == 0)
            ac.flag(p, d, Feature.AURA_ACCURACY, 0.45, fmt("60 кликов в бою подряд - все в цель, ни одного промаха (цель двигалась %.2f бл/тик)", speed));
        else if (miss40 <= 1)
            ac.flag(p, d, Feature.AURA_ACCURACY, 0.3, fmt("%d из 40 кликов в бою попали в цель (цель двигалась %.2f бл/тик)", 40 - miss40, speed));
    }

    private static boolean lookingAtBlock(Player p, ACPlayer d) {
        Vector eye = eye(p, d);
        return p.getWorld().rayTraceBlocks(new Location(p.getWorld(), eye.getX(), eye.getY(), eye.getZ()), MathX.dir(d.yaw, d.pitch), 5.5, FluidCollisionMode.NEVER, true) != null;
    }

    // ============================== геометрия ==============================
    private static Vector eye(Player p, ACPlayer d) {
        Location l = p.getLocation();
        double eh = p.getEyeHeight();
        if (d.posKnown && Math.abs(d.px - l.getX()) < 6 && Math.abs(d.py - l.getY()) < 6 && Math.abs(d.pz - l.getZ()) < 6)
            return new Vector(d.px, d.py + eh, d.pz);
        return new Vector(l.getX(), l.getY() + eh, l.getZ());
    }

    /** Наименьший угол от прицела до хитбокса цели с учётом того, где цель была на экране игрока (лаг). */
    private static double minAngle(Vector eye, Vector look, Entity t, ACPlayer td, int maxAgo) {
        BoundingBox bb = t.getBoundingBox();
        double e = td != null ? 0.2 : 0.35;
        double best = MathX.angleToBox(eye, look, bb.getMinX() - e, bb.getMinY() - e, bb.getMinZ() - e, bb.getMaxX() + e, bb.getMaxY() + e, bb.getMaxZ() + e);
        if (td == null || best == 0) return best;
        double hw = bb.getWidthX() / 2 + e, h = bb.getHeight();
        for (int ago = 0; ago <= maxAgo; ago++) {
            int i = td.histIndex(ago);
            if (i < 0) break;
            double a = MathX.angleToBox(eye, look, td.hx[i] - hw, td.hy[i] - e, td.hz[i] - hw, td.hx[i] + hw, td.hy[i] + h + e, td.hz[i] + hw);
            if (a < best) {
                best = a;
                if (best == 0) break;
            }
        }
        return best;
    }

    private static String name(Entity t) {
        return t instanceof Player p ? p.getName() : t.getType().name().toLowerCase(Locale.ROOT);
    }
}
