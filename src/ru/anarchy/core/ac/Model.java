package ru.anarchy.core.ac;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * ИИ-модель: логистическая регрессия по уликам. P(чит) = σ(bias + Σ w·x), где x = 1 - e^(-score/k).
 * Дообучается онлайн на решениях модераторов (чист / читер). Веса не выходят за 0.4..2.0 от стартовых,
 * чтобы несколько ошибочных решений не сломали модель.
 */
final class Model {
    static final double BIAS0 = -7.0, LR = 0.8, LR_BIAS = 0.05, BIAS_MIN = -8.5, BIAS_MAX = -5.5;

    final double[] w = new double[ACPlayer.F];
    double bias = BIAS0;
    final int[] seen = new int[ACPlayer.F], cheat = new int[ACPlayer.F], clean = new int[ACPlayer.F];
    int cheats, cleans;
    private final File file;
    private final Logger log;

    Model(File file, Logger log) {
        this.file = file;
        this.log = log;
        reset();
        load();
    }

    void reset() {
        for (Feature f : Feature.ALL) w[f.ordinal()] = f.weight;
        bias = BIAS0;
        Arrays.fill(seen, 0);
        Arrays.fill(cheat, 0);
        Arrays.fill(clean, 0);
        cheats = cleans = 0;
    }

    static double x(Feature f, double score) { return score <= 0 ? 0 : 1 - Math.exp(-score / f.k); }

    static double sigmoid(double z) { return 1 / (1 + Math.exp(-z)); }

    double z(double[] xs) {
        double z = bias;
        for (int i = 0; i < xs.length; i++) z += w[i] * xs[i];
        return z;
    }

    double prob(double[] xs) { return sigmoid(z(xs)); }

    /** Шаг обучения на одном решении модератора. rate - доверие к примеру (0..1). */
    void learn(double[] xs, boolean isCheat, double rate) {
        double g = (isCheat ? 1 : 0) - prob(xs);
        for (Feature f : Feature.ALL) {
            int i = f.ordinal();
            if (xs[i] < 0.05) continue;
            w[i] = MathX.clamp(w[i] + LR * rate * g * xs[i], f.weight * 0.4, f.weight * 2.0);
            seen[i]++;
            if (isCheat) cheat[i]++; else clean[i]++;
        }
        bias = MathX.clamp(bias + LR_BIAS * rate * g, BIAS_MIN, BIAS_MAX);
        if (isCheat) cheats++; else cleans++;
        save();
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        bias = MathX.clamp(y.getDouble("bias", BIAS0), BIAS_MIN, BIAS_MAX);
        cheats = y.getInt("cases.cheat");
        cleans = y.getInt("cases.clean");
        for (Feature f : Feature.ALL) {
            int i = f.ordinal();
            w[i] = MathX.clamp(y.getDouble("weights." + f.name(), f.weight), f.weight * 0.4, f.weight * 2.0);
            seen[i] = y.getInt("stats." + f.name() + ".seen");
            cheat[i] = y.getInt("stats." + f.name() + ".cheat");
            clean[i] = y.getInt("stats." + f.name() + ".clean");
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Веса ИИ-античита AquaGuard. Меняются сами по решениям модераторов.", "Сбросить к стартовым: /ac model reset"));
        y.set("bias", bias);
        y.set("cases.cheat", cheats);
        y.set("cases.clean", cleans);
        for (Feature f : Feature.ALL) {
            int i = f.ordinal();
            y.set("weights." + f.name(), Math.round(w[i] * 1000) / 1000.0);
            if (seen[i] > 0) {
                y.set("stats." + f.name() + ".seen", seen[i]);
                y.set("stats." + f.name() + ".cheat", cheat[i]);
                y.set("stats." + f.name() + ".clean", clean[i]);
            }
        }
        try { y.save(file); } catch (Exception e) { log.warning("[AquaGuard] anticheat-model.yml: " + e); }
    }
}
