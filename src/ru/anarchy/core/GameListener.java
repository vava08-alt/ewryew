package ru.anarchy.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Locale;

import static ru.anarchy.core.AnarchyCore.color;
import static ru.anarchy.core.AnarchyCore.mm;

public final class GameListener implements Listener {
    private final AnarchyCore pl;

    public GameListener(AnarchyCore pl) { this.pl = pl; }

    // ---------- Чат ----------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        PlayerData d = pl.data(p);
        Rank r = pl.rank(d);
        long now = System.currentTimeMillis();
        long cd = pl.getConfig().getLong("chat.cooldown-ms", 2000);
        if (r.chatCooldown() && now - d.lastChat < cd) {
            e.setCancelled(true);
            pl.msg(p, "<red>Не так быстро! <gray>Без задержки в чате - от ранга Странник (/donate)");
            return;
        }
        if (pl.admin().isMuted(p)) { e.setCancelled(true); return; }
        d.lastChat = now;
        Component clan = pl.clans().of(p.getUniqueId()) == null ? Component.empty() : mm(pl.clans().tagOf(p.getUniqueId()));
        Component name = pl.displayName(d);
        Component title = d.title != null && !d.title.equals("newbie") ? mm("<dark_gray>[</dark_gray>" + pl.titleDisplay(d) + "<dark_gray>]</dark_gray> ") : Component.empty();
        Component streak = d.streak >= 3 ? mm("<red>☄" + d.streak + " </red>") : Component.empty();
        String nick = d.name;
        Component hover = mm("<white>" + AnarchyCore.esc(nick) + "\n<gray>Убийств: <red>" + d.kills + "\n<gray>Наиграно: <green>" + AnarchyCore.time(d.playMinutes * 60) + "\n<yellow>Нажми - профиль");
        Component clickName = name.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(hover))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/profile " + nick));
        e.renderer((src, dn, message, viewer) -> streak.append(clan).append(title).append(clickName).append(mm(" <dark_gray>»</dark_gray> "))
                .append(message.color(color(r.chatColor()))));
    }

    // ---------- Режим боя ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof TNTPrimed t && t.getSource() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;
        long until = System.currentTimeMillis() + pl.getConfig().getLong("combat.tag-seconds", 15) * 1000;
        for (Player p : List.of(victim, attacker)) {
            PlayerData d = pl.data(p);
            if (!d.inCombat()) pl.msg(p, "<red>⚔ Ты в режиме боя! <gray>Выход из игры = смерть.");
            d.combatUntil = until;
            pl.teleports().cancel(p, "ты в бою");
            pl.items().combatStarted(p);
        }
        PlayerData ad = pl.data(attacker);
        if (ad.ghost) {
            ad.ghost = false;
            attacker.removePotionEffect(PotionEffectType.INVISIBILITY);
            pl.msg(attacker, "<gray>☁ Ты атаковал и стал видимым.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) pl.teleports().cancel(p, "ты получил урон");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCombatCommand(PlayerCommandPreprocessEvent e) {
        PlayerData d = pl.data(e.getPlayer());
        if (!d.inCombat()) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        if (pl.getConfig().getStringList("combat.blocked-commands").contains(cmd)) {
            e.setCancelled(true);
            pl.msg(e.getPlayer(), "<red>Эта команда недоступна в режиме боя!");
        }
    }

    // ---------- Выход ----------
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        PlayerData d = pl.data(p);
        pl.teleports().cancel(p, "выход");
        if (!d.authed || pl.hasPerk(d, "silent-join") && !pl.hasPerk(d, "join-broadcast")) e.quitMessage(null);
        else e.quitMessage(mm("<dark_gray>[<red>-</red>]</dark_gray> ").append(pl.displayName(d)));
        if (d.authed && d.inCombat() && !pl.moreEvents().inTournament(p) && !(pl.dungeon() != null && pl.dungeon().isDungeon(p.getWorld())) && pl.getConfig().getBoolean("combat.kill-on-logout", true)) {
            p.setHealth(0);
            Bukkit.broadcast(mm("<red>☠ ").append(Component.text(p.getName())).append(mm(" <red>вышел из игры в бою и погиб!")));
        }
        d.combatUntil = 0;
        d.authed = false;
        d.flying = false;
        pl.board().remove(p);
        if (d.ghost) { d.ghost = false; p.removePotionEffect(PotionEffectType.INVISIBILITY); }
    }

    // ---------- Смерть ----------
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        PlayerData vd = pl.data(victim);
        if (pl.moreEvents().inTournament(victim) || pl.dungeon() != null && pl.dungeon().isDungeon(victim.getWorld())) { vd.combatUntil = 0; return; }
        String cause = e.deathMessage() == null ? "Погиб" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.deathMessage());
        org.bukkit.Location dl = victim.getLocation();
        vd.deathLog.add(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")) + " - " + cause
                + " (" + dl.getBlockX() + " " + dl.getBlockY() + " " + dl.getBlockZ() + ")");
        while (vd.deathLog.size() > 8) vd.deathLog.remove(0);
        if (vd.flying) { vd.flying = false; victim.setAllowFlight(false); }
        vd.backLoc = victim.getLocation();
        vd.deaths++;
        vd.combatUntil = 0;
        int lostStreak = vd.streak;
        vd.streak = 0;
        victim.setGlowing(false);
        if (vd.ghost) vd.ghost = false;

        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            if (lostStreak >= 5) Bukkit.broadcast(mm("<gray>Серия <red>" + lostStreak + "</red> игрока " + AnarchyCore.esc(victim.getName()) + " оборвалась."));
            return;
        }
        PlayerData kd = pl.data(killer);
        kd.combatUntil = 0;

        // защита от фарма твинками
        String vip = victim.getAddress() == null ? "a" : victim.getAddress().getAddress().getHostAddress();
        String kip = killer.getAddress() == null ? "b" : killer.getAddress().getAddress().getHostAddress();
        if (vip.equals(kip) && !pl.sharedIp(vip)) {   // общий IP туннеля не считается твинком
            pl.msg(killer, "<gray>Убийство с одного IP - награды не засчитаны.");
            return;
        }

        kd.kills++;
        kd.streak++;
        kd.bestStreak = Math.max(kd.bestStreak, kd.streak);
        Rank kr = pl.rank(kd);
        pl.quests().progress(killer, "KILL_PLAYER", null, 1);
        long base = Math.round(pl.getConfig().getLong("economy.kill-reward", 15) * kr.killMultiplier() * pl.events().coinMultiplier());
        long streakBonus = (kd.streak - 1) * pl.getConfig().getLong("streaks.bonus-per-kill", 5);
        long stolen = vd.balance * pl.getConfig().getLong("economy.steal-percent", 10) / 100;
        vd.balance -= stolen;
        long total = base + streakBonus + stolen;
        kd.balance += total;
        pl.msg(killer, "<green>+" + total + " " + pl.cur() + " <gray>(убийство " + base
                + (streakBonus > 0 ? ", серия +" + streakBonus : "") + (stolen > 0 ? ", отнято у жертвы " + stolen : "") + ")");
        if (stolen > 0) pl.msg(victim, "<red>Убийца забрал у тебя " + stolen + " " + pl.cur());

        if (pl.getConfig().getIntegerList("streaks.announce").contains(kd.streak))
            Bukkit.broadcast(mm("<red>☄ ").append(pl.displayName(kd)).append(mm(" <red>устроил серию из <yellow>" + kd.streak + "</yellow> убийств!")));
        if (kd.streak == pl.getConfig().getInt("streaks.glow-from", 5))
            Bukkit.broadcast(mm("<dark_red><bold>РАЗЫСКИВАЕТСЯ:</bold> ").append(Component.text(killer.getName()))
                    .append(mm(" <red>теперь светится для всех. Убей его!")));
        pl.updateGlow(killer);
        if (lostStreak >= 5)
            Bukkit.broadcast(mm("<gray>").append(pl.displayName(kd)).append(mm(" <gray>оборвал серию <red>" + lostStreak + "</red> игрока " + AnarchyCore.esc(victim.getName()))));

        if (pl.getConfig().getBoolean("heads.drop-on-pvp", true)) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(victim);
            meta.displayName(mm("<!italic><red>Голова " + AnarchyCore.esc(victim.getName())));
            meta.lore(List.of(mm("<!italic><gray>Убит игроком <white>" + AnarchyCore.esc(killer.getName())),
                    mm("<!italic><gray>" + java.time.LocalDate.now())));
            head.setItemMeta(meta);
            e.getDrops().add(head);
        }
    }

    // ---------- квесты и статистика ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(org.bukkit.event.block.BlockBreakEvent e) {
        PlayerData d = pl.data(e.getPlayer());
        d.blocksMined++;
        pl.quests().progress(e.getPlayer(), "MINE", e.getBlock().getType(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceQ(org.bukkit.event.block.BlockPlaceEvent e) { pl.quests().progress(e.getPlayer(), "PLACE", e.getBlock().getType(), 1); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(org.bukkit.event.player.PlayerFishEvent e) {
        if (e.getState() == org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH) pl.quests().progress(e.getPlayer(), "FISH", null, 1);
    }

    @EventHandler
    public void onMobDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        Player k = e.getEntity().getKiller();
        if (k == null) return;
        pl.data(k).mobKills++;
        mobReward(k, e.getEntity());
        if (e.getEntity() instanceof org.bukkit.entity.Monster || e.getEntity() instanceof org.bukkit.entity.Slime || e.getEntity() instanceof org.bukkit.entity.Animals)
            pl.quests().progress(k, "KILL_MOB", null, 1);
    }

    /** Небольшая награда монетами за моба (economy.mob-rewards). Мобы со спавнеров - x spawner-multiplier. */
    private void mobReward(Player k, org.bukkit.entity.LivingEntity en) {
        var c = pl.getConfig().getConfigurationSection("economy.mob-rewards");
        if (c == null || !c.getBoolean("enabled", true)) return;
        if (pl.dungeon() != null && pl.dungeon().isDungeon(en.getWorld())) return;   // в подземелье своя награда
        String t = en.getType().name();
        double v;
        if (c.isSet("mobs." + t)) v = c.getDouble("mobs." + t);
        else if (en instanceof org.bukkit.entity.Enemy) v = c.getDouble("monster", 2);
        else if (en instanceof org.bukkit.entity.Animals || en instanceof org.bukkit.entity.WaterMob) v = c.getDouble("animal", 1);
        else return;
        if (en.fromMobSpawner()) v *= c.getDouble("spawner-multiplier", 0.25);
        v *= pl.events().coinMultiplier();
        long n = (long) Math.floor(v);
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < v - n) n++;   // дробная часть - шансом
        if (n <= 0) return;
        pl.data(k).balance += n;
        if (c.getBoolean("actionbar", true)) k.sendActionBar(mm("<gold>+" + n + " " + pl.cur() + " <gray>за моба"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        if (!e.isBedSpawn() && !e.isAnchorSpawn() && pl.spawn() != null) e.setRespawnLocation(pl.spawn());
    }
}
