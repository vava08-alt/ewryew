package ru.anarchy.core.ac;

import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.event.events.FlagEvent;
import org.bukkit.entity.Player;

/** Мост к GrimAC: его флаги становятся уликами для ИИ. Создаётся, только если GrimAC установлен. */
final class GrimBridge {
    private final AntiCheat ac;
    private volatile GrimAbstractAPI api;

    GrimBridge(AntiCheat ac) { this.ac = ac; }

    void hook() {
        GrimAPIProvider.getAsync().thenAccept(a -> {
            try {
                a.getEventBus().subscribe(ac.plugin(), FlagEvent.class, ev -> {
                    GrimUser u = ev.getUser();
                    AbstractCheck c = ev.getCheck();
                    if (u == null || c == null) return;
                    String verbose;
                    try { verbose = ev.getVerbose(); } catch (Throwable t) { verbose = ""; }
                    ac.grimFlag(u.getUniqueId(), c.getCheckName(), verbose, ev.isSetback(), c.isExperimental());
                });
                api = a;
                ac.info("GrimAC " + a.getGrimVersion() + " подключён: его флаги идут в ИИ");
            } catch (Throwable t) {
                ac.warn("Не удалось подписаться на флаги GrimAC: " + t);
            }
        }).exceptionally(t -> { ac.warn("GrimAC API не запустился: " + t); return null; });
    }

    void unhook() {
        GrimAbstractAPI a = api;
        if (a == null) return;
        try { a.getEventBus().unregisterAllListeners(ac.plugin()); } catch (Throwable ignored) {}
    }

    void refresh(Player p) {
        GrimAbstractAPI a = api;
        if (a == null) return;
        try {
            GrimUser u = a.getGrimUser(p);
            if (u != null) u.updatePermissions();
        } catch (Throwable ignored) {}
    }

    /** Пинг по транзакциям Grim (точнее серверного), мс; -1 если неизвестен. */
    int ping(Player p) {
        GrimAbstractAPI a = api;
        if (a == null) return -1;
        try {
            GrimUser u = a.getGrimUser(p);
            if (u == null) return -1;
            int v = u.getTransactionPing();
            return v >= 0 && v < 10_000 ? v : -1;
        } catch (Throwable t) { return -1; }
    }

    /** Шаг поворота камеры мышью по чувствительности, которую вычислил Grim; 0 если неизвестна. */
    double step(Player p) {
        GrimAbstractAPI a = api;
        if (a == null) return 0;
        try {
            GrimUser u = a.getGrimUser(p);
            if (u == null) return 0;
            double s = u.getVerticalSensitivity();
            if (!(s > 0 && s <= 2)) return 0;
            double f = s * 0.6 + 0.2;
            return f * f * f * 1.2;
        } catch (Throwable t) { return 0; }
    }
}
