package ru.anarchy.core.ac;

import org.bukkit.util.Vector;

import java.util.Arrays;

final class MathX {
    private MathX() {}

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }

    static double wrap(double deg) {
        deg %= 360.0;
        if (deg >= 180.0) deg -= 360.0;
        if (deg < -180.0) deg += 360.0;
        return deg;
    }

    /** Направление взгляда по yaw/pitch Minecraft (единичный вектор). */
    static Vector dir(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch), c = Math.cos(p);
        return new Vector(-c * Math.sin(y), -Math.sin(p), c * Math.cos(y));
    }

    static double angle(float y1, float p1, float y2, float p2) {
        return Math.toDegrees(Math.acos(clamp(dir(y1, p1).dot(dir(y2, p2)), -1, 1)));
    }

    /** Наименьший угол между лучом взгляда и коробкой, градусы (0 - луч проходит через коробку). */
    static double angleToBox(Vector eye, Vector look, double x0, double y0, double z0, double x1, double y1, double z1) {
        if (rayHitsBox(eye, look, x0, y0, z0, x1, y1, z1)) return 0;
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ(), lx = look.getX(), ly = look.getY(), lz = look.getZ();
        double best = 180;
        for (int i = 0; i < 3; i++) {
            double x = i == 0 ? x0 : i == 1 ? (x0 + x1) / 2 : x1;
            for (int j = 0; j < 3; j++) {
                double y = j == 0 ? y0 : j == 1 ? (y0 + y1) / 2 : y1;
                for (int k = 0; k < 3; k++) {
                    double z = k == 0 ? z0 : k == 1 ? (z0 + z1) / 2 : z1;
                    double dx = x - ex, dy = y - ey, dz = z - ez, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (len < 1e-6) return 0;
                    double a = Math.toDegrees(Math.acos(clamp((dx * lx + dy * ly + dz * lz) / len, -1, 1)));
                    if (a < best) best = a;
                }
            }
        }
        return best;
    }

    /** Луч из o по направлению d пересекает коробку впереди (метод плит). */
    static boolean rayHitsBox(Vector o, Vector d, double x0, double y0, double z0, double x1, double y1, double z1) {
        double tmin = 0, tmax = Double.MAX_VALUE;
        double[] os = {o.getX(), o.getY(), o.getZ()}, ds = {d.getX(), d.getY(), d.getZ()}, mn = {x0, y0, z0}, mx = {x1, y1, z1};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(ds[a]) < 1e-12) {
                if (os[a] < mn[a] || os[a] > mx[a]) return false;
                continue;
            }
            double t1 = (mn[a] - os[a]) / ds[a], t2 = (mx[a] - os[a]) / ds[a];
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            if (t1 > tmin) tmin = t1;
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return false;
        }
        return true;
    }

    static double mean(double[] a, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) s += a[i];
        return n == 0 ? 0 : s / n;
    }

    static double std(double[] a, int n) {
        if (n < 2) return 0;
        double m = mean(a, n), s = 0;
        for (int i = 0; i < n; i++) s += (a[i] - m) * (a[i] - m);
        return Math.sqrt(s / n);
    }

    static double median(double[] a, int n) {
        if (n == 0) return 0;
        double[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2;
    }

    /** Последние n значений кольцевого буфера в порядке записи (count - сколько всего записано). */
    static double[] lastOf(double[] ring, int count, int n) {
        n = Math.min(n, Math.min(count, ring.length));
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = ring[Math.floorMod(count - n + i, ring.length)];
        return out;
    }

    /**
     * Насколько повороты по pitch объясняются движением мыши: мышь двигает камеру шагами, кратными
     * шагу сенсы g = 1.2*(0.6*sens+0.2)^3. Возвращает долю поворотов, кратных лучшему найденному шагу
     * (1.0 - всё от мыши или данных мало для вывода).
     */
    static double sensFit(double[] v, int n, double hint) {
        if (n < 20) return 1;
        double[] s = Arrays.copyOf(v, n);
        Arrays.sort(s);
        double best = -1;
        int cand = Math.min(8, n);
        for (int j = -1; j < cand; j++) {
            double base = j < 0 ? hint : s[j];
            if (!(base > 0)) continue;
            for (int k = 1; k <= 6; k++) {
                double g = base / k;
                if (g < 0.003) break;
                double f = fit(s, n, refine(s, n, g));
                if (f > best) best = f;
                if (best >= 0.9) return best;
            }
        }
        return best < 0 ? 1 : best;
    }

    private static double refine(double[] s, int n, double g) {
        double num = 0, den = 0;
        for (int i = 0; i < n; i++) {
            double r = s[i] / g, m = Math.rint(r);
            if (m < 1 || m > 60 || Math.abs(r - m) > 0.1) continue;
            num += s[i] * m;
            den += m * m;
        }
        return den > 0 ? num / den : g;
    }

    /** Доля значений, кратных g; -1 если значений в рабочем диапазоне слишком мало. */
    private static double fit(double[] s, int n, double g) {
        int ok = 0, used = 0;
        for (int i = 0; i < n; i++) {
            double r = s[i] / g;
            if (r > 400) continue;
            used++;
            if (Math.abs(r - Math.rint(r)) < 0.06) ok++;
        }
        return used < 15 ? -1 : ok / (double) used;
    }
}
