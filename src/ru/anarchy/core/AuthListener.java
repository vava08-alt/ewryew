package ru.anarchy.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static ru.anarchy.core.AnarchyCore.mm;

public final class AuthListener implements Listener {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Set<String> ALLOWED = Set.of("login", "l", "log", "register", "reg");
    private final AnarchyCore pl;

    public AuthListener(AnarchyCore pl) { this.pl = pl; }

    private boolean blocked(Player p) {
        PlayerData d = pl.store().get(p.getUniqueId());
        return d == null || !d.authed;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        String name = e.getName();
        if (!NAME.matcher(name).matches()) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, mm("<red>Недопустимый ник. Разрешены A-Z, 0-9, _ (3-16 символов)"));
            return;
        }
        // защита от кика владельца ника повторным заходом
        for (Player o : Bukkit.getOnlinePlayers())
            if (o.getName().equalsIgnoreCase(name)) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, mm("<red>Игрок с таким ником уже на сервере"));
                return;
            }
        PlayerData d = pl.store().byName(name);
        if (d != null && !d.name.equals(name) && d.passHash != null) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    mm("<red>Этот ник зарегистрирован как <white>" + d.name + "</white>. Зайди с правильным регистром букв."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        e.joinMessage(null);
        PlayerData d = pl.data(p);
        d.name = p.getName();
        d.authed = false;
        d.combatUntil = 0;
        d.ghost = false;
        String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
        long session = pl.getConfig().getLong("auth.session-minutes", 10) * 60_000;
        if (d.passHash != null && !pl.sharedIp(ip) && ip.equals(d.lastIp) && System.currentTimeMillis() - d.lastLogin < session) {   // через туннель у всех один IP - сессию не восстанавливаем
            pl.msg(p, "<gray>Сессия восстановлена.");
            pl.onAuthed(p, false);
            return;
        }
        p.setGlowing(false);
        if (pl.antibot().needsCaptcha(p)) return; // сначала капча, регистрация - после неё
        int timeout = pl.getConfig().getInt("auth.timeout-seconds", 60);
        if (d.passHash == null) {
            pl.msg(p, "<yellow>Зарегистрируйся: <white>/reg <пароль> <пароль>");
            p.showTitle(net.kyori.adventure.title.Title.title(mm(pl.getConfig().getString("visual.title", "AquaAnarchy")), mm("<gray>Регистрация: <aqua>/reg <пароль> <пароль>")));
        } else {
            pl.msg(p, "<yellow>Войди в аккаунт: <white>/login <пароль>");
            p.showTitle(net.kyori.adventure.title.Title.title(mm(pl.getConfig().getString("visual.title", "AquaAnarchy")), mm("<gray>Вход: <aqua>/login <пароль>")));
        }
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (p.isOnline() && blocked(p)) p.kick(mm("<red>Время на вход истекло"));
        }, timeout * 20L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (pl.antibot().pending(e.getPlayer())) return;
        if (!blocked(e.getPlayer())) {
            if (e.hasChangedPosition()) pl.store().get(e.getPlayer().getUniqueId()).lastMove = System.currentTimeMillis();
            return;
        }
        if (e.hasChangedPosition()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        if (e.isCancelled()) return;
        if (blocked(e.getPlayer())) { e.setCancelled(true); pl.msg(e.getPlayer(), "<red>Сначала войди: /login или /reg"); }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (e.isCancelled() || !blocked(e.getPlayer())) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        if (!ALLOWED.contains(cmd)) { e.setCancelled(true); pl.msg(e.getPlayer(), "<red>Сначала войди: /login или /reg"); }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && blocked(p)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInv(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && blocked(p) && !pl.antibot().pending(p)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvOpen(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p && blocked(p) && !pl.antibot().pending(p)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFood(FoodLevelChangeEvent e) { if (e.getEntity() instanceof Player p && blocked(p)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && blocked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && blocked(p)) e.setCancelled(true);
    }
}
