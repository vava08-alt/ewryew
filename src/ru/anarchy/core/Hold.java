package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Удержание игрока без движения (капча, заморозка на проверке). Пока держим, полёт разрешён - иначе Paper кикает
 *  «Flying is not enabled» через 4 сек в воздухе, - а проверки GrimAC выключены (право grim.disabled). */
public final class Hold implements Listener {
    private record Saved(boolean allowFlight, boolean flying) {}

    private final AnarchyCore pl;
    private final Map<UUID, Set<String>> reasons = new ConcurrentHashMap<>();
    private final Map<UUID, Saved> saved = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> grim = new HashMap<>();

    public Hold(AnarchyCore pl) { this.pl = pl; }

    public boolean held(Player p) { return reasons.containsKey(p.getUniqueId()); }

    public void set(Player p, String reason, boolean on) {
        UUID u = p.getUniqueId();
        Set<String> s = reasons.get(u);
        if (on) {
            boolean first = s == null;
            if (first) reasons.put(u, s = ConcurrentHashMap.newKeySet());
            if (s.add(reason) && first) start(p);
        } else if (s != null && s.remove(reason) && s.isEmpty()) {
            reasons.remove(u);
            stop(p);
        }
    }

    private void start(Player p) {
        saved.put(p.getUniqueId(), new Saved(p.getAllowFlight(), p.isFlying()));
        if (p.isGliding()) p.setGliding(false);
        p.setAllowFlight(true);
        if (airborne(p)) p.setFlying(true);
        p.setFallDistance(0);
        grim(p, true);
    }

    private void stop(Player p) {
        Saved sv = saved.remove(p.getUniqueId());
        boolean allow = sv != null && sv.allowFlight();
        p.setFlying(allow && sv.flying());
        p.setAllowFlight(allow);
        p.setFallDistance(0);
        if (!allow && airborne(p)) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 200, 0, false, false, true));
        grim(p, false);
    }

    /** Под ногами (до 2 блоков вниз) нет твёрдого блока. По серверу, а не по клиентскому onGround. */
    private static boolean airborne(Player p) {
        Location l = p.getLocation();
        for (int dy = 1; dy <= 2; dy++) if (l.clone().subtract(0, dy - 0.99, 0).getBlock().getType().isSolid()) return false;
        return true;
    }

    private void grim(Player p, boolean off) {
        PermissionAttachment a = grim.remove(p.getUniqueId());
        if (a != null) try { p.removeAttachment(a); } catch (IllegalArgumentException ignored) {}
        if (!Bukkit.getPluginManager().isPluginEnabled("GrimAC")) return;
        if (off) grim.put(p.getUniqueId(), p.addAttachment(pl, "grim.disabled", true));
        if (pl.anticheat() != null) pl.anticheat().grimRefresh(p);
    }

    /** Способности сохраняются в файл игрока после выхода - возвращаем их до сохранения. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) { release(e.getPlayer()); }

    public void release(Player p) {
        if (reasons.remove(p.getUniqueId()) != null) stop(p);
    }

    public void releaseAll() { for (Player p : Bukkit.getOnlinePlayers()) release(p); }
}
