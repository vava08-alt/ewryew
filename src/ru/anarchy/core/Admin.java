package ru.anarchy.core;

import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import ru.anarchy.core.ac.AntiCheat;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static ru.anarchy.core.AnarchyCore.esc;
import static ru.anarchy.core.AnarchyCore.mm;

/** Админ-панель: баны, муты, кики, варны, проверка на читы, заморозка, ваниш, журнал блоков и откат, GUI /admin. */
public final class Admin implements Listener {
    private record Punish(String name, UUID uuid, String ip, long until, String reason, String by) {
        boolean active() { return until == 0 || until > System.currentTimeMillis(); }
    }

    private final AnarchyCore pl;
    private final File file;
    private final Map<String, Punish> bans = new ConcurrentHashMap<>();     // ключ: ник в нижнем регистре
    private final Map<String, Punish> ipBans = new ConcurrentHashMap<>();   // ключ: ip
    private final Map<UUID, Punish> mutes = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> history = new ConcurrentHashMap<>();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> checks = new ConcurrentHashMap<>();                // проверяемый -> модератор
    private final Set<UUID> vanished = new HashSet<>();
    private final Set<UUID> inspectors = new HashSet<>();
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Europe/Moscow"));

    public Admin(AnarchyCore pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "punishments.yml");
        load();
        loadLog();
        Bukkit.getScheduler().runTaskTimer(pl, this::checkTick, 40, 40);
        Bukkit.getScheduler().runTaskTimerAsynchronously(pl, this::saveLogAsync, 6000, 6000);
    }

    public boolean isVanished(UUID u) { return vanished.contains(u); }

    public boolean staff(CommandSender s) {
        if (!(s instanceof Player p)) return true;
        return p.isOp() || p.hasPermission("anarchy.admin") || pl.hasPerk(pl.data(p), "mod");
    }

    // ================= хранение =================
    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        read(y.getConfigurationSection("bans"), (k, p) -> bans.put(k, p));
        read(y.getConfigurationSection("ipbans"), (k, p) -> ipBans.put(k.replace('_', '.'), p));
        read(y.getConfigurationSection("mutes"), (k, p) -> { if (p.uuid() != null) mutes.put(p.uuid(), p); });
        ConfigurationSection h = y.getConfigurationSection("history");
        if (h != null) for (String k : h.getKeys(false)) history.put(UUID.fromString(k), new ArrayList<>(h.getStringList(k)));
    }

    private void read(ConfigurationSection s, java.util.function.BiConsumer<String, Punish> put) {
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            String u = c.getString("uuid");
            Punish p = new Punish(c.getString("name", k), u == null ? null : UUID.fromString(u), c.getString("ip"), c.getLong("until"), c.getString("reason", ""), c.getString("by", "?"));
            if (p.active()) put.accept(k, p);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        write(y, "bans", bans);
        Map<String, Punish> ipb = new HashMap<>(); ipBans.forEach((k, v) -> ipb.put(k.replace('.', '_'), v));
        write(y, "ipbans", ipb);
        Map<String, Punish> m = new HashMap<>(); mutes.forEach((k, v) -> m.put(k.toString(), v));
        write(y, "mutes", m);
        history.forEach((k, v) -> y.set("history." + k, v));
        try { y.save(file); } catch (Exception e) { pl.getLogger().severe("punishments.yml: " + e); }
    }

    private void write(YamlConfiguration y, String sec, Map<String, Punish> map) {
        for (var e : map.entrySet()) {
            Punish p = e.getValue();
            if (!p.active()) continue;
            String k = sec + "." + e.getKey();
            y.set(k + ".name", p.name()); y.set(k + ".uuid", p.uuid() == null ? null : p.uuid().toString()); y.set(k + ".ip", p.ip());
            y.set(k + ".until", p.until()); y.set(k + ".reason", p.reason()); y.set(k + ".by", p.by());
        }
    }

    private void log(UUID u, String text) {
        if (u == null) return;
        List<String> l = history.computeIfAbsent(u, k -> new ArrayList<>());
        l.add(DF.format(Instant.now()) + " " + text);
        while (l.size() > 30) l.remove(0);
    }

    // ================= проверки входа / мута =================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        Punish b = bans.get(e.getName().toLowerCase(Locale.ROOT));
        if (b == null || !b.active()) b = ipBans.get(e.getAddress().getHostAddress());
        if (b != null && b.active()) e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(b));
    }

    private net.kyori.adventure.text.Component banScreen(Punish b) {
        return mm("<gradient:#00f0ff:#0080ff><bold>AquaAnarchy</bold></gradient>\n\n<red><bold>Ты заблокирован</bold>\n\n<gray>Причина: <white>" + esc(b.reason())
                + "\n<gray>Срок: <white>" + (b.until() == 0 ? "навсегда" : Util.left(b.until())) + "\n<gray>Выдал: <white>" + esc(b.by())
                + "\n\n<dark_gray>Обжалование: " + pl.getConfig().getString("admin.appeal", "discord сервера"));
    }

    public boolean isMuted(Player p) {
        Punish m = mutes.get(p.getUniqueId());
        if (m == null) return false;
        if (!m.active()) { mutes.remove(p.getUniqueId()); return false; }
        pl.msg(p, "<red>Ты в муте: <white>" + esc(m.reason()) + " <gray>(" + (m.until() == 0 ? "навсегда" : "ещё " + Util.left(m.until())) + ")");
        return true;
    }

    private static final Set<String> MUTE_CMDS = Set.of("msg", "tell", "w", "r", "m", "me", "whisper", "reply", "cc", "bc", "broadcast");

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String c = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (c.contains(":")) c = c.substring(c.indexOf(':') + 1);
        if (frozen.contains(p.getUniqueId()) && !List.of("mod", "login", "l", "log").contains(c)) { e.setCancelled(true); pl.msg(p, "<red>Ты заморожен. Писать модератору: <white>/mod <сообщение>"); return; }
        if ((MUTE_CMDS.contains(c) || (c.equals("clan") && e.getMessage().contains(" chat "))) && isMuted(p)) e.setCancelled(true);
    }

    // ================= наказания =================
    private PlayerData target(CommandSender s, String name) {
        PlayerData d = pl.store().byName(name);
        if (d == null) s.sendMessage(mm("<red>Игрок " + esc(name) + " не найден"));
        return d;
    }

    private String by(CommandSender s) { return s instanceof Player p ? p.getName() : "Консоль"; }

    public void ban(CommandSender s, PlayerData d, long ms, String reason, boolean ip) {
        long until = ms == 0 ? 0 : System.currentTimeMillis() + ms;
        Punish p = new Punish(d.name, d.uuid, d.lastIp, until, reason, by(s));
        bans.put(d.name.toLowerCase(Locale.ROOT), p);
        int ipCount = 0;
        if (ip) {
            Set<String> all = new LinkedHashSet<>(d.ips); if (d.lastIp != null) all.add(d.lastIp);
            all.removeIf(pl::sharedIp);   // 127.0.0.1 туннеля не баним и по нему не кикаем - иначе вылетят все
            for (String one : all) { ipBans.put(one, new Punish(d.name, d.uuid, one, until, reason, by(s))); ipCount++; }
            // кикаем всех, кто сейчас сидит с этих IP
            for (Player o : Bukkit.getOnlinePlayers()) if (o.getAddress() != null && all.contains(o.getAddress().getAddress().getHostAddress()) && !o.getUniqueId().equals(d.uuid) && !staff(o)) o.kick(banScreen(p));
            if (ipCount == 0) s.sendMessage(mm("<yellow>IP игрока в исключениях (127.0.0.1 - туннель), заблокирован только ник."));
            else s.sendMessage(mm("<gray>Заблокировано IP: <white>" + ipCount));
        }
        log(d.uuid, (ip ? "IP-бан " : "Бан ") + (ms == 0 ? "навсегда" : AnarchyCore.time(ms / 1000)) + " - " + reason + " (" + by(s) + ")");
        if (pl.anticheat() != null) pl.anticheat().banned(d.uuid, by(s));
        Player t = Bukkit.getPlayer(d.uuid);
        if (t != null) t.kick(banScreen(p));
        Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<red>" + esc(d.name) + " заблокирован " + (ms == 0 ? "навсегда" : "на " + AnarchyCore.time(ms / 1000)) + ". <gray>Причина: " + esc(reason)));
        save();
    }

    public void mute(CommandSender s, PlayerData d, long ms, String reason) {
        long until = ms == 0 ? 0 : System.currentTimeMillis() + ms;
        mutes.put(d.uuid, new Punish(d.name, d.uuid, null, until, reason, by(s)));
        log(d.uuid, "Мут " + (ms == 0 ? "навсегда" : AnarchyCore.time(ms / 1000)) + " - " + reason + " (" + by(s) + ")");
        Player t = Bukkit.getPlayer(d.uuid);
        if (t != null) pl.msg(t, "<red>Тебе выдан мут " + (ms == 0 ? "навсегда" : "на " + AnarchyCore.time(ms / 1000)) + ". Причина: " + esc(reason));
        Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<yellow>" + esc(d.name) + " получил мут " + (ms == 0 ? "навсегда" : "на " + AnarchyCore.time(ms / 1000)) + ". <gray>Причина: " + esc(reason)));
        save();
    }

    /** /ban /tempban /banip /unban /mute /unmute /kick /warn /history /check /freeze /vanish /glog /rollback /admin */
    public void command(CommandSender s, String cmd, String[] a) {
        if (!staff(s)) { s.sendMessage(mm("<red>Нет прав")); return; }
        switch (cmd) {
            case "ban", "tempban", "banip" -> {
                if (a.length < 1) { s.sendMessage(mm("<red>/" + cmd + " <ник" + (cmd.equals("banip") ? "|ip" : "") + "> [время 1h/7d/perm] [причина]")); return; }
                if (cmd.equals("banip") && a[0].matches("[0-9a-fA-F.:]+") && a[0].contains(".")) { banRawIp(s, a); return; }
                PlayerData d = target(s, a[0]); if (d == null) return;
                long ms = a.length > 1 && Util.isTime(a[1]) ? Util.parseTime(a[1]) : (cmd.equals("tempban") ? 86_400_000L : 0);
                String reason = Util.join(a, a.length > 1 && Util.isTime(a[1]) ? 2 : 1);
                if (ms == 0 && !(s instanceof Player pp && (pp.isOp() || pp.hasPermission("anarchy.admin"))) && s instanceof Player) ms = 30L * 86_400_000L; // модеры - максимум 30 дней
                ban(s, d, ms, reason.isBlank() ? "Нарушение правил" : reason, cmd.equals("banip"));
            }
            case "unban" -> {
                if (a.length < 1) { s.sendMessage(mm("<red>/unban <ник|ip>")); return; }
                Punish p = bans.remove(a[0].toLowerCase(Locale.ROOT));
                Punish ip = ipBans.remove(a[0]);
                if (p != null && p.ip() != null) ipBans.remove(p.ip());
                PlayerData ud = pl.store().byName(a[0]);
                if (ud != null) { for (String one : ud.ips) if (ipBans.remove(one) != null && ip == null) ip = p; ipBans.values().removeIf(x -> ud.uuid.equals(x.uuid())); }
                if (p != null) log(p.uuid(), "Разбан (" + by(s) + ")");
                save();
                s.sendMessage(mm(p != null || ip != null ? "<green>Разбанен: " + esc(a[0]) : "<red>Бан не найден"));
            }
            case "mute" -> {
                if (a.length < 1) { s.sendMessage(mm("<red>/mute <ник> [время] [причина]")); return; }
                PlayerData d = target(s, a[0]); if (d == null) return;
                long ms = a.length > 1 && Util.isTime(a[1]) ? Util.parseTime(a[1]) : 3_600_000L;
                String reason = Util.join(a, a.length > 1 && Util.isTime(a[1]) ? 2 : 1);
                mute(s, d, ms, reason.isBlank() ? "Нарушение правил чата" : reason);
            }
            case "unmute" -> {
                if (a.length < 1) { s.sendMessage(mm("<red>/unmute <ник>")); return; }
                PlayerData d = target(s, a[0]); if (d == null) return;
                s.sendMessage(mm(mutes.remove(d.uuid) != null ? "<green>Мут снят с " + esc(d.name) : "<red>У игрока нет мута"));
                log(d.uuid, "Снят мут (" + by(s) + ")");
                save();
            }
            case "kick" -> {
                Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : null;
                if (t == null) { s.sendMessage(mm("<red>/kick <ник> [причина] - игрок должен быть онлайн")); return; }
                String reason = a.length > 1 ? Util.join(a, 1) : "Кик модератором";
                log(t.getUniqueId(), "Кик - " + reason + " (" + by(s) + ")");
                t.kick(mm("<red>Тебя кикнули\n<gray>" + esc(reason)));
                Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<yellow>" + esc(t.getName()) + " кикнут. <gray>" + esc(reason)));
            }
            case "warn" -> {
                if (a.length < 1) { s.sendMessage(mm("<red>/warn <ник> [причина]")); return; }
                PlayerData d = target(s, a[0]); if (d == null) return;
                String reason = a.length > 1 ? Util.join(a, 1) : "Нарушение правил";
                d.warns++;
                log(d.uuid, "Варн " + d.warns + "/3 - " + reason + " (" + by(s) + ")");
                Player t = Bukkit.getPlayer(d.uuid);
                if (t != null) { pl.msg(t, "<red>Предупреждение " + d.warns + "/3: " + esc(reason)); t.showTitle(Title.title(mm("<red><bold>ПРЕДУПРЕЖДЕНИЕ " + d.warns + "/3"), mm("<white>" + esc(reason)))); }
                s.sendMessage(mm("<yellow>Варн выдан (" + d.warns + "/3)"));
                if (d.warns >= 3) { d.warns = 0; mute(s, d, 3_600_000L * 6, "3 предупреждения"); }
                save();
            }
            case "history", "hist" -> {
                if (a.length < 1) { s.sendMessage(mm("<red>/history <ник>")); return; }
                PlayerData d = target(s, a[0]); if (d == null) return;
                s.sendMessage(mm("<gold>История " + esc(d.name) + " <gray>(IP: " + (s instanceof Player pp && !pp.isOp() ? "скрыт" : d.lastIp) + ", варнов: " + d.warns + ")"));
                Punish b = bans.get(d.name.toLowerCase(Locale.ROOT)); Punish m = mutes.get(d.uuid);
                if (b != null && b.active()) s.sendMessage(mm(" <red>Сейчас в бане: " + esc(b.reason()) + " (" + Util.left(b.until()) + ")"));
                if (m != null && m.active()) s.sendMessage(mm(" <yellow>Сейчас в муте: " + esc(m.reason()) + " (" + Util.left(m.until()) + ")"));
                List<String> h = history.getOrDefault(d.uuid, List.of());
                if (h.isEmpty()) s.sendMessage(mm(" <gray>Наказаний не было"));
                for (String l : h) s.sendMessage(mm(" <gray>" + esc(l)));
                List<String> alts = pl.store().all().stream().filter(x -> d.lastIp != null && d.lastIp.equals(x.lastIp) && !x.uuid.equals(d.uuid) && !pl.sharedIp(d.lastIp)).map(x -> x.name).toList();
                if (!alts.isEmpty()) s.sendMessage(mm(" <gray>Твинки (тот же IP): <white>" + String.join(", ", alts)));
            }
            case "check" -> check(s, a);
            case "alts" -> alts(s, a);
            case "freeze" -> {
                Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : null;
                if (t == null) { s.sendMessage(mm("<red>/freeze <ник>")); return; }
                if (frozen.contains(t.getUniqueId())) { unfreeze(t.getUniqueId()); pl.msg(t, "<green>Тебя разморозили"); s.sendMessage(mm("<green>Разморожен")); }
                else { freeze(t); pl.msg(t, "<red>Тебя заморозил модератор"); s.sendMessage(mm("<yellow>Заморожен: " + esc(t.getName()))); }
            }
            case "vanish", "v" -> { if (s instanceof Player p) vanish(p); }
            case "glog" -> glog(s, a);
            case "rollback", "rb" -> rollback(s, a);
            case "admin", "panel" -> { if (s instanceof Player p) panel(p); }
            default -> {}
        }
    }

    // ================= проверка на читы =================
    private void banRawIp(CommandSender s, String[] a) {
        long ms = a.length > 1 && Util.isTime(a[1]) ? Util.parseTime(a[1]) : 0;
        if (ms == 0 && s instanceof Player pp && !(pp.isOp() || pp.hasPermission("anarchy.admin"))) ms = 30L * 86_400_000L;
        String reason = Util.join(a, a.length > 1 && Util.isTime(a[1]) ? 2 : 1);
        if (reason.isBlank()) reason = "Нарушение правил";
        long until = ms == 0 ? 0 : System.currentTimeMillis() + ms;
        if (pl.sharedIp(a[0])) { s.sendMessage(mm("<red>" + esc(a[0]) + " - общий адрес туннеля (в antibot.exempt-ips), бан по нему заблокирует всех игроков. Забань по нику.")); return; }
        Punish p = new Punish(a[0], null, a[0], until, reason, by(s));
        ipBans.put(a[0], p);
        int kicked = 0;
        for (Player o : Bukkit.getOnlinePlayers()) if (o.getAddress() != null && a[0].equals(o.getAddress().getAddress().getHostAddress()) && !staff(o)) { o.kick(banScreen(p)); kicked++; }
        for (PlayerData d : pl.store().all()) if (d.ips.contains(a[0])) log(d.uuid, "IP-бан " + a[0] + " - " + reason + " (" + by(s) + ")");
        save();
        s.sendMessage(mm("<green>IP " + a[0] + " заблокирован " + (ms == 0 ? "навсегда" : "на " + AnarchyCore.time(ms / 1000)) + ". Кикнуто: " + kicked));
    }

    /** Статус бана ника (null - не забанен) */
    public String banStatus(PlayerData d) {
        Punish b = bans.get(d.name.toLowerCase(Locale.ROOT));
        if (b != null && b.active()) return "бан " + (b.until() == 0 ? "навсегда" : Util.left(b.until())) + " - " + b.reason();
        for (String ip : d.ips) { Punish x = ipBans.get(ip); if (x != null && x.active()) return "IP-бан (" + ip + ") " + (x.until() == 0 ? "навсегда" : Util.left(x.until())) + " - " + x.reason(); }
        return null;
    }

    /** /alts <ник|ip> - все аккаунты с теми же IP, с банами */
    private void alts(CommandSender s, String[] a) {
        if (a.length < 1) { s.sendMessage(mm("<red>/alts <ник|ip>")); return; }
        Set<String> ips = new LinkedHashSet<>();
        String title;
        if (a[0].contains(".") && a[0].matches("[0-9a-fA-F.:]+")) { ips.add(a[0]); title = a[0]; }
        else { PlayerData d = target(s, a[0]); if (d == null) return; ips.addAll(d.ips); if (d.lastIp != null) ips.add(d.lastIp); title = d.name; }
        List<String> exempt = pl.getConfig().getStringList("antibot.exempt-ips");
        s.sendMessage(mm("<dark_gray>▬▬▬ <gold><bold>Аккаунты " + esc(title) + "</bold> <dark_gray>▬▬▬"));
        s.sendMessage(mm("<gray>IP (" + ips.size() + "): <white>" + String.join(", ", ips)));
        for (String ip : ips) {
            Punish ib = ipBans.get(ip);
            if (ib != null && ib.active()) s.sendMessage(mm(" <red>⛔ " + ip + " в IP-бане: " + esc(ib.reason())));
            if (exempt.contains(ip)) s.sendMessage(mm(" <yellow>⚠ " + ip + " - общий адрес туннеля, по нему видны ВСЕ игроки, не только твинки"));
        }
        List<PlayerData> list = new ArrayList<>();
        for (PlayerData x : pl.store().all()) {
            boolean match = (x.lastIp != null && ips.contains(x.lastIp));
            if (!match) for (String ip : x.ips) if (ips.contains(ip)) { match = true; break; }
            if (match) list.add(x);
        }
        list.sort(Comparator.comparingLong((PlayerData x) -> x.lastLogin).reversed());
        int banned = 0;
        for (PlayerData x : list) {
            String st = banStatus(x);
            if (st != null) banned++;
            boolean online = Bukkit.getPlayer(x.uuid) != null;
            Punish m = mutes.get(x.uuid);
            s.sendMessage(mm(" " + (online ? "<green>●" : "<dark_gray>●") + " <white>" + esc(x.name) + " <dark_gray>| <gray>вход " + (x.lastLogin == 0 ? "-" : DF.format(Instant.ofEpochMilli(x.lastLogin)))
                    + " <dark_gray>| <gray>" + x.playMinutes / 60 + " ч" + (st != null ? " <red>| ⛔ " + esc(st) : "") + (m != null && m.active() ? " <yellow>| мут" : "")
                    + (x.passHash == null ? " <dark_gray>(не зарег.)" : "")));
        }
        s.sendMessage(mm("<gray>Всего аккаунтов: <white>" + list.size() + " <gray>| в бане: <red>" + banned));
    }

    private void check(CommandSender s, String[] a) {
        if (a.length < 1) { s.sendMessage(mm("<red>/check <ник> <gray>- вызвать на проверку, <red>/check stop <ник>, /check ban <ник>")); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("stop") || sub.equals("ban")) && a.length > 1) {
            Player t = Bukkit.getPlayerExact(a[1]);
            PlayerData d = pl.store().byName(a[1]);
            if (d == null) { s.sendMessage(mm("<red>Игрок не найден")); return; }
            boolean wasChecked = checks.remove(d.uuid) != null;
            unfreeze(d.uuid);
            if (t != null) t.clearTitle();
            if (wasChecked && pl.anticheat() != null) pl.anticheat().checkEnded(d.uuid, sub.equals("ban"), by(s));   // решение модератора - урок для ИИ
            if (sub.equals("ban")) ban(s, d, 30L * 86_400_000L, "Читы (проверка)", false);
            else { if (t != null) { pl.msg(t, "<green>Проверка окончена, спасибо! Приятной игры."); t.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS); } log(d.uuid, "Проверка пройдена (" + by(s) + ")"); s.sendMessage(mm("<green>Проверка завершена")); }
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { s.sendMessage(mm("<red>Игрок не в сети")); return; }
        if (AntiCheat.AI.equals(checks.get(t.getUniqueId())) && s instanceof Player p) { pl.anticheat().accept(p, t.getName()); return; }   // ждёт модератора после вызова ИИ
        if (checks.containsKey(t.getUniqueId())) { s.sendMessage(mm("<red>Игрок уже на проверке")); return; }
        UUID mod = s instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        checks.put(t.getUniqueId(), mod);
        freeze(t);
        log(t.getUniqueId(), "Вызван на проверку (" + by(s) + ")");
        if (s instanceof Player p) pl.teleports().teleport(p, t.getLocation());
        pl.msg(t, "<red><bold>Ты вызван на проверку на читы!</bold>");
        pl.msg(t, "<white>Не выходи с сервера - выход = бан на 30 дней. <gray>Ты полностью заморожен. Связь с модератором <yellow>" + esc(by(s)) + "</yellow>: <white>/mod <сообщение>");
        pl.msg(t, "<gray>" + pl.getConfig().getString("admin.check-text", "Тебе скажут, как связаться (Discord / AnyDesk). Признание сокращает бан."));
        s.sendMessage(mm("<green>Проверка начата. <gray>Писать игроку: <white>/mod " + t.getName() + " <текст> <gray>Окончить: <white>/check stop " + t.getName() + " <gray>или <white>/check ban " + t.getName()));
        for (Player o : Bukkit.getOnlinePlayers()) if (staff(o) && o != s) pl.msg(o, "<gold>[Проверка] <white>" + esc(by(s)) + " проверяет " + esc(t.getName()));
    }

    private void checkTick() {
        for (var e : checks.entrySet()) {
            Player t = Bukkit.getPlayer(e.getKey());
            if (t == null) continue;
            boolean waiting = AntiCheat.AI.equals(e.getValue());
            t.showTitle(Title.title(mm(waiting ? "<red><bold>ВЫЗОВ НА ПРОВЕРКУ" : "<red><bold>ПРОВЕРКА НА ЧИТЫ"),
                    mm(waiting ? "<white>Тебя вызвал ИИ-античит. <yellow>Жди модератора, не выходи!" : "<white>Не выходи! Пиши модератору: <yellow>/mod <текст>"),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ZERO)));
        }
    }

    public boolean onCheck(UUID u) { return checks.containsKey(u); }

    /** ИИ-античит вызвал игрока на проверку: заморозка до тех пор, пока модератор не примет вызов. */
    public void aiCall(Player t, int caseId) {
        checks.put(t.getUniqueId(), AntiCheat.AI);
        freeze(t);
        log(t.getUniqueId(), "Вызван на проверку ИИ-античитом (дело #" + caseId + ")");
    }

    /** Модератор принял вызов ИИ: проверка становится его, как после /check. */
    public void aiAccept(Player mod, Player t) {
        checks.put(t.getUniqueId(), mod.getUniqueId());
        freeze(t);
        log(t.getUniqueId(), "Проверку по вызову ИИ принял " + mod.getName());
        pl.teleports().teleport(mod, t.getLocation());
    }

    /** Вызов ИИ отменён или никто его не принял. */
    public void aiRelease(Player t) {
        checks.remove(t.getUniqueId());
        unfreeze(t.getUniqueId());
        t.clearTitle();
    }

    // ================= полная заморозка =================
    public boolean isFrozen(Player p) { return frozen.contains(p.getUniqueId()); }

    private void freeze(Player t) {
        frozen.add(t.getUniqueId());
        pl.hold().set(t, "frozen", true);
    }

    private void unfreeze(UUID u) {
        frozen.remove(u);
        Player t = Bukkit.getPlayer(u);
        if (t != null) pl.hold().set(t, "frozen", false);
    }

    /** /mod <текст> - игрок на проверке пишет модератору; /mod <ник> <текст> - модератор пишет игроку. */
    public void modCmd(CommandSender s, String[] a) {
        if (s instanceof Player p && frozen.contains(p.getUniqueId())) {
            if (a.length == 0) { pl.msg(p, "<red>/mod <сообщение> <gray>- написать проверяющему модератору"); return; }
            String text = esc(String.join(" ", a));
            UUID mod = checks.get(p.getUniqueId());
            String line = "<dark_gray>[<red>Проверка</red>]</dark_gray> <white>" + esc(p.getName()) + " <dark_gray>→ <gold>модератору<dark_gray>: <white>" + text;
            p.sendMessage(mm(line));
            Set<CommandSender> to = new HashSet<>();
            if (mod != null) { Player m = Bukkit.getPlayer(mod); if (m != null) to.add(m); }
            for (Player o : Bukkit.getOnlinePlayers()) if (staff(o)) to.add(o);
            to.remove(p);
            for (CommandSender o : to) {
                o.sendMessage(mm(line).append(mm(" <yellow>[Ответить]").clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/mod " + p.getName() + " "))));
                if (o instanceof Player op) op.playSound(op.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
            }
            Bukkit.getConsoleSender().sendMessage(mm(line));
            return;
        }
        if (!staff(s)) { pl.msg(s, "<red>Команда только для игроков на проверке"); return; }
        if (a.length < 2) { pl.msg(s, "<red>/mod <ник> <сообщение> <gray>- написать замороженному игроку"); return; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || !frozen.contains(t.getUniqueId())) { pl.msg(s, "<red>Игрок не в сети или не заморожен"); return; }
        String line = "<dark_gray>[<red>Проверка</red>]</dark_gray> <gold>" + esc(by(s)) + " <dark_gray>→ <white>" + esc(t.getName()) + "<dark_gray>: <yellow>" + esc(Util.join(a, 1));
        t.sendMessage(mm(line));
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
        for (Player o : Bukkit.getOnlinePlayers()) if (staff(o) && o != t) o.sendMessage(mm(line));
        if (!(s instanceof Player)) s.sendMessage(mm(line));
    }

    private boolean fz(org.bukkit.entity.Entity e) { return e instanceof Player p && frozen.contains(p.getUniqueId()); }
    private void fzMsg(Player p) { p.sendActionBar(mm("<red>Ты заморожен. <white>Пиши модератору: <yellow>/mod <текст>")); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        pl.msg(e.getPlayer(), "<red>Чат недоступен на проверке. <white>Пиши модератору: <yellow>/mod <текст>");
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenDrop(PlayerDropItemEvent e) { if (fz(e.getPlayer())) { e.setCancelled(true); fzMsg(e.getPlayer()); } }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenPickup(org.bukkit.event.entity.EntityPickupItemEvent e) { if (fz(e.getEntity())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenClick(org.bukkit.event.inventory.InventoryClickEvent e) { if (fz(e.getWhoClicked())) { e.setCancelled(true); fzMsg((Player) e.getWhoClicked()); } }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenDrag(org.bukkit.event.inventory.InventoryDragEvent e) { if (fz(e.getWhoClicked())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenOpen(org.bukkit.event.inventory.InventoryOpenEvent e) { if (fz(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenInteract(PlayerInteractEvent e) { if (fz(e.getPlayer())) { e.setCancelled(true); e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY); e.setUseItemInHand(org.bukkit.event.Event.Result.DENY); } }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenInteractEntity(PlayerInteractEntityEvent e) { if (fz(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenArmorStand(PlayerArmorStandManipulateEvent e) { if (fz(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenBreak(BlockBreakEvent e) { if (fz(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenPlace(BlockPlaceEvent e) { if (fz(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenDamage(org.bukkit.event.entity.EntityDamageEvent e) { if (fz(e.getEntity())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenAttack(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        org.bukkit.entity.Entity d = e.getDamager();
        if (d instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof org.bukkit.entity.Entity sh) d = sh;
        if (fz(d)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) { if (e.getEntity().getShooter() instanceof Player p && fz(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenConsume(PlayerItemConsumeEvent e) { if (fz(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenSwap(PlayerSwapHandItemsEvent e) { if (fz(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenHunger(org.bukkit.event.entity.FoodLevelChangeEvent e) { if (fz(e.getEntity()) && e.getFoodLevel() < e.getEntity().getFoodLevel()) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenGlide(org.bukkit.event.entity.EntityToggleGlideEvent e) { if (fz(e.getEntity()) && e.isGliding()) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent e) { if (fz(e.getTarget())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenTeleport(PlayerTeleportEvent e) {
        if (!fz(e.getPlayer())) return;
        var c = e.getCause();
        if (c != PlayerTeleportEvent.TeleportCause.PLUGIN && c != PlayerTeleportEvent.TeleportCause.COMMAND && c != PlayerTeleportEvent.TeleportCause.UNKNOWN) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenPortal(PlayerPortalEvent e) { if (fz(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (frozen.contains(e.getPlayer().getUniqueId()) && e.hasChangedPosition()) {
            Location f = e.getFrom().clone(); f.setYaw(e.getTo().getYaw()); f.setPitch(e.getTo().getPitch());
            e.setTo(f);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        inspectors.remove(p.getUniqueId());
        UUID checker = checks.remove(p.getUniqueId());
        if (checker != null) {
            frozen.remove(p.getUniqueId());
            boolean ban = pl.anticheat() == null || pl.anticheat().quitDuringCheck(p.getUniqueId(), AntiCheat.AI.equals(checker));
            PlayerData d = pl.data(p);
            // кик сервером (античит Paper, /kick) - не лив игрока, бан только за сам выход
            if (e.getReason() == PlayerQuitEvent.QuitReason.KICKED) {
                ban = false;
                log(p.getUniqueId(), "Кикнут сервером во время проверки - бан за выход не выдан");
                for (Player o : Bukkit.getOnlinePlayers()) if (staff(o)) pl.msg(o, "<gold>[Проверка] <white>" + esc(p.getName()) + " <gray>кикнут сервером во время проверки, бан не выдан");
            }
            if (ban) Bukkit.getScheduler().runTask(pl, () -> ban(Bukkit.getConsoleSender(), d, 30L * 86_400_000L, "Выход с проверки на читы", false));
        }
        vanished.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!staff(p)) for (UUID u : vanished) { Player v = Bukkit.getPlayer(u); if (v != null) p.hidePlayer(pl, v); }
    }

    private void vanish(Player p) {
        if (vanished.remove(p.getUniqueId())) {
            for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(pl, p);
            pl.msg(p, "<yellow>Ваниш выключен - тебя видно");
        } else {
            vanished.add(p.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) if (!staff(o)) o.hidePlayer(pl, p);
            pl.msg(p, "<green>Ваниш включён - игроки тебя не видят");
        }
    }

    public boolean vanished(Player p) { return vanished.contains(p.getUniqueId()); }

    // ================= журнал блоков =================
    private static final int CAP = 300_000;
    private final long[] lt = new long[CAP];
    private final int[] lx = new int[CAP], ly = new int[CAP], lz = new int[CAP];
    private final String[] lw = new String[CAP], lp = new String[CAP], lold = new String[CAP], lnew = new String[CAP];
    private final byte[] la = new byte[CAP]; // 1 поставил, 2 сломал, 3 взрыв, 4 сгорел
    private int head, size;
    private final Map<String, String> intern = new HashMap<>();
    private String in(String s) { if (s == null) return null; String r = intern.putIfAbsent(s, s); return r == null ? s : r; }

    private synchronized void record(String player, Block b, byte action, String oldData, String newData) {
        int i = head;
        lt[i] = System.currentTimeMillis(); lx[i] = b.getX(); ly[i] = b.getY(); lz[i] = b.getZ();
        lw[i] = in(b.getWorld().getName()); lp[i] = in(player); lold[i] = in(oldData); lnew[i] = in(newData); la[i] = action;
        head = (head + 1) % CAP; size = Math.min(CAP, size + 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (pl.worlds().isHub(e.getBlock().getWorld())) return;
        record(e.getPlayer().getName(), e.getBlock(), (byte) 1, e.getBlockReplacedState().getBlockData().getAsString(), e.getBlock().getBlockData().getAsString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (pl.worlds().isHub(e.getBlock().getWorld())) return;
        record(e.getPlayer().getName(), e.getBlock(), (byte) 2, e.getBlock().getBlockData().getAsString(), "minecraft:air");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        String who = "#взрыв";
        if (e.getEntity() instanceof org.bukkit.entity.TNTPrimed t && t.getSource() instanceof Player p) who = p.getName();
        else if (e.getEntity() instanceof org.bukkit.entity.Creeper c && c.getTarget() instanceof Player p) who = "#крипер(" + p.getName() + ")";
        else if (e.getEntity() instanceof org.bukkit.entity.EnderCrystal) who = "#кристалл";
        for (Block b : e.blockList()) record(who, b, (byte) 3, b.getBlockData().getAsString(), "minecraft:air");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { record("#огонь", e.getBlock(), (byte) 4, e.getBlock().getBlockData().getAsString(), "minecraft:air"); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInspect(PlayerInteractEvent e) {
        if (!inspectors.contains(e.getPlayer().getUniqueId()) || e.getClickedBlock() == null || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        e.setCancelled(true);
        Block b = e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK ? e.getClickedBlock().getRelative(e.getBlockFace()) : e.getClickedBlock();
        showAt(e.getPlayer(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), 0);
    }

    private static String act(byte a) { return switch (a) { case 1 -> "<green>поставил"; case 2 -> "<red>сломал"; case 3 -> "<gold>взорвал"; case 4 -> "<yellow>сжёг"; default -> "?"; }; }
    private static String mat(String data) { if (data == null) return "?"; String s = data.replace("minecraft:", ""); int i = s.indexOf('['); return i > 0 ? s.substring(0, i) : s; }

    private synchronized void showAt(Player p, String w, int x, int y, int z, int r) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < size && out.size() < 10; k++) {
            int i = Math.floorMod(head - 1 - k, CAP);
            if (!w.equals(lw[i]) || Math.abs(lx[i] - x) > r || Math.abs(ly[i] - y) > r || Math.abs(lz[i] - z) > r) continue;
            out.add("<gray>" + DF.format(Instant.ofEpochMilli(lt[i])) + " <white>" + esc(lp[i]) + " " + act(la[i]) + " <white>" + mat(la[i] == 1 ? lnew[i] : lold[i]) + (r > 0 ? " <dark_gray>(" + lx[i] + " " + ly[i] + " " + lz[i] + ")" : ""));
        }
        p.sendMessage(mm("<gold>Журнал " + (r > 0 ? "рядом (r=" + r + ")" : x + " " + y + " " + z) + ":"));
        if (out.isEmpty()) p.sendMessage(mm(" <gray>Записей нет"));
        for (String s : out) p.sendMessage(mm(" " + s));
    }

    private void glog(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) return;
        String sub = a.length == 0 ? "i" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "i", "inspect" -> {
                if (inspectors.remove(p.getUniqueId())) pl.msg(p, "<yellow>Инспектор выключен");
                else { inspectors.add(p.getUniqueId()); pl.msg(p, "<green>Инспектор включён: <gray>ЛКМ по блоку - кто его ставил/ломал, ПКМ - кто ставил на это место. Выкл: /glog"); }
            }
            case "near" -> { int r = a.length > 1 ? parse(a[1], 5) : 5; Location l = p.getLocation(); showAt(p, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), Math.min(30, r)); }
            default -> {
                long since = System.currentTimeMillis() - (a.length > 1 && Util.isTime(a[1]) ? Util.parseTime(a[1]) : 86_400_000L);
                int[] c = new int[5];
                synchronized (this) { for (int k = 0; k < size; k++) { int i = Math.floorMod(head - 1 - k, CAP); if (lt[i] < since) break; if (a[0].equalsIgnoreCase(lp[i])) c[la[i]]++; } }
                p.sendMessage(mm("<gold>" + esc(a[0]) + ": <green>поставил " + c[1] + " <red>сломал " + c[2] + " <gold>взорвал " + c[3] + " <gray>за период. Откат: <white>/rollback " + esc(a[0]) + " 1h [радиус]"));
            }
        }
    }

    private static int parse(String s, int d) { try { return Integer.parseInt(s); } catch (Exception e) { return d; } }

    private void rollback(CommandSender s, String[] a) {
        if (a.length < 2 || !Util.isTime(a[1])) { s.sendMessage(mm("<red>/rollback <ник> <время 30m/2h/1d> [радиус] <gray>- откатить постройки/разрушения игрока")); return; }
        String who = a[0];
        long since = System.currentTimeMillis() - Util.parseTime(a[1]);
        int r = a.length > 2 ? parse(a[2], -1) : -1;
        Location c = s instanceof Player p ? p.getLocation() : null;
        int n = 0;
        synchronized (this) {
            for (int k = 0; k < size; k++) {
                int i = Math.floorMod(head - 1 - k, CAP);
                if (lt[i] < since) break;
                if (!who.equalsIgnoreCase(lp[i]) || lt[i] == 0) continue;
                World w = Bukkit.getWorld(lw[i]);
                if (w == null) continue;
                if (r > 0 && (c == null || !c.getWorld().equals(w) || Math.abs(lx[i] - c.getBlockX()) > r || Math.abs(lz[i] - c.getBlockZ()) > r)) continue;
                try {
                    BlockData bd = Bukkit.createBlockData(lold[i]);
                    w.getBlockAt(lx[i], ly[i], lz[i]).setBlockData(bd, false);
                    lt[i] = 0; // запись использована
                    n++;
                } catch (Exception ignored) {}
            }
        }
        s.sendMessage(mm("<green>Откат " + esc(who) + " за " + a[1] + ": восстановлено блоков: <white>" + n + (r > 0 ? " <gray>(радиус " + r + ")" : "")));
        pl.getLogger().info(by(s) + " откатил " + who + " за " + a[1] + ": " + n + " блоков");
    }

    private void saveLogAsync() { saveLog(); }

    public synchronized void saveLog() {
        File f = new File(pl.getDataFolder(), "blocklog.dat");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(f))))) {
            o.writeInt(size);
            for (int k = size - 1; k >= 0; k--) {
                int i = Math.floorMod(head - 1 - k, CAP);
                o.writeLong(lt[i]); o.writeInt(lx[i]); o.writeInt(ly[i]); o.writeInt(lz[i]); o.writeByte(la[i]);
                o.writeUTF(lw[i] == null ? "" : lw[i]); o.writeUTF(lp[i] == null ? "" : lp[i]); o.writeUTF(lold[i] == null ? "" : lold[i]); o.writeUTF(lnew[i] == null ? "" : lnew[i]);
            }
        } catch (Exception e) { pl.getLogger().warning("blocklog.dat: " + e); }
    }

    private synchronized void loadLog() {
        File f = new File(pl.getDataFolder(), "blocklog.dat");
        if (!f.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))))) {
            int n = in.readInt();
            for (int k = 0; k < n; k++) {
                int i = head;
                lt[i] = in.readLong(); lx[i] = in.readInt(); ly[i] = in.readInt(); lz[i] = in.readInt(); la[i] = in.readByte();
                lw[i] = in(in.readUTF()); lp[i] = in(in.readUTF()); lold[i] = in(in.readUTF()); lnew[i] = in(in.readUTF());
                head = (head + 1) % CAP; size = Math.min(CAP, size + 1);
            }
        } catch (Exception e) { pl.getLogger().warning("blocklog.dat: " + e); }
    }

    // ================= GUI /admin =================
    public void panel(Player p) {
        Menus.Menu m = new Menus.Menu(6, "<gradient:#ff4040:#ff9900><bold>Админ-панель</bold></gradient> <dark_gray>| онлайн " + Bukkit.getOnlinePlayers().size());
        int slot = 0;
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (slot >= 36) break;
            PlayerData d = pl.data(t);
            ItemStack h = Menus.icon(Material.PLAYER_HEAD, "<white><bold>" + esc(t.getName()), "<gray>Ранг: " + pl.rank(d).prefix(), "<gray>Пинг: <white>" + t.getPing() + " мс",
                    "<gray>Мир: <white>" + t.getWorld().getName() + " " + t.getLocation().getBlockX() + " " + t.getLocation().getBlockZ(),
                    "<gray>Убийств: <red>" + d.kills + " <gray>Варнов: <white>" + d.warns, mutes.containsKey(t.getUniqueId()) ? "<yellow>В муте" : "", checks.containsKey(t.getUniqueId()) ? "<red>На проверке" : "", "", "<yellow>▶ Действия");
            SkullMeta sm = (SkullMeta) h.getItemMeta(); sm.setOwningPlayer(t); h.setItemMeta(sm);
            m.set(slot++, h, c -> playerMenu(p, t));
        }
        m.set(45, Menus.icon(Material.ENDER_EYE, vanished(p) ? "<green><bold>Ваниш: ВКЛ" : "<gray><bold>Ваниш: ВЫКЛ", "<gray>Невидимость для игроков"), c -> { vanish(p); panel(p); });
        m.set(46, Menus.icon(Material.SPYGLASS, inspectors.contains(p.getUniqueId()) ? "<green><bold>Инспектор: ВКЛ" : "<gray><bold>Инспектор: ВЫКЛ", "<gray>Журнал блоков по клику"), c -> { p.closeInventory(); glog(p, new String[]{"i"}); });
        m.set(47, Menus.icon(Material.BOOK, "<gold><bold>Журнал рядом", "<gray>Кто что ставил/ломал (r=8)"), c -> { p.closeInventory(); glog(p, new String[]{"near", "8"}); });
        int open = pl.moderation().openReports();
        m.set(48, Menus.icon(open > 0 ? Material.WRITABLE_BOOK : Material.BOOK, "<red><bold>Жалобы и /spec" + (open > 0 ? " <white>(" + open + ")" : ""), "<gray>Жалобы игроков, просьбы проследить,", "<gray>подозрения на X-Ray", "", "<yellow>▶ Открыть"), c -> pl.moderation().reportsMenu(p));
        m.set(49, Menus.icon(Material.FIRE_CHARGE, "<red><bold>Запустить ивент", "<gray>Аирдроп, босс, метеорит,", "<gray>Стрела Зевса, Подземелье дьявола, турнир"), c -> eventsMenu(p));
        m.set(50, Menus.icon(Material.HOPPER, "<yellow><bold>Очистить предметы", "<gray>Удалить предметы на земле сейчас"), c -> {
            int n = 0; for (World w : Bukkit.getWorlds()) for (var e : w.getEntitiesByClass(org.bukkit.entity.Item.class)) { e.remove(); n++; }
            pl.msg(p, "<green>Удалено предметов: " + n);
        });
        m.set(51, Menus.icon(Material.WRITABLE_BOOK, "<aqua><bold>Сохранить данные", "<gray>Игроки, приваты, кланы, аукцион"), c -> { Bukkit.dispatchCommand(p, "core save"); });
        m.set(52, Menus.icon(Material.BEACON, "<gradient:#00f0ff:#0080ff><bold>ИИ-античит</bold></gradient>", "<gray>Вызовы на проверку, подозрительные", "<gray>игроки, досье и модель ИИ", "", "<yellow>▶ Открыть"), c -> pl.anticheat().menu(p));
        m.set(53, Menus.icon(Material.BARRIER, "<red>Закрыть"), c -> p.closeInventory());
        m.fill();
        p.openInventory(m.getInventory());
    }

    private void playerMenu(Player p, Player t) {
        PlayerData d = pl.data(t);
        Menus.Menu m = new Menus.Menu(4, "<dark_gray>Игрок: <white>" + t.getName());
        m.set(4, Menus.icon(Material.PLAYER_HEAD, "<white><bold>" + esc(t.getName()), "<gray>IP-аккаунтов: <white>" + (pl.sharedIp(d.lastIp) ? "туннель" : String.valueOf(pl.store().all().stream().filter(x -> d.lastIp != null && d.lastIp.equals(x.lastIp)).count())), "<gray>Наиграно: <white>" + d.playMinutes / 60 + " ч", "", "<yellow>▶ Все аккаунты (/alts)"), c -> { p.closeInventory(); alts(p, new String[]{t.getName()}); });
        m.set(22, Menus.icon(Material.ENDER_EYE, "<aqua><bold>Следить (/spec)", "<gray>Режим наблюдателя за игроком"), c -> { p.closeInventory(); pl.moderation().spectate(p, t); });
        m.set(26, Menus.icon(Material.DIAMOND_ORE, "<aqua><bold>X-Ray статистика"), c -> { p.closeInventory(); pl.moderation().xrayInfo(p, d); });
        m.set(10, Menus.icon(Material.ENDER_PEARL, "<aqua><bold>Телепорт к игроку"), c -> { p.closeInventory(); pl.teleports().teleport(p, t.getLocation()); });
        m.set(11, Menus.icon(Material.CHEST, "<white><bold>Инвентарь", "<gray>/invsee"), c -> p.performCommand("invsee " + t.getName()));
        m.set(12, Menus.icon(Material.ICE, frozen.contains(t.getUniqueId()) ? "<aqua><bold>Разморозить" : "<aqua><bold>Заморозить"), c -> { command(p, "freeze", new String[]{t.getName()}); playerMenu(p, t); });
        m.set(13, Menus.icon(Material.SPYGLASS, checks.containsKey(t.getUniqueId()) ? "<green><bold>Завершить проверку" : "<red><bold>Вызвать на проверку", "<gray>Заморозка, выход = бан 30 дней"), c -> {
            p.closeInventory(); command(p, "check", checks.containsKey(t.getUniqueId()) ? new String[]{"stop", t.getName()} : new String[]{t.getName()}); });
        m.set(14, Menus.icon(Material.PAPER, "<yellow><bold>Варн", "<gray>3 варна = мут 6 ч"), c -> { command(p, "warn", new String[]{t.getName(), "Нарушение правил"}); playerMenu(p, t); });
        m.set(15, Menus.icon(Material.BOOK, "<gold><bold>История наказаний"), c -> { p.closeInventory(); command(p, "history", new String[]{t.getName()}); });
        m.set(16, Menus.icon(Material.LEATHER_BOOTS, "<yellow><bold>Кикнуть"), c -> { p.closeInventory(); command(p, "kick", new String[]{t.getName(), "Кик модератором"}); });
        m.set(19, Menus.icon(Material.ORANGE_DYE, "<gold><bold>Мут 1 час"), c -> { mute(p, d, 3_600_000L, "Нарушение правил чата"); playerMenu(p, t); });
        m.set(20, Menus.icon(Material.RED_DYE, "<red><bold>Мут 1 день"), c -> { mute(p, d, 86_400_000L, "Нарушение правил чата"); playerMenu(p, t); });
        m.set(21, Menus.icon(Material.LIME_DYE, "<green><bold>Снять мут"), c -> { command(p, "unmute", new String[]{t.getName()}); playerMenu(p, t); });
        m.set(23, Menus.icon(Material.IRON_BARS, "<red><bold>Бан 1 день"), c -> { p.closeInventory(); ban(p, d, 86_400_000L, "Нарушение правил", false); });
        m.set(24, Menus.icon(Material.IRON_BLOCK, "<red><bold>Бан 7 дней"), c -> { p.closeInventory(); ban(p, d, 7 * 86_400_000L, "Нарушение правил", false); });
        m.set(25, Menus.icon(Material.NETHERITE_BLOCK, "<dark_red><bold>Бан навсегда (читы)"), c -> {
            p.closeInventory();
            boolean admin = p.isOp() || p.hasPermission("anarchy.admin");
            ban(p, d, admin ? 0 : 30L * 86_400_000L, "Читы", admin);
        });
        m.set(31, Menus.icon(Material.BEACON, "<aqua><bold>Досье ИИ-античита", "<gray>Вероятность читов, улики", "<gray>и все сообщения античита"), c -> { p.closeInventory(); pl.anticheat().dossier(p, t.getName()); });
        m.set(27, Menus.icon(Material.ARROW, "<gray>« Назад"), c -> panel(p));
        m.fill();
        p.openInventory(m.getInventory());
    }

    private void eventsMenu(Player p) {
        Menus.Menu m = new Menus.Menu(3, "<dark_gray>Запуск ивента");
        int slot = 10;
        Material[] icons = {Material.CHEST, Material.WITHER_SKELETON_SKULL, Material.MAGMA_BLOCK, Material.LIGHTNING_ROD, Material.CRYING_OBSIDIAN, Material.DIAMOND_SWORD, Material.ENDER_EYE};
        int i = 0;
        for (String type : MoreEvents.NAMES.keySet()) {
            m.set(slot++, Menus.icon(icons[i++ % icons.length], MoreEvents.name(type), "", "<yellow>▶ Запустить"), c -> { p.closeInventory(); pl.msg(p, pl.events().start(type) ? "<green>Ивент запущен" : "<red>Не удалось"); });
        }
        m.set(18, Menus.icon(Material.ARROW, "<gray>« Назад"), c -> panel(p));
        m.set(26, Menus.icon(Material.CLOCK, "<yellow><bold>Следующий через 10 сек", "<gray>По расписанию: </gray>" + (pl.events().nextType() == null ? "-" : MoreEvents.name(pl.events().nextType()))), c -> { pl.events().skipTimer(); p.closeInventory(); pl.msg(p, "<green>Готово"); });
        m.fill();
        p.openInventory(m.getInventory());
    }
}
