package ru.anarchy.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.stream.Collectors;

import static ru.anarchy.core.AnarchyCore.esc;
import static ru.anarchy.core.AnarchyCore.mm;
import static ru.anarchy.core.AnarchyCore.time;

public final class Commands implements CommandExecutor, TabCompleter {
    private final AnarchyCore pl;

    public Commands(AnarchyCore pl) { this.pl = pl; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        switch (c) {
            case "rank": rankCmd(s, a); return true;
            case "eco": ecoCmd(s, a); return true;
            case "core": coreCmd(s, a); return true;
            case "baltop": baltop(s); return true;
            case "killtop": top(s); return true;
            case "ari": ariCmd(s, a); return true;
            case "cases": if (a.length > 0 && casesAdmin(s, a)) return true; break;
            case "rules": for (String l : pl.getConfig().getStringList("rules")) s.sendMessage(mm(l)); return true;
            case "stats": stats(s, a); return true;
            case "balance": balance(s, a); return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Только для игроков"); return true; }
        PlayerData d = pl.data(p);
        switch (c) {
            case "register" -> register(p, d, a);
            case "login" -> login(p, d, a);
            case "changepassword" -> changePass(p, d, a);
            case "tpa" -> tpa(p, a, false);
            case "tpahere" -> { if (perk(p, d, "tpahere")) tpa(p, a, true); }
            case "tpaccept" -> tpaccept(p);
            case "tpdeny" -> tpdeny(p);
            case "sethome" -> sethome(p, d, a);
            case "home" -> home(p, d, a);
            case "delhome" -> delhome(p, d, a);
            case "homes" -> homes(p, d);
            case "rtp" -> rtp(p, d, a);
            case "donate" -> pl.menus().ranks(p);
            case "don" -> pl.menus().ranks(p);   // /don - привилегии, донат-магазин - /shop
            case "ext" -> { if (perk(p, d, "ext")) { p.setFireTicks(0); pl.msg(p, "<green>Огонь потушен"); } }
            case "stonecutter" -> { if (perk(p, d, "stonecutter")) p.openStonecutter(null, true); }
            case "loom" -> { if (perk(p, d, "loom")) p.openLoom(null, true); }
            case "grindstone" -> { if (perk(p, d, "grindstone")) p.openGrindstone(null, true); }
            case "smithing" -> { if (perk(p, d, "smithing")) p.openSmithingTable(null, true); }
            case "cartography" -> { if (perk(p, d, "cartography")) p.openCartographyTable(null, true); }
            case "nv" -> {
                if (!perk(p, d, "nv")) return true;
                if (p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) { p.removePotionEffect(PotionEffectType.NIGHT_VISION); pl.msg(p, "<yellow>Ночное зрение выключено"); }
                else { p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, true, false, true)); pl.msg(p, "<green>Ночное зрение включено"); }
            }
            case "bc" -> {
                if (!perk(p, d, "bc")) return true;
                if (a.length == 0) { pl.msg(p, "<red>/bc <текст> - объявление на весь сервер (раз в час)"); return true; }
                if (!cooldown(p, d, "bc", perkCd("bc"))) return true;
                String text = esc(String.join(" ", a));
                for (Player o : Bukkit.getOnlinePlayers()) {
                    o.sendMessage(mm(" "));
                    o.sendMessage(mm("<gradient:#ffd700:#ff8c00><bold>♛ ОБЪЯВЛЕНИЕ</bold></gradient> <dark_gray>от</dark_gray> ").append(pl.displayName(d)));
                    o.sendMessage(mm("<yellow>" + text));
                    o.sendMessage(mm(" "));
                    o.playSound(o.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
                }
            }
            case "pweather" -> {
                if (!perk(p, d, "pweather")) return true;
                String m = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "reset";
                if (m.equals("clear")) p.setPlayerWeather(WeatherType.CLEAR);
                else if (m.equals("rain")) p.setPlayerWeather(WeatherType.DOWNFALL);
                else p.resetPlayerWeather();
                pl.msg(p, "<green>Твоя погода: " + m);
            }
            case "ecsee" -> {
                if (!perk(p, d, "ecsee")) return true;
                if (a.length < 1) { pl.msg(p, "<red>/ecsee <ник>"); return true; }
                Player t = online(p, a[0]);
                if (t == null) return true;
                org.bukkit.inventory.Inventory inv = Bukkit.createInventory(new Menus.Menu(1, "x"), 27, mm("<dark_gray>Эндер-сундук " + esc(t.getName()) + " (просмотр)"));
                ItemStack[] ecc = t.getEnderChest().getContents();
                for (int i = 0; i < ecc.length && i < 27; i++) if (ecc[i] != null) inv.setItem(i, ecc[i].clone());
                p.openInventory(inv);
            }
            case "menu" -> pl.menus().main(p);
            case "cases" -> pl.cases().list(p);
            case "profile" -> {
                PlayerData t = a.length > 0 ? pl.store().byName(a[0]) : d;
                if (t == null) pl.msg(p, "<red>Игрок не найден"); else pl.menus().profile(p, t);
            }
            case "quests" -> pl.menus().quests(p);
            case "titles" -> pl.menus().titles(p);
            case "buffs" -> pl.menus().buffs(p);
            case "shop" -> pl.menus().donate(p);
            case "market" -> pl.menus().shop(p);
            case "daily" -> daily(p, d);
            case "rg" -> pl.regions().command(p, a);
            case "spawn" -> {
                if (pl.spawn() == null) { pl.msg(p, "<red>Спавн не установлен"); return true; }
                pl.teleports().start(p, "на спавн", () -> pl.teleports().teleport(p, pl.spawn()));
            }
            case "fly" -> fly(p, d);
            case "trash" -> { if (perk(p, d, "trash")) p.openInventory(Bukkit.createInventory(null, 36, mm("<dark_gray>Мусорка (предметы исчезнут)"))); }
            case "anvil" -> { if (perk(p, d, "anvil")) p.openAnvil(null, true); }
            case "top" -> {
                if (pl.bossArena() != null && pl.bossArena().isLocked(p)) { pl.msg(p, "<red>☠ С арены Стража не уйти, пока он жив!"); return true; }
                if (!perk(p, d, "top") || !cooldown(p, d, "top", perkCd("top"))) return true;
                Location hl = p.getWorld().getHighestBlockAt(p.getLocation()).getLocation().add(0.5, 1, 0.5);
                hl.setYaw(p.getLocation().getYaw()); hl.setPitch(p.getLocation().getPitch());
                pl.teleports().teleport(p, hl);
            }
            case "invsee" -> invsee(p, d, a);
            case "color" -> color(p, d, a);
            case "ptime" -> {
                if (!perk(p, d, "ptime")) return true;
                String m = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "reset";
                switch (m) {
                    case "day" -> p.setPlayerTime(6000, false);
                    case "night" -> p.setPlayerTime(18000, false);
                    case "sunset" -> p.setPlayerTime(12500, false);
                    default -> p.resetPlayerTime();
                }
                pl.msg(p, "<green>Твоё время: " + m);
            }
            case "kit" -> kit(p, d, a);
            case "pay" -> pay(p, d, a);
            case "suicide" -> { p.setHealth(0); pl.msg(p, "<gray>Ты покончил с собой."); }
            case "hunt" -> hunt(p, d);
            case "hat" -> hat(p, d);
            case "ghost" -> ghost(p, d);
            case "ec" -> { if (perk(p, d, "ec")) p.openInventory(p.getEnderChest()); }
            case "craft" -> { if (perk(p, d, "craft")) p.openWorkbench(null, true); }
            case "feed" -> feed(p, d);
            case "heal" -> heal(p, d);
            case "repair" -> repair(p, d, a);
            case "back" -> back(p, d);
            case "near" -> near(p, d);
        }
        return true;
    }

