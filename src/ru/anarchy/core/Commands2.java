package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Команды новых модулей. */
public final class Commands2 implements CommandExecutor, TabCompleter {
    public static final List<String> NAMES = List.of("clan", "cc", "ah", "boost", "pass", "pwarp", "pwarps", "event",
            "ban", "tempban", "banip", "unban", "mute", "unmute", "kick", "warn", "history", "check", "freeze", "vanish", "glog", "rollback", "admin", "aqua",
            "code", "codes", "report", "reports", "spec", "alts", "xray", "mod", "sell", "ac", "pvp", "warp", "seller-buyer", "mine", "bpshop", "monuments", "crafts");
    private static final Set<String> ADMIN = Set.of("ban", "tempban", "banip", "unban", "mute", "unmute", "kick", "warn", "history", "check", "freeze", "vanish", "glog", "rollback", "admin", "alts");
    private final AnarchyCore pl;

    public Commands2(AnarchyCore pl) { this.pl = pl; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        if (ADMIN.contains(c)) { pl.admin().command(s, c, a); return true; }
        switch (c) {
            case "event" -> { pl.moreEvents().command(s, a); return true; }
            case "aqua" -> { aqua(s, a); return true; }
            case "codes" -> { pl.moderation().codesCmd(s, a); return true; }
            case "reports" -> { pl.moderation().reportsCmd(s, a); return true; }
            case "xray" -> { pl.moderation().xrayCmd(s, a); return true; }
            case "mod" -> { pl.admin().modCmd(s, a); return true; }
            case "ac" -> { pl.anticheat().command(s, a); return true; }
            case "pvp" -> { pl.pvp().command(s, a); return true; }
            case "warp" -> { pl.warps().command(s, a); return true; }
            case "mine" -> { pl.mine().command(s, a); return true; }
            case "monuments" -> { pl.monuments().command(s, a); return true; }
            case "crafts" -> {
                if (s instanceof Player cp && !(a.length > 0 && a[0].equalsIgnoreCase("list") && s.hasPermission("anarchy.admin"))) pl.crafts().menu(cp);
                else pl.crafts().list(s);
                return true;
            }
            case "bpshop" -> {
                if (a.length > 0 && s.hasPermission("anarchy.admin")) { pl.bpShop().admin(s, a); return true; }
                if (s instanceof Player bp) pl.bpShop().open(bp); else s.sendMessage("bpshop give|take|set <ник> <кол-во>");
                return true;
            }
        }
        if (!(s instanceof Player p)) { s.sendMessage("Только для игроков"); return true; }
        switch (c) {
            case "clan" -> pl.clans().command(p, a);
            case "cc" -> { if (a.length == 0) pl.msg(p, "<red>/cc <сообщение>"); else pl.clans().chat(p, String.join(" ", a)); }
            case "ah" -> pl.auction().command(p, a);
            case "boost" -> pl.boosts().menu(p);
            case "pass" -> pl.pass().menu(p, pl.pass().level(pl.data(p)) / 9);
            case "pwarp" -> pl.pwarps().command(p, a);
            case "pwarps" -> pl.pwarps().menu(p, 0);
            case "code" -> pl.moderation().codeCmd(p, a);
            case "report" -> pl.moderation().reportCmd(p, a);
            case "spec" -> pl.moderation().specCmd(p, a);
            case "sell" -> pl.buyer().sellCommand(p, a);
            case "seller-buyer" -> pl.buyer().open(p);
            default -> {}
        }
        return true;
    }

