package ru.anarchy.core.ac;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Журнал дел: каждый вызов на проверку от ИИ, его улики, сообщения античита и решение модератора. */
final class CaseBook {
    static final String WAITING = "WAITING", ACCEPTED = "ACCEPTED", CHEAT = "CHEAT", CLEAN = "CLEAN",
            EXPIRED = "EXPIRED", QUIT = "QUIT", CANCELLED = "CANCELLED", NOTED = "NOTED", SELFTEST = "SELFTEST";
    private static final int KEEP = 200, LOG_KEEP = 120;

    static final class Case {
        int id, report;
        UUID uuid;
        String name, where = "", status = WAITING, moderator;
        long time, accepted, closed;
        double prob;
        boolean test, learned;
        final double[] xs = new double[ACPlayer.F];
        final List<String> reasons = new ArrayList<>(), log = new ArrayList<>();

        boolean open() { return WAITING.equals(status) || ACCEPTED.equals(status); }
    }

    static String statusName(String s) {
        return switch (s) {
            case WAITING -> "<yellow>ждёт модератора</yellow>";
            case ACCEPTED -> "<aqua>идёт проверка</aqua>";
            case CHEAT -> "<red>читер</red>";
            case CLEAN -> "<green>чист</green>";
            case EXPIRED -> "<gray>никто не принял</gray>";
            case QUIT -> "<red>вышел с проверки</red>";
            case CANCELLED -> "<gray>отменена</gray>";
            case NOTED -> "<gold>не вызван: модераторов не было</gold>";
            case SELFTEST -> "<aqua>самопроверка: вызвал бы</aqua>";
            default -> s;
        };
    }

    private final File file;
    private final Plugin plugin;
    private final List<Case> cases = new ArrayList<>();
    final Set<UUID> alerts = new HashSet<>(), testers = new HashSet<>();
    private int nextId = 1;
    private boolean dirty;
    private final Object ioLock = new Object();

    CaseBook(File file, Plugin plugin) {
        this.file = file;
        this.plugin = plugin;
        load();
    }

    Case create(UUID uuid, String name) {
        Case c = new Case();
        c.id = nextId++;
        c.uuid = uuid;
        c.name = name;
        c.time = System.currentTimeMillis();
        cases.add(c);
        for (int i = 0; cases.size() > KEEP && i < cases.size(); ) {
            if (cases.get(i).open()) i++; else cases.remove(i);
        }
        dirty = true;
        return c;
    }

    Case get(int id) {
        for (Case c : cases) if (c.id == id) return c;
        return null;
    }

    /** Открытое дело игрока (ждёт модератора или идёт проверка). */
    Case active(UUID u) {
        for (int i = cases.size() - 1; i >= 0; i--) {
            Case c = cases.get(i);
            if (c.uuid.equals(u) && c.open()) return c;
        }
        return null;
    }

    Case last(UUID u) {
        for (int i = cases.size() - 1; i >= 0; i--) if (cases.get(i).uuid.equals(u)) return cases.get(i);
        return null;
    }

    List<Case> all() { return cases; }

    List<Case> open() {
        List<Case> out = new ArrayList<>();
        for (Case c : cases) if (c.open()) out.add(c);
        return out;
    }

    void touch() { dirty = true; }

    void snapshotLog(Case c, Iterable<String> log) {
        c.log.clear();
        for (String s : log) c.log.add(s);
        while (c.log.size() > LOG_KEEP) c.log.remove(0);
        dirty = true;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, y.getInt("next-id", 1));
        for (String s : y.getStringList("alerts")) {
            try { alerts.add(UUID.fromString(s)); } catch (Exception ignored) {}
        }
        for (String s : y.getStringList("testers")) {
            try { testers.add(UUID.fromString(s)); } catch (Exception ignored) {}
        }
        ConfigurationSection sec = y.getConfigurationSection("cases");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(k);
            if (s == null) continue;
            try {
                Case c = new Case();
                c.id = Integer.parseInt(k);
                c.uuid = UUID.fromString(s.getString("uuid", ""));
                c.name = s.getString("name", "?");
                c.time = s.getLong("time");
                c.prob = s.getDouble("prob");
                c.where = s.getString("where", "");
                c.status = s.getString("status", EXPIRED);
                c.moderator = s.getString("moderator");
                c.accepted = s.getLong("accepted");
                c.closed = s.getLong("closed");
                c.report = s.getInt("report");
                c.test = s.getBoolean("test");
                c.learned = s.getBoolean("learned");
                List<Double> xs = s.getDoubleList("xs");
                for (int i = 0; i < Math.min(xs.size(), ACPlayer.F); i++) c.xs[i] = xs.get(i);
                c.reasons.addAll(s.getStringList("reasons"));
                c.log.addAll(s.getStringList("log"));
                // после перезапуска игрок уже не заморожен - незакрытые дела закрываем
                if (c.open()) { c.status = EXPIRED; if (c.closed == 0) c.closed = System.currentTimeMillis(); }
                cases.add(c);
                nextId = Math.max(nextId, c.id + 1);
            } catch (Exception ignored) {}
        }
        cases.sort((a, b) -> Integer.compare(a.id, b.id));
    }

    private String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        List<String> al = new ArrayList<>();
        for (UUID u : alerts) al.add(u.toString());
        y.set("alerts", al);
        List<String> ts = new ArrayList<>();
        for (UUID u : testers) ts.add(u.toString());
        y.set("testers", ts);
        for (Case c : cases) {
            String k = "cases." + c.id + ".";
            y.set(k + "uuid", c.uuid.toString());
            y.set(k + "name", c.name);
            y.set(k + "time", c.time);
            y.set(k + "prob", Math.round(c.prob * 1000) / 1000.0);
            y.set(k + "where", c.where);
            y.set(k + "status", c.status);
            y.set(k + "moderator", c.moderator);
            y.set(k + "accepted", c.accepted);
            y.set(k + "closed", c.closed);
            y.set(k + "report", c.report);
            if (c.test) y.set(k + "test", true);
            if (c.learned) y.set(k + "learned", true);
            List<Double> xs = new ArrayList<>();
            for (double v : c.xs) xs.add(Math.round(v * 1000) / 1000.0);
            y.set(k + "xs", xs);
            y.set(k + "reasons", c.reasons);
            y.set(k + "log", c.log);
        }
        return y.saveToString();
    }

    /** Сохраняет, если что-то менялось. async - запись файла в отдельном потоке. */
    void flush(boolean async) {
        if (!dirty) return;
        dirty = false;
        String data = serialize();
        Runnable write = () -> {
            synchronized (ioLock) {
                try { Files.writeString(file.toPath(), data, StandardCharsets.UTF_8); }
                catch (Exception e) { plugin.getLogger().warning("[AquaGuard] anticheat-cases.yml: " + e); }
            }
        };
        if (async && plugin.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        else write.run();
    }
}