    // ================= ВСПОМОГАТЕЛЬНОЕ =================
    private boolean perk(Player p, PlayerData d, String perk) {
        if (pl.hasPerk(d, perk)) return true;
        Rank r = pl.rankForPerk(perk);
        pl.msg(p, "<red>Доступно с привилегии </red>" + (r == null ? "?" : r.prefix()) + "<gray>- /donate");
        return false;
    }

    private boolean cooldown(Player p, PlayerData d, String key, long seconds) {
        long left = d.cooldownLeft(key);
        if (left > 0) { pl.msg(p, "<red>Подожди ещё " + time(left)); return false; }
        d.setCooldown(key, seconds);
        return true;
    }

    private long perkCd(String perk) { return pl.getConfig().getLong("perk-cooldowns." + perk, 300); }

    private Player online(Player p, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null || !pl.data(t).authed) { pl.msg(p, "<red>Игрок не в сети"); return null; }
        return t;
    }

    private static Component button(String text, String command, String hover) {
        return mm(text).clickEvent(ClickEvent.runCommand(command)).hoverEvent(HoverEvent.showText(mm(hover)));
    }

    // ================= АВТОРИЗАЦИЯ =================
    private void register(Player p, PlayerData d, String[] a) {
        if (d.authed) { pl.msg(p, "<red>Ты уже вошёл"); return; }
        if (d.passHash != null) { pl.msg(p, "<red>Ник уже зарегистрирован. /login <пароль>"); return; }
        if (a.length < 2 || !a[0].equals(a[1])) { pl.msg(p, "<red>Использование: /reg <пароль> <пароль> (пароли должны совпадать)"); return; }
        int min = pl.getConfig().getInt("auth.min-password", 5);
        if (a[0].length() < min) { pl.msg(p, "<red>Пароль минимум " + min + " символов"); return; }
        if (a[0].equalsIgnoreCase(p.getName())) { pl.msg(p, "<red>Пароль не должен совпадать с ником"); return; }
        String pass = a[0];
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            String h = AnarchyCore.newHash(pass);
            Bukkit.getScheduler().runTask(pl, () -> {
                if (!p.isOnline() || d.passHash != null) return;
                d.passHash = h;
                pl.msg(p, "<green>Регистрация успешна! Запомни пароль.");
                pl.onAuthed(p, true);
            });
        });
    }

    private void login(Player p, PlayerData d, String[] a) {
        if (d.authed) { pl.msg(p, "<red>Ты уже вошёл"); return; }
        if (d.passHash == null) { pl.msg(p, "<red>Сначала зарегистрируйся: /reg <пароль> <пароль>"); return; }
        if (a.length < 1) { pl.msg(p, "<red>Использование: /login <пароль>"); return; }
        String pass = a[0], stored = d.passHash;
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            boolean ok = AnarchyCore.checkHash(pass, stored);
            Bukkit.getScheduler().runTask(pl, () -> {
                if (!p.isOnline() || d.authed) return;
                if (ok) { pl.onAuthed(p, false); return; }
                d.failedAttempts++;
                if (d.failedAttempts >= pl.getConfig().getInt("auth.max-attempts", 3)) p.kick(mm("<red>Слишком много неверных попыток"));
                else pl.msg(p, "<red>Неверный пароль!");
            });
        });
    }

    private void changePass(Player p, PlayerData d, String[] a) {
        if (a.length < 2) { pl.msg(p, "<red>Использование: /changepassword <старый> <новый>"); return; }
        if (a[1].length() < pl.getConfig().getInt("auth.min-password", 5)) { pl.msg(p, "<red>Новый пароль слишком короткий"); return; }
        String old = a[0], neu = a[1], stored = d.passHash;
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            boolean ok = AnarchyCore.checkHash(old, stored);
            String h = ok ? AnarchyCore.newHash(neu) : null;
            Bukkit.getScheduler().runTask(pl, () -> {
                if (!ok) { pl.msg(p, "<red>Старый пароль неверный"); return; }
                d.passHash = h;
                pl.msg(p, "<green>Пароль изменён");
            });
        });
    }

    // ================= TPA =================
    private void tpa(Player p, String[] a, boolean here) {
        if (a.length < 1) { pl.msg(p, "<red>Использование: /" + (here ? "tpahere" : "tpa") + " <ник>"); return; }
        Player t = online(p, a[0]);
        if (t == null) return;
        if (t.equals(p)) { pl.msg(p, "<red>Нельзя себе"); return; }
        PlayerData d = pl.data(p);
        if (d.cooldownLeft("tpa") > 0) { pl.msg(p, "<red>Запросы можно отправлять раз в 10 сек"); return; }
        d.setCooldown("tpa", 10);
        pl.teleports().request(p, t, here);
        pl.msg(p, "<green>Запрос отправлен игроку <white>" + esc(t.getName()));
        Component m = mm(pl.getConfig().getString("prefix", "")).append(pl.displayName(d))
                .append(mm(here ? " <yellow>зовёт тебя к себе. " : " <yellow>хочет телепортироваться к тебе. "))
                .append(button("<green><bold>[Принять]</bold>", "/tpaccept", "<green>Принять"))
                .append(Component.text(" "))
                .append(button("<red><bold>[Отклонить]</bold>", "/tpdeny", "<red>Отклонить"));
        t.sendMessage(m);
        if (!here) t.sendMessage(mm("<dark_gray>Осторожно: это может быть ловушка. Это анархия."));
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
    }

    private void tpaccept(Player p) {
        Teleports.Request r = pl.teleports().takeRequest(p);
        Player from = r == null ? null : Bukkit.getPlayer(r.from());
        if (from == null) { pl.msg(p, "<red>Нет активных запросов"); return; }
        pl.msg(p, "<green>Запрос принят");
        pl.msg(from, "<green>" + esc(p.getName()) + " принял запрос");
        if (r.here()) pl.teleports().start(p, "к " + esc(from.getName()), () -> { if (from.isOnline()) pl.teleports().teleport(p, from.getLocation()); });
        else pl.teleports().start(from, "к " + esc(p.getName()), () -> { if (p.isOnline()) pl.teleports().teleport(from, p.getLocation()); });
    }

    private void tpdeny(Player p) {
        Teleports.Request r = pl.teleports().takeRequest(p);
        if (r == null) { pl.msg(p, "<red>Нет активных запросов"); return; }
        pl.msg(p, "<gray>Запрос отклонён");
        Player from = Bukkit.getPlayer(r.from());
        if (from != null) pl.msg(from, "<red>" + esc(p.getName()) + " отклонил запрос");
    }

    // ================= ДОМА =================
    private void sethome(Player p, PlayerData d, String[] a) {
        String name = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "home";
        if (!name.matches("[a-zа-я0-9_]{1,16}")) { pl.msg(p, "<red>Название: буквы/цифры, до 16 символов"); return; }
        int max = pl.rank(d).homes();
        if (!d.homes.containsKey(name) && d.homes.size() >= max) {
            pl.msg(p, "<red>Лимит домов: " + max + ". <gray>Больше домов - /donate");
            return;
        }
        d.homes.put(name, PlayerData.locToString(p.getLocation()));
        pl.msg(p, "<green>Дом <white>" + name + "</white> установлен (" + d.homes.size() + "/" + max + ")");
    }

    private void home(Player p, PlayerData d, String[] a) {
        if (d.homes.isEmpty()) { pl.msg(p, "<red>У тебя нет домов. /sethome [название]"); return; }
        String name = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : (d.homes.containsKey("home") ? "home" : d.homes.keySet().iterator().next());
        String s = d.homes.get(name);
        if (s == null) { homes(p, d); return; }
        Location l = PlayerData.locFromString(s);
        if (l == null) { pl.msg(p, "<red>Мир этого дома не найден"); return; }
        pl.teleports().start(p, "домой (" + name + ")", () -> pl.teleports().teleport(p, l));
    }

    private void delhome(Player p, PlayerData d, String[] a) {
        if (a.length < 1) { pl.msg(p, "<red>Использование: /delhome <название>"); return; }
        if (d.homes.remove(a[0].toLowerCase(Locale.ROOT)) != null) pl.msg(p, "<green>Дом удалён");
        else pl.msg(p, "<red>Нет такого дома");
    }

    private void homes(Player p, PlayerData d) {
        if (d.homes.isEmpty()) { pl.msg(p, "<gray>Домов нет. /sethome [название]"); return; }
        Component m = mm(pl.getConfig().getString("prefix", "") + "<gray>Дома (" + d.homes.size() + "/" + pl.rank(d).homes() + "): ");
        for (String h : d.homes.keySet())
            m = m.append(button("<yellow>[" + h + "]</yellow> ", "/home " + h, "<gray>Телепорт в " + h));
        p.sendMessage(m);
    }

    // ================= RTP =================
    private void rtp(Player p, PlayerData d, String[] a) {
        String mode = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "normal";
        World w = Bukkit.getWorld(pl.getConfig().getString("rtp.world", "world"));
        if (w == null) w = Bukkit.getWorlds().get(0);
        World world = w;
        Location center = pl.spawn() != null && pl.spawn().getWorld().equals(world) ? pl.spawn() : new Location(world, 0, 64, 0);
        String key; long cd; int min, max; String label;
        switch (mode) {
            case "far", "big", "b" -> {
                if (!perk(p, d, "rtp-far")) return;
                key = "rtp_far"; cd = perkCd("rtp-far"); label = "далеко";
                min = pl.getConfig().getInt("rtp.far.min", 5000); max = pl.getConfig().getInt("rtp.far.max", 30000);
            }
            case "player", "p" -> {
                if (!perk(p, d, "rtp-player")) return;
                List<Player> targets = Bukkit.getOnlinePlayers().stream().filter(o -> !o.equals(p) && pl.data(o).authed
                        && o.getWorld().getEnvironment() == World.Environment.NORMAL && !pl.regions().inSpawn(o.getLocation())
                        && o.getGameMode() == GameMode.SURVIVAL).map(o -> (Player) o).toList();
                if (targets.isEmpty()) { pl.msg(p, "<red>Нет подходящих игроков (все на спавне или в другом мире)"); return; }
                Player t = targets.get(new Random().nextInt(targets.size()));
                center = t.getLocation();
                key = "rtp_player"; cd = perkCd("rtp-player"); label = "к случайному игроку";
                min = pl.getConfig().getInt("rtp.player.min", 25); max = pl.getConfig().getInt("rtp.player.max", 70);
            }
            default -> {
                key = "rtp"; cd = pl.rank(d).rtpCooldown(); label = "в случайное место";
                min = pl.getConfig().getInt("rtp.normal.min", 300); max = pl.getConfig().getInt("rtp.normal.max", 3000);
            }
        }
        long left = d.cooldownLeft(key);
        if (left > 0) { pl.msg(p, "<red>Этот RTP будет доступен через " + time(left) + ". <gray>Меньше ожидание - /don"); return; }
        Location c = center;
        pl.teleports().start(p, label, () -> {
            pl.msg(p, "<gray>Ищу безопасное место...");
            d.setCooldown(key, 20);
            pl.teleports().findAround(c, min, max, 15, loc -> {
                if (!p.isOnline()) return;
                if (loc == null) { d.cooldowns.remove(key); pl.msg(p, "<red>Не удалось найти место, попробуй ещё раз"); return; }
                d.setCooldown(key, cd);
                pl.teleports().teleport(p, loc);
                pl.msg(p, "<green>Ты на <white>" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "</white>. "
                        + (key.equals("rtp_player") ? "<red>Кто-то рядом..." : "Выживай."));
            });
        });
    }

    private void daily(Player p, PlayerData d) {
        long left = d.cooldownLeft("daily");
        if (left > 0) { pl.msg(p, "<red>Ежедневная награда через " + time(left)); return; }
        int idx = 0;
        for (Rank r : pl.ranks()) { if (r.hidden()) continue; if (r.weight() <= pl.rank(d).weight()) idx++; }
        double mult = 1 + Math.max(0, idx - 1) * 0.5;
        long coins = Math.round(pl.getConfig().getLong("economy.daily-reward", 100) * mult);
        d.balance += coins;
        d.setCooldown("daily", 86400);
        String extra = "";
        if (pl.hasPerk(d, "daily-ari")) { d.ari += 5; extra = " <light_purple>+5 " + pl.ariSym(); }
        String dk = pl.getConfig().getString("daily-key", "ari");
        if (pl.cases().get(dk) != null) { pl.cases().addKeys(d, dk, 1); extra += " <aqua>+1 ключ " + pl.cases().get(dk).display; }
        pl.msg(p, "<gold>Ежедневная награда: +" + coins + " " + pl.cur() + extra + " <gray>(x" + mult + " за ранг)");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
    }

    private void fly(Player p, PlayerData d) {
        if (!perk(p, d, "fly")) return;
        if (d.flying) { d.flying = false; p.setAllowFlight(false); p.setFlying(false); p.setFallDistance(0); pl.msg(p, "<yellow>Полёт выключен"); return; }
        Regions.Region g = pl.regions().at(p.getLocation());
        if (g == null || !g.canUse(p.getUniqueId())) { pl.msg(p, "<red>Летать можно только в своём привате (или где ты участник)"); return; }
        d.flying = true;
        p.setAllowFlight(true);
        pl.msg(p, "<green>Полёт включён <gray>(выключится при выходе из привата или в бою)");
    }

    private void invsee(Player p, PlayerData d, String[] a) {
        if (!perk(p, d, "invsee")) return;
        if (a.length < 1) { pl.msg(p, "<red>/invsee <ник>"); return; }
        Player t = online(p, a[0]);
        if (t == null) return;
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(new Menus.Menu(1, "x"), 45, mm("<dark_gray>Инвентарь " + esc(t.getName()) + " (только просмотр)"));
        ItemStack[] c = t.getInventory().getStorageContents();
        for (int i = 9; i < c.length && i < 36; i++) if (c[i] != null) inv.setItem(i - 9, c[i].clone());
        for (int i = 0; i < 9; i++) if (c[i] != null) inv.setItem(27 + i, c[i].clone());
        ItemStack[] ar = t.getInventory().getArmorContents();
        for (int i = 0; i < ar.length; i++) if (ar[i] != null) inv.setItem(36 + i, ar[i].clone());
        if (!t.getInventory().getItemInOffHand().getType().isAir()) inv.setItem(41, t.getInventory().getItemInOffHand().clone());
        p.openInventory(inv);
    }

    private void color(Player p, PlayerData d, String[] a) {
        if (!perk(p, d, "color")) return;
        if (a.length < 1) { pl.msg(p, "<red>/color <#RRGGBB> или /color reset"); return; }
        if (a[0].equalsIgnoreCase("reset")) { d.customColor = null; pl.msg(p, "<green>Цвет сброшен"); pl.applyTeam(p); return; }
        if (!a[0].matches("#[0-9a-fA-F]{6}")) { pl.msg(p, "<red>Формат: #FF00AA"); return; }
        d.customColor = a[0];
        pl.applyTeam(p);
        pl.msg(p, "<green>Новый цвет ника: <" + a[0] + ">" + esc(p.getName()));
    }

    private void ariCmd(CommandSender s, String[] a) {
        if (a.length >= 3 && s.hasPermission("anarchy.admin")) {
            PlayerData d = pl.store().byName(a[1]);
            if (d == null) { s.sendMessage("Игрок не найден"); return; }
            long v;
            try { v = Long.parseLong(a[2]); } catch (Exception e) { s.sendMessage("Неверное число"); return; }
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "give" -> d.ari += v;
                case "take" -> d.ari = Math.max(0, d.ari - v);
                case "set" -> d.ari = v;
                default -> { s.sendMessage("ari give|take|set <ник> <кол-во>"); return; }
            }
            s.sendMessage(d.name + ": " + d.ari + " Ari");
            Player p = Bukkit.getPlayer(d.uuid);
            if (p != null && a[0].equalsIgnoreCase("give")) {
                pl.msg(p, "<light_purple>Зачислено " + v + " " + pl.ariName() + " " + pl.ariSym() + "! <gray>Трать в /shop");
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.5f);
            }
            pl.store().save(true);
            return;
        }
        PlayerData d = a.length > 0 ? pl.store().byName(a[0]) : s instanceof Player p ? pl.data(p) : null;
        if (d == null) { s.sendMessage("ari <ник> | ari give|take|set <ник> <кол-во>"); return; }
        pl.msg(s, "<light_purple>" + esc(d.name) + ": " + d.ari + " " + pl.ariName() + " " + pl.ariSym() + " <gray>| Пополнить: " + pl.getConfig().getString("donate-site"));
    }

    // ================= КИТЫ =================
    private void kit(Player p, PlayerData d, String[] a) {
        ConfigurationSection ks = pl.getConfig().getConfigurationSection("kits");
        if (ks == null) return;
        int w = pl.rank(d).weight();
        if (a.length == 0) {
            Component m = mm(pl.getConfig().getString("prefix", "") + "<gray>Киты: ");
            for (String k : ks.getKeys(false)) {
                Rank r = pl.rankById(ks.getString(k + ".rank", "player"));
                boolean can = r == null || r.weight() <= w;
                long left = d.cooldownLeft("kit_" + k);
                String col = !can ? "<dark_gray>" : left > 0 ? "<gold>" : "<green>";
                String hover = !can ? "<red>Нужна привилегия " + (r == null ? "" : r.display()) : left > 0 ? "<gold>Через " + time(left) : "<green>Получить";
                m = m.append(button(col + "[" + k + "]</" + col.substring(1) + " ", "/kit " + k, hover));
            }
            p.sendMessage(m);
            return;
        }
        String k = a[0].toLowerCase(Locale.ROOT);
        if (!ks.contains(k)) { pl.msg(p, "<red>Нет такого кита. /kit"); return; }
        Rank r = pl.rankById(ks.getString(k + ".rank", "player"));
        if (r != null && r.weight() > w) { pl.msg(p, "<red>Кит доступен с привилегии </red>" + r.prefix() + "<gray>- /donate"); return; }
        if (!cooldown(p, d, "kit_" + k, ks.getLong(k + ".cooldown", 86400))) return;
        giveKit(pl, p, k);
        pl.msg(p, "<green>Кит <white>" + k + "</white> получен!");
    }

    public static void giveKit(AnarchyCore pl, Player p, String kit) {
        for (String line : pl.getConfig().getStringList("kits." + kit + ".items")) {
            try {
                String[] parts = line.split(":");
                if (parts[0].equalsIgnoreCase("custom")) {   // custom:<id>[:кол-во]
                    int n = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
                    for (int i = 0; i < n; i++) { ItemStack c = pl.items().create(parts[1]); if (c != null) for (ItemStack left : p.getInventory().addItem(c).values()) p.getWorld().dropItemNaturally(p.getLocation(), left); }
                    continue;
                }
                if (parts[0].equalsIgnoreCase("potion") || parts[0].equalsIgnoreCase("splash")) {   // potion|splash:<эффект>:<уровень>:<секунд>[:кол-во]
                    ItemStack pt = new ItemStack(parts[0].equalsIgnoreCase("splash") ? Material.SPLASH_POTION : Material.POTION, parts.length > 4 ? Integer.parseInt(parts[4]) : 1);
                    org.bukkit.inventory.meta.PotionMeta pm = (org.bukkit.inventory.meta.PotionMeta) pt.getItemMeta();
                    PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(parts[1].toLowerCase(Locale.ROOT)));
                    if (type == null) continue;
                    pm.addCustomEffect(new PotionEffect(type, Integer.parseInt(parts[3]) * 20, Integer.parseInt(parts[2]) - 1), true);
                    pm.setColor(type.getColor());
                    pm.displayName(mm("<!italic><white>" + (parts[0].equalsIgnoreCase("splash") ? "Взрывное зелье: " : "Зелье: ") + "<lang:" + type.translationKey() + "> " + parts[2]));
                    pt.setItemMeta(pm);
                    for (ItemStack left : p.getInventory().addItem(pt).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                    continue;
                }
                Material m = Material.matchMaterial(parts[0]);
                if (m == null) continue;
                ItemStack it = new ItemStack(m, parts.length > 1 ? Integer.parseInt(parts[1]) : 1);
                if (parts.length > 2) for (String en : parts[2].split(",")) {
                    String[] kv = en.split("=");
                    Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(kv[0].toLowerCase(Locale.ROOT)));
                    if (e != null) it.addUnsafeEnchantment(e, Integer.parseInt(kv[1]));
                }
                for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            } catch (Exception ex) {
                pl.getLogger().warning("Ошибка в ките " + kit + ": " + line);
            }
        }
    }

    // ================= ЭКОНОМИКА =================
    private void balance(CommandSender s, String[] a) {
        PlayerData d;
        if (a.length > 0) d = pl.store().byName(a[0]);
        else if (s instanceof Player p) d = pl.data(p);
        else { s.sendMessage("Использование: balance <ник>"); return; }
        if (d == null) { pl.msg(s, "<red>Игрок не найден"); return; }
        pl.msg(s, "<gray>Баланс <white>" + esc(d.name) + "</white>: <gold>" + d.balance + " " + pl.cur());
    }

    private void pay(Player p, PlayerData d, String[] a) {
        if (a.length < 2) { pl.msg(p, "<red>Использование: /pay <ник> <сумма>"); return; }
        PlayerData t = pl.store().byName(a[0]);
        if (t == null || t.passHash == null) { pl.msg(p, "<red>Игрок не найден"); return; }
        if (t == d) { pl.msg(p, "<red>Нельзя себе"); return; }
        long amount;
        try { amount = Long.parseLong(a[1]); } catch (Exception e) { pl.msg(p, "<red>Неверная сумма"); return; }
        if (amount <= 0 || amount > d.balance) { pl.msg(p, "<red>Недостаточно монет"); return; }
        d.balance -= amount;
        t.balance += amount;
        pl.msg(p, "<green>Отправлено " + amount + " " + pl.cur() + " игроку " + esc(t.name));
        Player tp = Bukkit.getPlayer(t.uuid);
        if (tp != null) pl.msg(tp, "<green>Получено " + amount + " " + pl.cur() + " от " + esc(p.getName()));
    }

    private void baltop(CommandSender s) {
        List<PlayerData> top = pl.store().all().stream().filter(x -> x.passHash != null)
                .sorted(Comparator.comparingLong((PlayerData x) -> x.balance).reversed()).limit(10).toList();
        s.sendMessage(mm("<gold><bold>Топ богачей"));
        int i = 1;
        for (PlayerData d : top) s.sendMessage(mm("<gray>" + i++ + ". ").append(pl.displayName(d)).append(mm(" <dark_gray>- <gold>" + d.balance + " " + pl.cur())));
    }

    private void top(CommandSender s) {
        List<PlayerData> top = pl.store().all().stream().filter(x -> x.passHash != null)
                .sorted(Comparator.comparingInt((PlayerData x) -> x.kills).reversed()).limit(10).toList();
        s.sendMessage(mm("<red><bold>Топ убийц"));
        int i = 1;
        for (PlayerData d : top) s.sendMessage(mm("<gray>" + i++ + ". ").append(pl.displayName(d))
                .append(mm(" <dark_gray>- <red>" + d.kills + " убийств <gray>(лучшая серия " + d.bestStreak + ")")));
    }

    // ================= СТАТИСТИКА =================
    private void stats(CommandSender s, String[] a) {
        PlayerData d;
        if (a.length > 0) d = pl.store().byName(a[0]);
        else if (s instanceof Player p) d = pl.data(p);
        else return;
        if (d == null) { pl.msg(s, "<red>Игрок не найден"); return; }
        Rank r = pl.rank(d);
        String kd = d.deaths == 0 ? String.valueOf(d.kills) : String.format(Locale.US, "%.2f", (double) d.kills / d.deaths);
        s.sendMessage(mm("<dark_gray>------ </dark_gray>").append(pl.displayName(d)).append(mm(" <dark_gray>------")));
        s.sendMessage(mm("<gray>Ранг: <white>" + r.display() + (d.rankExpire > 0 ? " <gray>(ещё " + time((d.rankExpire - System.currentTimeMillis()) / 1000) + ")" : "")));
        s.sendMessage(mm("<gray>Убийств: <red>" + d.kills + "</red>  Смертей: <white>" + d.deaths + "</white>  K/D: <yellow>" + kd));
        s.sendMessage(mm("<gray>Серия: <red>" + d.streak + "</red>  Лучшая: <gold>" + d.bestStreak));
        s.sendMessage(mm("<gray>Баланс: <gold>" + d.balance + " " + pl.cur() + "</gold>"));
        s.sendMessage(mm("<gray>Наиграно: <white>" + time(d.playMinutes * 60)));
    }

    // ================= ДОНАТ =================
    private void donate(CommandSender s) {
        s.sendMessage(mm("<dark_red><bold>━━━━━━ ПРИВИЛЕГИИ ━━━━━━"));
        String current = s instanceof Player p ? pl.data(p).rank : "";
        for (Rank r : pl.ranks()) {
            if (r.hidden() || r.priceAri() <= 0) continue;
            s.sendMessage(mm(" "));
            s.sendMessage(mm(r.prefix() + "<dark_gray>- <light_purple>" + r.priceAri() + " " + pl.ariName() + (r.id().equals(current) ? " <yellow>(твой ранг)" : "")));
            for (String l : r.description()) s.sendMessage(mm("  <gray>" + l));
        }
        s.sendMessage(mm(" "));
        s.sendMessage(mm("<gray>Все способности младших рангов включены в старшие."));
        s.sendMessage(mm("<gray>Купить: <yellow>" + pl.getConfig().getString("donate-site")));
    }

    // ================= СПОСОБНОСТИ =================
    private void hunt(Player p, PlayerData d) {
        if (!perk(p, d, "hunt")) return;
        int range = pl.getConfig().getInt("hunt.range", 5000);
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p) || !pl.data(o).authed || o.getGameMode() == GameMode.SPECTATOR) continue;
            double dist = o.getLocation().distance(p.getLocation());
            if (dist < bestD && dist <= range) { bestD = dist; best = o; }
        }
        if (best == null) { pl.msg(p, "<gray>В радиусе " + range + " блоков никого. Тишина..."); return; }
        if (!cooldown(p, d, "hunt", perkCd("hunt"))) return;
        double dx = best.getLocation().getX() - p.getLocation().getX();
        double dz = best.getLocation().getZ() - p.getLocation().getZ();
        double ang = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360;
        String[] dirs = {"север", "северо-восток", "восток", "юго-восток", "юг", "юго-запад", "запад", "северо-запад"};
        String dir = dirs[(int) Math.round(ang / 45) % 8];
        long rounded = Math.max(50, Math.round(bestD / 50) * 50);
        pl.msg(p, "<gold>➹ Цель обнаружена: <white>~" + rounded + " блоков</white>, направление: <yellow>" + dir);
        p.playSound(p.getLocation(), Sound.ENTITY_WOLF_GROWL, 0.7f, 1f);
        pl.msg(best, "<dark_red><i>Ты чувствуешь, что за тобой кто-то следит...");
        best.playSound(best.getLocation(), Sound.AMBIENT_CAVE, 1f, 1f);
    }

    private void hat(Player p, PlayerData d) {
        if (!perk(p, d, "hat")) return;
        PlayerInventory inv = p.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (hand.getType().isAir()) { pl.msg(p, "<red>Возьми предмет в руку"); return; }
        ItemStack old = inv.getHelmet();
        inv.setHelmet(hand);
        inv.setItemInMainHand(old);
        pl.msg(p, "<green>Отличная шляпа!");
    }

    private void ghost(Player p, PlayerData d) {
        if (!perk(p, d, "ghost")) return;
        if (!cooldown(p, d, "ghost", perkCd("ghost"))) return;
        int sec = pl.getConfig().getInt("ghost-duration", 30);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, sec * 20, 0, false, false, true));
        d.ghost = true;
        p.getWorld().spawnParticle(Particle.LARGE_SMOKE, p.getLocation().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.02);
        p.playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 1f);
        pl.msg(p, "<gray>☁ Ты растворился в воздухе на " + sec + " сек. Удар раскроет тебя. <dark_gray>(броня видна)");
        Bukkit.getScheduler().runTaskLater(pl, () -> d.ghost = false, sec * 20L);
    }

    private void feed(Player p, PlayerData d) {
        if (!perk(p, d, "feed") || !cooldown(p, d, "feed", perkCd("feed"))) return;
        p.setFoodLevel(20);
        p.setSaturation(10);
        pl.msg(p, "<green>Ты сыт");
    }

    private void heal(Player p, PlayerData d) {
        if (!perk(p, d, "heal") || !cooldown(p, d, "heal", perkCd("heal"))) return;
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(attr == null ? 20 : attr.getValue());
        p.setFoodLevel(20);
        p.setSaturation(10);
        p.setFireTicks(0);
        pl.msg(p, "<green>Ты полностью исцелён");
    }

    private void repair(Player p, PlayerData d, String[] a) {
        if (!perk(p, d, "repair")) return;
        boolean all = a.length > 0 && a[0].equalsIgnoreCase("all");
        if (all && !perk(p, d, "repair-all")) return;
        List<ItemStack> items = new ArrayList<>();
        if (all) { items.addAll(Arrays.asList(p.getInventory().getContents())); }
        else items.add(p.getInventory().getItemInMainHand());
        List<ItemStack> damaged = items.stream().filter(i -> i != null && i.getItemMeta() instanceof Damageable dm && dm.hasDamage()).toList();
        if (damaged.isEmpty()) { pl.msg(p, "<red>Нечего чинить"); return; }
        if (!cooldown(p, d, all ? "repair_all" : "repair", perkCd("repair"))) return;
        for (ItemStack i : damaged) {
            ItemMeta m = i.getItemMeta();
            ((Damageable) m).setDamage(0);
            i.setItemMeta(m);
        }
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
        pl.msg(p, "<green>Починено предметов: " + damaged.size());
    }

    private void back(Player p, PlayerData d) {
        if (!perk(p, d, "back")) return;
        if (d.backLoc == null) { pl.msg(p, "<red>Нет места смерти"); return; }
        long left = d.cooldownLeft("back");
        if (left > 0) { pl.msg(p, "<red>Подожди ещё " + time(left)); return; }
        Location l = d.backLoc;
        pl.teleports().start(p, "к месту смерти", () -> {
            d.setCooldown("back", perkCd("back"));
            d.backLoc = null;
            pl.teleports().teleport(p, l);
        });
    }

    private void near(Player p, PlayerData d) {
        if (!perk(p, d, "near") || !cooldown(p, d, "near", perkCd("near"))) return;
        List<String> list = new ArrayList<>();
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p) || !pl.data(o).authed) continue;
            double dist = o.getLocation().distance(p.getLocation());
            if (dist <= 300) list.add("<white>" + esc(o.getName()) + " <gray>(" + Math.round(dist) + "м)");
        }
        pl.msg(p, list.isEmpty() ? "<gray>Рядом никого" : "<gold>♛ Рядом: " + String.join("<dark_gray>, ", list));
    }

    // ================= АДМИН =================
    private void rankCmd(CommandSender s, String[] a) {
        if (a.length >= 3 && a[0].equalsIgnoreCase("set")) {
            PlayerData d = pl.store().byName(a[1]);
            Rank r = pl.rankById(a[2]);
            if (d == null) { s.sendMessage("Игрок не найден (должен хотя бы раз зайти)"); return; }
            if (r == null) { s.sendMessage("Нет ранга. Доступны: " + pl.ranks().stream().map(Rank::id).collect(Collectors.joining(", "))); return; }
            if (a.length >= 4) {
                long days;
                try { days = Long.parseLong(a[3]); } catch (Exception e) { s.sendMessage("Неверное число дней"); return; }
                Rank cur = pl.rank(d);
                if (d.rankExpire == 0) { if (cur.weight() < r.weight()) d.permRank = cur.id(); else { s.sendMessage("У игрока уже есть такой ранг или выше навсегда"); return; } }
                // продление того же временного ранга
                long base = d.rank.equals(r.id()) && d.rankExpire > System.currentTimeMillis() ? d.rankExpire : System.currentTimeMillis();
                d.rank = r.id();
                d.rankExpire = base + days * 86_400_000L;
            } else {
                d.rank = r.id();
                d.rankExpire = 0;
                d.permRank = null;
            }
            s.sendMessage("Ранг " + r.id() + " выдан " + d.name + (d.rankExpire > 0 ? " на " + a[3] + " дн." : " навсегда"));
            Player p = Bukkit.getPlayer(d.uuid);
            if (p != null) {
                pl.applyTeam(p);
                pl.msg(p, "<green>Ты получил привилегию </green>" + r.prefix().trim() + "<green>! Способности: /don");
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            if (!r.hidden() && r.priceAri() > 0)
                Bukkit.broadcast(mm("<gold>✦ </gold>").append(Component.text(d.name)).append(mm(" <gold>приобрёл привилегию </gold>" + r.prefix().trim() + "<gold>! <gray>/don")));
            pl.store().save(true);
            return;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("info")) {
            PlayerData d = pl.store().byName(a[1]);
            if (d == null) { s.sendMessage("Игрок не найден"); return; }
            s.sendMessage(d.name + ": " + d.rank + (d.rankExpire > 0 ? " до " + new Date(d.rankExpire) : " навсегда"));
            return;
        }
        s.sendMessage("rank set <ник> <ранг> [дней] | rank info <ник>");
    }

    private void ecoCmd(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage("eco give|take|set <ник> <сумма>"); return; }
        PlayerData d = pl.store().byName(a[1]);
        if (d == null) { s.sendMessage("Игрок не найден"); return; }
        long v;
        try { v = Long.parseLong(a[2]); } catch (Exception e) { s.sendMessage("Неверная сумма"); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "give" -> d.balance += v;
            case "take" -> d.balance = Math.max(0, d.balance - v);
            case "set" -> d.balance = v;
            default -> { s.sendMessage("eco give|take|set <ник> <сумма>"); return; }
        }
        s.sendMessage("Баланс " + d.name + ": " + d.balance);
    }

    /** cases give <ник> <кейс> <кол-во> | cases key <ник> <кейс> <кол-во> (предмет-ключ) - для админа и автодоната. */
    private boolean casesAdmin(CommandSender s, String[] a) {
        if (s.hasPermission("anarchy.admin") && a.length >= 2 && a[0].equalsIgnoreCase("demo")) {
            Cases.Case dc = pl.cases().get(a[1]);
            s.sendMessage(dc != null && pl.cases().demo(dc) ? "Демо-анимация запущена" : "cases demo <кейс> (кейс должен стоять в мире и не крутиться)");
            return true;
        }
        if (!s.hasPermission("anarchy.admin") || a.length < 3) return false;
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("give") && !sub.equals("take") && !sub.equals("key")) return false;
        PlayerData d = pl.store().byName(a[1]);
        Cases.Case c = pl.cases().get(a[2]);
        if (d == null || c == null) { s.sendMessage("cases give|take|key <ник> <" + String.join("|", pl.cases().all().stream().map(x -> x.id).toList()) + "> [кол-во]"); return true; }
        int n = a.length > 3 ? Integer.parseInt(a[3]) : 1;
        Player t = Bukkit.getPlayer(d.uuid);
        switch (sub) {
            case "give" -> { pl.cases().addKeys(d, c.id, n); if (t != null) { pl.msg(t, "<light_purple>Получено ключей: " + n + " <gray>(" + c.display + "<gray>) - /cases"); t.playSound(t.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.5f); } }
            case "take" -> pl.cases().addKeys(d, c.id, -Math.min(n, pl.cases().keys(d, c.id)));
            case "key" -> { if (t == null) { s.sendMessage("Игрок не в сети"); return true; } t.getInventory().addItem(pl.cases().keyItem(c.id, n)); }
        }
        s.sendMessage(d.name + ": ключей " + c.id + " = " + pl.cases().keys(d, c.id));
        pl.store().save(true);
        return true;
    }

    private void coreCmd(CommandSender s, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("reload")) { pl.reload(); s.sendMessage("Конфиг перезагружен"); return; }
        if (a.length >= 1 && a[0].equalsIgnoreCase("save")) { pl.store().save(true); pl.regions().save(); s.sendMessage("Сохранено"); return; }
        if (a.length >= 1 && a[0].equalsIgnoreCase("setspawn") && s instanceof Player p) { pl.setSpawn(p.getLocation()); s.sendMessage("Спавн установлен"); return; }
        if (a.length >= 3 && a[0].equalsIgnoreCase("spawnbuilder")) {
            List<String> list = new ArrayList<>(pl.getConfig().getStringList("spawn.builders"));
            list.removeIf(n -> n.equalsIgnoreCase(a[2]));
            if (a[1].equalsIgnoreCase("add")) list.add(a[2]);
            pl.getConfig().set("spawn.builders", list);
            pl.saveConfig();
            s.sendMessage("Могут строить на спавне: " + (list.isEmpty() ? "никто" : String.join(", ", list)));
            return;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("holo")) { pl.holograms().spawnAll(); pl.cases().placeBlocks(); s.sendMessage("Голограммы и кейсы пересозданы"); return; }
        if (a.length >= 1 && a[0].equalsIgnoreCase("where") && s instanceof Player p) { Location l = p.getLocation(); s.sendMessage(l.getWorld().getName() + ";" + (Math.round(l.getX() * 2) / 2.0) + ";" + (Math.round(l.getY() * 2) / 2.0) + ";" + (Math.round(l.getZ() * 2) / 2.0)); return; }
        if (a.length >= 2 && a[0].equalsIgnoreCase("event")) { s.sendMessage(pl.events().start(a[1]) ? "Ивент запущен" : "Ивенты: " + String.join(", ", MoreEvents.NAMES.keySet())); return; }
        if (a.length >= 2 && a[0].equalsIgnoreCase("give")) {
            Player t = a.length >= 3 ? Bukkit.getPlayerExact(a[2]) : s instanceof Player p ? p : null;
            ItemStack it = a[1].startsWith("spawner_") ? pl.items().spawner(org.bukkit.entity.EntityType.valueOf(a[1].substring(8).toUpperCase(Locale.ROOT)))
                    : a[1].startsWith("region_") ? pl.regions().item(a[1].substring(7)) : pl.items().create(a[1]);
            if (t == null || it == null) { s.sendMessage("core give <id> [ник]. id: " + String.join(", ", CustomItems.IDS) + ", region_<tier>, spawner_<mob>"); return; }
            t.getInventory().addItem(it);
            s.sendMessage("Выдано");
            return;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("resetpass")) {
            PlayerData d = pl.store().byName(a[1]);
            if (d == null) { s.sendMessage("Игрок не найден"); return; }
            d.passHash = null;
            Player p = Bukkit.getPlayer(d.uuid);
            if (p != null) p.kick(mm("<yellow>Пароль сброшен администратором. Зарегистрируйся заново."));
            s.sendMessage("Пароль " + d.name + " сброшен");
            return;
        }
        s.sendMessage("core reload | save | resetpass <ник> | setspawn | spawnbuilder add|remove <ник> | holo | where | event <" + String.join("|", MoreEvents.NAMES.keySet()) + "> | give <id> [ник]");
    }

    // ================= ТАБ =================
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        PlayerData d = s instanceof Player p ? pl.data(p) : null;
        if (a.length == 1) {
            switch (c) {
                case "home", "delhome" -> { if (d != null) out.addAll(d.homes.keySet()); }
                case "kit" -> { var ks = pl.getConfig().getConfigurationSection("kits"); if (ks != null) out.addAll(ks.getKeys(false)); }
                case "repair" -> out.add("all");
                case "rank" -> out.addAll(List.of("set", "info"));
                case "eco" -> out.addAll(List.of("give", "take", "set"));
                case "core" -> out.addAll(List.of("reload", "save", "resetpass", "setspawn", "holo", "where", "event", "give"));
                case "rtp" -> out.addAll(List.of("far", "player"));
                case "rg" -> out.addAll(List.of("info", "list", "add", "remove", "show", "delete"));
                case "ari" -> { out.addAll(List.of("give", "take", "set")); for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName()); }
                case "ptime" -> out.addAll(List.of("day", "night", "sunset", "reset"));
                case "pweather" -> out.addAll(List.of("clear", "rain", "reset"));
                case "color" -> out.addAll(List.of("#FF3377", "#00E5FF", "#FFD700", "reset"));
                case "register", "login", "changepassword", "sethome", "homes", "baltop", "top", "killtop", "donate", "don", "rules", "menu", "quests", "titles", "buffs", "shop", "market", "daily", "spawn", "fly", "trash", "anvil",
                     "hunt", "hat", "ghost", "ec", "craft", "feed", "heal", "back", "near", "tpaccept", "tpdeny", "suicide" -> {}
                default -> { for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName()); }
            }
        } else if (a.length == 2 && c.equals("core") && a[0].equalsIgnoreCase("event")) {
            out.addAll(MoreEvents.NAMES.keySet());
        } else if (a.length == 2 && c.equals("core") && a[0].equalsIgnoreCase("give")) {
            out.addAll(CustomItems.IDS);
        } else if (a.length == 2 && (c.equals("rank") || c.equals("eco") || c.equals("core") || c.equals("ari") || c.equals("rg"))) {
            for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName());
        } else if (a.length == 3 && c.equals("rank")) {
            for (Rank r : pl.ranks()) out.add(r.id());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