    /** Админ/автодонат: /aqua sale|season|pass|boost|enchant ... (также через консоль и RCON). */
    private void aqua(CommandSender s, String[] a) {
        if (!s.hasPermission("anarchy.admin")) { s.sendMessage(mm("<red>Нет прав")); return; }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "sale" -> {
                    if (a.length < 3) { s.sendMessage("aqua sale <процент> <часов> [название] | aqua sale off | сейчас скидка: " + pl.boosts().salePercent() + "%"); if (a.length == 2 && a[1].equals("off")) { pl.boosts().setSale(0, 0, ""); s.sendMessage("Акция выключена"); } return; }
                    pl.boosts().setSale(Integer.parseInt(a[1]), Integer.parseInt(a[2]), a.length > 3 ? Util.join(a, 3) : "Акция");
                    s.sendMessage("Акция запущена: -" + a[1] + "% на " + a[2] + " ч");
                }
                case "startsale" -> {
                    pl.getConfig().set("sales.start-until", System.currentTimeMillis() + Long.parseLong(a[1]) * 86_400_000L);
                    if (a.length > 2) pl.getConfig().set("sales.start-percent", Integer.parseInt(a[2]));
                    pl.saveConfig();
                    s.sendMessage("Скидки на открытии включены на " + a[1] + " дн.");
                }
                case "pass" -> {
                    PlayerData d = pl.store().byName(a[2]);
                    if (d == null) { s.sendMessage("Игрок не найден"); return; }
                    if (a[1].equalsIgnoreCase("premium")) { pl.pass().setPremium(d); s.sendMessage("Премиум пропуска выдан " + d.name); }
                    else if (a[1].equalsIgnoreCase("xp")) { d.passXp += Integer.parseInt(a[3]); s.sendMessage("Опыт пропуска " + d.name + ": " + d.passXp); }
                }
                case "boost" -> {
                    pl.boosts().activate(a[1], a.length < 3 || a[2].equalsIgnoreCase("coins"), a.length > 3 ? Integer.parseInt(a[3]) : pl.getConfig().getInt("boosts.minutes", 60));
                    s.sendMessage("Буст активирован");
                }
                case "enchant" -> {
                    Player t = Bukkit.getPlayerExact(a[1]);
                    var it = pl.enchants().book(a[2], a.length > 3 ? Integer.parseInt(a[3]) : 1);
                    if (t == null || it == null) { s.sendMessage("aqua enchant <ник> <" + String.join("|", Enchants.ALL.keySet()) + "> [уровень]"); return; }
                    Util.give(t, it);
                    s.sendMessage("Книга выдана");
                }
                case "npc" -> { pl.npcs().spawnAll(); s.sendMessage("NPC пересозданы"); }
                case "seasonal" -> {
                    List<String> l = new ArrayList<>(pl.getConfig().getStringList("seasonal-force"));
                    if (!l.remove(a[1])) l.add(a[1]);
                    pl.getConfig().set("seasonal-force", l); pl.saveConfig();
                    s.sendMessage("Принудительно включённые сезонные кейсы: " + l);
                }
                default -> s.sendMessage(mm("<gold>/aqua sale <процент> <часов> [название] <gray>- акция на донат\n<gold>/aqua startsale <дней> [процент] <gray>- скидки на открытии\n"
                        + "<gold>/aqua pass premium <ник> | pass xp <ник> <n>\n"
                        + "<gold>/aqua boost <ник> [coins|pass] [минут] <gray>- буст (автодонат)\n<gold>/aqua enchant <ник> <id> [ур.] <gray>- книга зачарования\n"
                        + "<gold>/aqua seasonal <newyear|halloween> <gray>- вкл/выкл сезонный кейс вне дат\n<gold>/aqua npc <gray>- пересоздать NPC"));
            }
        } catch (Exception e) { s.sendMessage("Ошибка в аргументах: /aqua"); }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        if (c.equals("ac")) return pl.anticheat().tab(s, a);
        if (c.equals("warp")) return a.length == 1 ? pl.warps().names().stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(a[0].toLowerCase(Locale.ROOT))).toList() : List.of();
        if (c.equals("mine")) return a.length == 1 && s.hasPermission("anarchy.admin") ? java.util.stream.Stream.of("reset", "build", "remove", "reload").filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList() : List.of();
        if (c.equals("seller-buyer")) return List.of();
        if (c.equals("pvp")) return a.length == 1 && (s.isOp() || s.hasPermission("anarchy.admin")) ? java.util.stream.Stream.of("elite", "refill", "hub", "unhub", "rebuild", "help").filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList() : List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            switch (c) {
                case "clan" -> out.addAll(List.of("create", "invite", "accept", "deny", "leave", "kick", "deposit", "withdraw", "sethome", "home", "chat", "war", "info", "top", "promote", "demote", "owner", "color", "disband", "level", "chest", "buffs", "zones"));
                case "ah" -> out.addAll(List.of("sell", "search", "my", "storage"));
                case "sell" -> out.addAll(List.of("hand", "all", "prices"));
                case "pwarp" -> { out.addAll(List.of("set", "del", "ad", "my", "help")); out.addAll(pl.pwarps().names()); }
                case "event" -> { out.addAll(List.of("delay", "join", "leave")); if (pl.admin().staff(s)) out.addAll(List.of("start", "stop", "skip", "loot")); }
                case "aqua" -> out.addAll(List.of("sale", "startsale", "pass", "boost", "enchant", "seasonal", "npc"));
                case "check" -> { out.addAll(List.of("stop", "ban")); for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName()); }
                case "glog" -> { out.addAll(List.of("inspect", "near")); for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName()); }
                case "cc", "boost", "pass", "pwarps", "admin", "vanish", "code", "crafts" -> {}
                case "bpshop" -> { if (s.hasPermission("anarchy.admin")) out.addAll(List.of("give", "take", "set")); }
                case "monuments" -> { if (s.hasPermission("anarchy.admin")) out.addAll(List.of("list", "tp", "regen", "remove", "gen")); }
                case "codes" -> out.addAll(List.of("create", "creator", "info", "del", "list"));
                case "reports" -> out.addAll(List.of("all", "close", "closeall"));
                default -> { for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName()); }
            }
        } else if (a.length == 2) {
            switch (c) {
                case "event" -> { if (a[0].equalsIgnoreCase("start") || a[0].equalsIgnoreCase("stop")) out.addAll(MoreEvents.NAMES.keySet()); else if (a[0].equalsIgnoreCase("loot")) out.addAll(List.of("airdrop", "meteor", "boss", "zeus")); }
                case "ban", "tempban", "mute", "rollback" -> out.addAll(List.of("30m", "1h", "1d", "7d", "30d", "perm"));
                case "codes" -> { if (!a[0].equalsIgnoreCase("create") && !a[0].equalsIgnoreCase("creator")) out.addAll(pl.moderation().codeIds()); }
                case "check", "clan" -> { for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName()); }
                case "aqua" -> { if (a[0].equalsIgnoreCase("pass")) out.addAll(List.of("premium", "xp")); else if (a[0].equalsIgnoreCase("seasonal")) out.addAll(List.of("newyear", "halloween")); else for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName()); }
                default -> {}
            }
        } else if (a.length == 3 && c.equals("aqua") && a[0].equalsIgnoreCase("enchant")) out.addAll(Enchants.ALL.keySet());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
