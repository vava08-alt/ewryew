package ru.anarchy.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static ru.anarchy.core.AnarchyCore.esc;
import static ru.anarchy.core.AnarchyCore.mm;

/** Промокоды (/code, коды ютуберов), жалобы /report, вызов модератора /spec + режим наблюдателя, детектор X-Ray. */
public final class Moderation implements Listener {
    private final AnarchyCore pl;
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Europe/Moscow"));

    public Moderation(AnarchyCore pl) {
        this.pl = pl;
        loadCodes();
        loadReports();
        Bukkit.getScheduler().runTaskTimer(pl, this::specTick, 20, 20);
    }

    private String pre() { return pl.getConfig().getString("prefix", ""); }

    // =====================================================================
    //                              ПРОМОКОДЫ
    // =====================================================================
    /** creator - ник ютубера (код автора), rewards - награды игроку, ownerReward - награда автору за каждую активацию */
    private static final class Code {
        String id, creator, rewards, ownerReward = ""; int maxUses; long until; int uses; long created; final List<String> log = new ArrayList<>();
        boolean isCreator() { return creator != null && !creator.isBlank(); }
    }
    private final Map<String, Code> codes = new LinkedHashMap<>();
    private final Map<String, Set<String>> codeIps = new HashMap<>();
    private File codesFile() { return new File(pl.getDataFolder(), "codes.yml"); }

    private void loadCodes() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(codesFile());
        ConfigurationSection s = y.getConfigurationSection("codes");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            Code x = new Code();
            x.id = k; x.creator = c.getString("creator"); x.rewards = c.getString("rewards", ""); x.ownerReward = c.getString("owner-reward", "");
            x.maxUses = c.getInt("max-uses"); x.until = c.getLong("until"); x.uses = c.getInt("uses"); x.created = c.getLong("created");
            x.log.addAll(c.getStringList("log"));
            codes.put(k, x);
            codeIps.put(k, new HashSet<>(c.getStringList("ips")));
        }
    }

    public void saveCodes() {
        YamlConfiguration y = new YamlConfiguration();
        for (Code x : codes.values()) {
            String k = "codes." + x.id;
            y.set(k + ".creator", x.creator); y.set(k + ".rewards", x.rewards); y.set(k + ".owner-reward", x.ownerReward);
            y.set(k + ".max-uses", x.maxUses); y.set(k + ".until", x.until); y.set(k + ".uses", x.uses); y.set(k + ".created", x.created);
            y.set(k + ".log", x.log); y.set(k + ".ips", new ArrayList<>(codeIps.getOrDefault(x.id, Set.of())));
        }
        try { y.save(codesFile()); } catch (Exception e) { pl.getLogger().warning("codes.yml: " + e); }
    }

    private String rewardsText(String spec) {
        List<String> out = new ArrayList<>();
        for (String r : spec.split(",")) {
            r = r.trim(); if (r.isEmpty()) continue;
            String[] a = r.split(":");
            out.add(switch (a[0]) {
                case "coins" -> a[1] + " " + pl.cur();
                case "ari" -> a[1] + " " + pl.ariName();
                case "key" -> "ключ «" + a[1] + "» x" + (a.length > 2 ? a[2] : "1");
                case "rank" -> "ранг " + a[1];
                case "title" -> "титул " + a[1];
                case "enchant" -> "книга " + a[1];
                case "custom" -> "предмет " + a[1];
                default -> r;
            });
        }
        return String.join(", ", out);
    }

    private void giveRewards(Player p, String spec) {
        for (String r : spec.split(",")) {
            r = r.trim(); if (r.isEmpty()) continue;
            pl.cases().grant(p, new Cases.Reward(r, 1, Cases.Rarity.COMMON));
        }
    }

    /** /code <код> - игрок вводит промокод. /code - мои коды (для ютуберов) */
    public void codeCmd(Player p, String[] a) {
        PlayerData d = pl.data(p);
        if (a.length == 0) {
            pl.msg(p, "<yellow>/code <промокод> <gray>- активировать промокод или код автора (ютубера)");
            List<Code> mine = codes.values().stream().filter(c -> c.isCreator() && c.creator.equalsIgnoreCase(p.getName())).toList();
            for (Code c : mine) pl.msg(p, "<gray>Твой код <gold>" + c.id + "</gold>: активаций <white>" + c.uses + (c.maxUses > 0 ? "/" + c.maxUses : "") + " <gray>| тебе за каждую: <white>" + rewardsText(c.ownerReward));
            if (d.creatorCode != null) pl.msg(p, "<gray>Ты поддержал автора: <aqua>" + d.creatorCode);
            return;
        }
        String id = a[0].toUpperCase(Locale.ROOT);
        Code c = codes.get(id);
        if (c == null) { pl.msg(p, "<red>Такого кода нет"); return; }
        if (c.until > 0 && c.until < System.currentTimeMillis()) { pl.msg(p, "<red>Срок действия кода истёк"); return; }
        if (c.maxUses > 0 && c.uses >= c.maxUses) { pl.msg(p, "<red>Код уже активировали максимальное число раз"); return; }
        if (d.usedCodes.contains(id)) { pl.msg(p, "<red>Ты уже активировал этот код"); return; }
        if (c.isCreator()) {
            if (d.creatorCode != null) { pl.msg(p, "<red>Ты уже ввёл код автора <white>" + d.creatorCode + "</white>. Код автора можно ввести только один раз."); return; }
            if (c.creator.equalsIgnoreCase(p.getName())) { pl.msg(p, "<red>Нельзя активировать свой же код"); return; }
            int maxH = pl.getConfig().getInt("codes.creator-max-hours", 10);
            if (maxH > 0 && d.playMinutes > maxH * 60L) { pl.msg(p, "<red>Код автора могут ввести только новички (до " + maxH + " ч игры)"); return; }
        }
        String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
        boolean exempt = pl.sharedIp(ip);
        Set<String> ips = codeIps.computeIfAbsent(id, k -> new HashSet<>());
        if (!exempt && ips.contains(ip)) { pl.msg(p, "<red>С твоего IP этот код уже активировали"); return; }
        if (!exempt) ips.add(ip);
        c.uses++;
        d.usedCodes.add(id);
        if (c.isCreator()) d.creatorCode = id;
        c.log.add(DF.format(Instant.now()) + " " + p.getName());
        while (c.log.size() > 200) c.log.remove(0);
        giveRewards(p, c.rewards);
        pl.msg(p, "<green>✔ Код <gold>" + id + "</gold> активирован! <gray>Ты получил: <white>" + rewardsText(c.rewards));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
        if (c.isCreator() && !c.ownerReward.isBlank()) {
            Player owner = Bukkit.getPlayerExact(c.creator);
            if (owner != null) { giveRewards(owner, c.ownerReward); pl.msg(owner, "<gold>" + esc(p.getName()) + " ввёл твой код " + id + "! <gray>Тебе: <white>" + rewardsText(c.ownerReward)); }
            else { PlayerData od = pl.store().byName(c.creator); if (od != null) addOffline(od, c.ownerReward); }
        }
        saveCodes();
    }

    /** Награда автору, который не в сети: монеты/Ari/ключи начисляем сразу, остальное - при следующей активации онлайн */
    private void addOffline(PlayerData d, String spec) {
        for (String r : spec.split(",")) {
            String[] a = r.trim().split(":");
            try {
                switch (a[0]) {
                    case "coins" -> d.balance += Long.parseLong(a[1]);
                    case "ari" -> d.ari += Long.parseLong(a[1]);
                    case "key" -> pl.cases().addKeys(d, a[1], a.length > 2 ? Integer.parseInt(a[2]) : 1);
                    default -> {}
                }
            } catch (Exception ignored) {}
        }
    }

    /** /codes create|creator|del|list|info - управление кодами (админ, консоль, RCON) */
    public void codesCmd(CommandSender s, String[] a) {
        if (!(s.hasPermission("anarchy.admin") || !(s instanceof Player) || ((Player) s).isOp())) { s.sendMessage(mm("<red>Нет прав")); return; }
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (a.length < 3) { s.sendMessage(mm("<red>/codes create <КОД> <награды> [активаций|0] [дней|0]\n<gray>Награды через запятую: coins:5000,ari:10,key:ari:2,rank:hunter,enchant:telekinesis:1")); return; }
                Code c = new Code(); c.id = a[1].toUpperCase(Locale.ROOT); c.rewards = a[2];
                c.maxUses = a.length > 3 ? Integer.parseInt(a[3]) : 0;
                c.until = a.length > 4 && Integer.parseInt(a[4]) > 0 ? System.currentTimeMillis() + Integer.parseInt(a[4]) * 86_400_000L : 0;
                c.created = System.currentTimeMillis();
                codes.put(c.id, c); saveCodes();
                s.sendMessage(mm("<green>Промокод <gold>" + c.id + "</gold> создан: <white>" + rewardsText(c.rewards) + (c.maxUses > 0 ? " <gray>| активаций: " + c.maxUses : "") + (c.until > 0 ? " <gray>| до " + DF.format(Instant.ofEpochMilli(c.until)) : "")));
            }
            case "creator", "youtuber", "yt" -> {
                if (a.length < 4) { s.sendMessage(mm("<red>/codes creator <КОД> <ник_автора> <награды_игроку> [награда_автору]\n<gray>Пример: /codes creator KIRILL Kirill coins:3000,key:ari:1 ari:2")); return; }
                Code c = new Code(); c.id = a[1].toUpperCase(Locale.ROOT); c.creator = a[2]; c.rewards = a[3];
                c.ownerReward = a.length > 4 ? a[4] : pl.getConfig().getString("codes.default-creator-reward", "ari:1");
                c.created = System.currentTimeMillis();
                codes.put(c.id, c); saveCodes();
                s.sendMessage(mm("<green>Код автора <gold>" + c.id + "</gold> для <white>" + esc(c.creator) + "</white> создан. Игроку: <white>" + rewardsText(c.rewards) + " <gray>| автору за каждого: <white>" + rewardsText(c.ownerReward)));
            }
            case "del", "delete", "remove" -> {
                if (a.length < 2) { s.sendMessage(mm("<red>/codes del <КОД>")); return; }
                s.sendMessage(mm(codes.remove(a[1].toUpperCase(Locale.ROOT)) != null ? "<yellow>Код удалён" : "<red>Нет такого кода"));
                saveCodes();
            }
            case "info" -> {
                if (a.length < 2) { s.sendMessage(mm("<red>/codes info <КОД>")); return; }
                Code c = codes.get(a[1].toUpperCase(Locale.ROOT));
                if (c == null) { s.sendMessage(mm("<red>Нет такого кода")); return; }
                s.sendMessage(mm("<gold><bold>" + c.id + "</bold> <gray>" + (c.isCreator() ? "код автора " + esc(c.creator) : "промокод")));
                s.sendMessage(mm("<gray>Награда: <white>" + rewardsText(c.rewards) + (c.isCreator() ? " <gray>| автору: <white>" + rewardsText(c.ownerReward) : "")));
                s.sendMessage(mm("<gray>Активаций: <white>" + c.uses + (c.maxUses > 0 ? "/" + c.maxUses : "") + (c.until > 0 ? " <gray>| до " + DF.format(Instant.ofEpochMilli(c.until)) : "")));
                int from = Math.max(0, c.log.size() - 15);
                if (!c.log.isEmpty()) s.sendMessage(mm("<gray>Последние: <white>" + esc(String.join(", ", c.log.subList(from, c.log.size())))));
            }
            default -> {
                s.sendMessage(mm("<gold><bold>Промокоды</bold> <gray>(" + codes.size() + ")"));
                for (Code c : codes.values()) s.sendMessage(mm(" <gold>" + c.id + " <dark_gray>| " + (c.isCreator() ? "<aqua>автор " + esc(c.creator) : "<gray>промо") + " <dark_gray>| <white>" + c.uses + (c.maxUses > 0 ? "/" + c.maxUses : "") + " акт." + (c.until > 0 && c.until < System.currentTimeMillis() ? " <red>истёк" : "")));
                s.sendMessage(mm("<gray>/codes create <КОД> <награды> [активаций] [дней]\n<gray>/codes creator <КОД> <ник_ютубера> <награды_игроку> [награда_автору]\n<gray>/codes info|del <КОД>"));
            }
        }
    }

    public List<String> codeIds() { return new ArrayList<>(codes.keySet()); }

    // =====================================================================
    //                    ЖАЛОБЫ /report  и  ВЫЗОВ /spec
    // =====================================================================
    private static final class Report {
        int id; String type, from, target, reason, where, handledBy; long time; boolean open = true;
    }
    private final List<Report> reports = new ArrayList<>();
    private int nextId = 1;
    private final Map<UUID, Long> reportCd = new HashMap<>();
    private final Map<String, Long> targetCd = new HashMap<>();
    private File reportsFile() { return new File(pl.getDataFolder(), "reports.yml"); }

    private void loadReports() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(reportsFile());
        nextId = y.getInt("next-id", 1);
        ConfigurationSection s = y.getConfigurationSection("reports");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            Report r = new Report();
            r.id = Integer.parseInt(k); r.type = c.getString("type", "report"); r.from = c.getString("from"); r.target = c.getString("target");
            r.reason = c.getString("reason", ""); r.where = c.getString("where", ""); r.handledBy = c.getString("handled-by"); r.time = c.getLong("time"); r.open = c.getBoolean("open", true);
            reports.add(r);
        }
    }

    public void saveReports() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        // храним последние 300
        List<Report> keep = reports.size() > 300 ? reports.subList(reports.size() - 300, reports.size()) : reports;
        for (Report r : keep) {
            String k = "reports." + r.id;
            y.set(k + ".type", r.type); y.set(k + ".from", r.from); y.set(k + ".target", r.target); y.set(k + ".reason", r.reason);
            y.set(k + ".where", r.where); y.set(k + ".handled-by", r.handledBy); y.set(k + ".time", r.time); y.set(k + ".open", r.open);
        }
        try { y.save(reportsFile()); } catch (Exception e) { pl.getLogger().warning("reports.yml: " + e); }
    }

    public int openReports() { return (int) reports.stream().filter(r -> r.open).count(); }

    private String typeName(String t) {
        return switch (t) { case "spec" -> "<aqua>◉ Проследить"; case "xray" -> "<gold>⛏ X-Ray?"; case "ai" -> "<aqua>⛨ ИИ-античит"; default -> "<red>⚠ Жалоба"; };
    }

    /** Письмо от ИИ-античита в почту модераторов (без общего оповещения - ИИ шлёт своё письмо с кнопкой «Принять»). */
    public int aiReport(Player target, String reason) {
        Report r = new Report();
        r.id = nextId++; r.type = "ai"; r.from = "ИИ-античит"; r.target = target.getName(); r.reason = reason; r.time = System.currentTimeMillis();
        Location l = target.getLocation();
        r.where = l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
        reports.add(r);
        saveReports();
        return r.id;
    }

    private Report report(int id) {
        for (Report r : reports) if (r.id == id) return r;
        return null;
    }

    public void aiReportTaken(int id, String by) {
        Report r = report(id);
        if (r != null) { r.handledBy = by; saveReports(); }
    }

    public void aiReportNote(int id, String note) {
        Report r = report(id);
        if (r != null && !r.reason.endsWith("(" + note + ")")) { r.reason += " (" + note + ")"; saveReports(); }
    }

    public void aiReportClose(int id, String by, String result) {
        Report r = report(id);
        if (r == null) return;
        r.open = false;
        r.handledBy = by;
        if (result != null && !result.isBlank()) r.reason += " -> " + result;
        saveReports();
    }

    private boolean aiWaiting(Report r) { return r.type.equals("ai") && r.open && pl.anticheat() != null && pl.anticheat().waitingCase(r.target) > 0; }

    private Report addReport(String type, String from, Player target, String reason) {
        Report r = new Report();
        r.id = nextId++; r.type = type; r.from = from; r.target = target.getName(); r.reason = reason; r.time = System.currentTimeMillis();
        Location l = target.getLocation();
        r.where = l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
        reports.add(r);
        saveReports();
        notifyStaff(r);
        return r;
    }

    private Component buttons(Report r) {
        Component start = aiWaiting(r) ? mm(" ").append(mm("<green><bold>[✔ Принять проверку]").clickEvent(ClickEvent.runCommand("/ac accept " + r.target))) : Component.empty();
        if (r.type.equals("ai")) start = start.append(mm(" ")).append(mm("<gold>[≡ Досье ИИ]").clickEvent(ClickEvent.runCommand("/ac info " + r.target)));
        return start.append(mm(" ")).append(mm("<aqua>[◉ Следить]").clickEvent(ClickEvent.runCommand("/spec " + r.target)).hoverEvent(HoverEvent.showText(mm("Режим наблюдателя за " + r.target))))
                .append(mm(" ")).append(mm("<green>[ТП]").clickEvent(ClickEvent.runCommand("/tp " + r.target)))
                .append(mm(" ")).append(mm("<gold>[Аккаунты]").clickEvent(ClickEvent.runCommand("/alts " + r.target)))
                .append(mm(" ")).append(mm("<gray>[Закрыть]").clickEvent(ClickEvent.runCommand("/reports close " + r.id)));
    }

    private void notifyStaff(Report r) {
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (!pl.admin().staff(s)) continue;
            s.sendMessage(mm("<dark_gray>[<red>Модерация</red>] " + typeName(r.type) + " <dark_gray>#" + r.id + " <white>" + esc(r.target) + " <gray>от " + esc(r.from) + ": <white>" + esc(r.reason)));
            s.sendMessage(buttons(r));
            s.playSound(s.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
            s.sendActionBar(mm(typeName(r.type) + " <white>" + esc(r.target)));
        }
    }

    /** /report <ник> <причина> */
    public void reportCmd(Player p, String[] a) {
        if (a.length < 2) { pl.msg(p, "<red>/report <ник> <причина> <gray>- пожаловаться на игрока (читы, гриф, оскорбления)"); return; }
        submit(p, a, "report");
    }

    private void submit(Player p, String[] a, String type) {
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { pl.msg(p, "<red>Игрок не в сети"); return; }
        if (t.equals(p)) { pl.msg(p, "<red>Нельзя на себя"); return; }
        long now = System.currentTimeMillis();
        if (reportCd.getOrDefault(p.getUniqueId(), 0L) > now) { pl.msg(p, "<red>Подожди " + (reportCd.get(p.getUniqueId()) - now) / 1000 + " сек"); return; }
        String key = type + ":" + t.getName().toLowerCase(Locale.ROOT) + ":" + p.getUniqueId();
        if (targetCd.getOrDefault(key, 0L) > now) { pl.msg(p, "<yellow>Ты уже отправил это. Модераторы разберутся."); return; }
        reportCd.put(p.getUniqueId(), now + pl.getConfig().getInt("reports.cooldown-seconds", 20) * 1000L);
        targetCd.put(key, now + 5 * 60_000L);
        String reason = a.length > 1 ? Util.join(a, 1) : (type.equals("spec") ? "подозрение на читы" : "");
        Report r = addReport(type, p.getName(), t, reason);
        long staffOnline = Bukkit.getOnlinePlayers().stream().filter(x -> pl.admin().staff(x)).count();
        pl.msg(p, "<green>✔ " + (type.equals("spec") ? "Модераторы получили просьбу проследить за " : "Жалоба на ") + "<white>" + esc(t.getName()) + "</white> отправлена <dark_gray>(#" + r.id + ")"
                + (staffOnline == 0 ? "<gray>. Сейчас модераторов нет в сети - они увидят её при входе." : "<gray>. Модераторов в сети: " + staffOnline));
    }

    /** /spec: игрок - позвать модератора проследить; модератор - режим наблюдателя */
    public void specCmd(Player p, String[] a) {
        if (pl.admin().staff(p)) {
            if (a.length == 0 || a[0].equalsIgnoreCase("off") || a[0].equalsIgnoreCase("stop")) { if (!stopSpec(p)) pl.msg(p, "<yellow>/spec <ник> <gray>- следить за игроком, <yellow>/spec off <gray>- вернуться. <yellow>/reports <gray>- жалобы"); return; }
            Player t = Bukkit.getPlayerExact(a[0]);
            if (t == null) { pl.msg(p, "<red>Игрок не в сети"); return; }
            spectate(p, t);
            return;
        }
        if (a.length < 1) { pl.msg(p, "<red>/spec <ник> [что заметил] <gray>- позвать модератора проследить за подозрительным игроком (бьёт читами, летает и т.д.)"); return; }
        submit(p, a, "spec");
    }

    private record SpecState(Location back, GameMode mode, UUID target) {}
    private final Map<UUID, SpecState> spec = new HashMap<>();

    public void spectate(Player p, Player t) {
        SpecState old = spec.get(p.getUniqueId());
        spec.put(p.getUniqueId(), new SpecState(old != null ? old.back() : p.getLocation(), old != null ? old.mode() : p.getGameMode(), t.getUniqueId()));
        for (Player o : Bukkit.getOnlinePlayers()) if (!pl.admin().staff(o)) o.hidePlayer(pl, p);
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(t.getLocation().add(0, 2, 0));
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline() && p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(t); }, 5);
        // закрываем открытые жалобы на этого игрока как "взято"
        for (Report r : reports) if (r.open && r.target.equalsIgnoreCase(t.getName()) && r.handledBy == null) r.handledBy = p.getName();
        pl.msg(p, "<aqua>◉ Слежка за <white>" + esc(t.getName()) + "<aqua>. Ты невидим. <gray>Shift - отцепиться от камеры, <yellow>/spec off</yellow> - вернуться.");
        pl.msg(p, "<gray>Дальше: <yellow>/check " + t.getName() + "</yellow> - вызвать на проверку, <yellow>/ban " + t.getName() + " 30d Читы");
    }

    public boolean stopSpec(Player p) {
        SpecState st = spec.remove(p.getUniqueId());
        if (st == null) return false;
        p.setSpectatorTarget(null);
        p.teleport(st.back());
        p.setGameMode(st.mode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : st.mode());
        if (!pl.admin().vanished(p)) for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(pl, p);
        pl.msg(p, "<green>Слежка завершена, ты вернулся на место");
        return true;
    }

    public boolean spectating(Player p) { return spec.containsKey(p.getUniqueId()); }

    private void specTick() {
        for (var e : new ArrayList<>(spec.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            Player t = Bukkit.getPlayer(e.getValue().target());
            if (t == null) { p.sendActionBar(mm("<gray>Игрок вышел. <yellow>/spec off")); continue; }
            double hp = t.getHealth();
            p.sendActionBar(mm("<aqua>◉ " + esc(t.getName()) + " <dark_gray>| <red>❤ " + String.format(Locale.ROOT, "%.1f", hp) + " <dark_gray>| <gray>пинг " + t.getPing()
                    + " <dark_gray>| <gray>" + t.getLocation().getBlockX() + " " + t.getLocation().getBlockY() + " " + t.getLocation().getBlockZ() + " <dark_gray>| <yellow>/spec off"));
            if (!p.getWorld().equals(t.getWorld()) || p.getLocation().distanceSquared(t.getLocation()) > 80 * 80) { p.teleport(t.getLocation().add(0, 2, 0)); Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline()) p.setSpectatorTarget(t); }, 3); }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (UUID u : spec.keySet()) { Player s = Bukkit.getPlayer(u); if (s != null && !pl.admin().staff(p)) p.hidePlayer(pl, s); }
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline() || !pl.admin().staff(p)) return;
            // "письмо" модератору: что пришло, пока его не было
            List<Report> open = reports.stream().filter(r -> r.open).toList();
            if (open.isEmpty()) return;
            p.sendMessage(mm("<dark_gray>▬▬▬ <red><bold>✉ Почта модератора</bold> <dark_gray>▬▬▬"));
            p.sendMessage(mm("<gray>Открытых жалоб и просьб проследить: <white>" + open.size()));
            for (Report r : open.subList(Math.max(0, open.size() - 5), open.size())) {
                Component line = mm(" " + typeName(r.type) + " <dark_gray>#" + r.id + " <white>" + esc(r.target) + (Bukkit.getPlayerExact(r.target) != null ? " <green>(в сети)" : " <dark_gray>(не в сети)") + " <gray>- " + esc(r.reason));
                if (aiWaiting(r)) line = line.append(mm(" <green><bold>[✔ Принять]").clickEvent(ClickEvent.runCommand("/ac accept " + r.target)));
                else if (r.type.equals("ai")) line = line.append(mm(" <gold>[Досье]").clickEvent(ClickEvent.runCommand("/ac info " + r.target)));
                p.sendMessage(line);
            }
            p.sendMessage(mm(" ").append(mm("<yellow>[Открыть все жалобы]").clickEvent(ClickEvent.runCommand("/reports"))));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1f);
        }, 60);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (spec.containsKey(e.getPlayer().getUniqueId())) stopSpec(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpecTp(PlayerTeleportEvent e) {
        // наблюдатель не может телепортироваться через меню наблюдателя к случайным игрокам без слежки - разрешаем, просто запоминаем
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE && !spectating(e.getPlayer()) && !pl.admin().staff(e.getPlayer())) e.setCancelled(true);
    }

    /** /reports [close <id>|closeall|all] */
    public void reportsCmd(CommandSender s, String[] a) {
        if (!pl.admin().staff(s)) { s.sendMessage(mm("<red>Нет прав. Пожаловаться: /report <ник> <причина>")); return; }
        if (a.length >= 2 && a[0].equalsIgnoreCase("close")) {
            try {
                int id = Integer.parseInt(a[1]);
                for (Report r : reports) if (r.id == id) { r.open = false; r.handledBy = s.getName(); saveReports(); s.sendMessage(mm("<green>Жалоба #" + id + " закрыта")); return; }
            } catch (NumberFormatException ignored) {}
            s.sendMessage(mm("<red>Нет такой жалобы")); return;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("closeall")) { reports.forEach(r -> { if (r.open) { r.open = false; r.handledBy = s.getName(); } }); saveReports(); s.sendMessage(mm("<green>Все жалобы закрыты")); return; }
        if (s instanceof Player p && a.length == 0) { reportsMenu(p); return; }
        boolean all = a.length > 0 && a[0].equalsIgnoreCase("all");
        List<Report> list = reports.stream().filter(r -> all || r.open).toList();
        s.sendMessage(mm("<red><bold>Жалобы</bold> <gray>(" + list.size() + ")"));
        for (Report r : list.subList(Math.max(0, list.size() - 15), list.size())) {
            s.sendMessage(mm(" " + typeName(r.type) + " <dark_gray>#" + r.id + " " + DF.format(Instant.ofEpochMilli(r.time)) + " <white>" + esc(r.target) + " <gray>от " + esc(r.from) + ": " + esc(r.reason) + (r.open ? "" : " <dark_gray>[закрыта " + esc(String.valueOf(r.handledBy)) + "]")));
            if (r.open) s.sendMessage(buttons(r));
        }
    }

    public void reportsMenu(Player p) {
        List<Report> open = reports.stream().filter(r -> r.open).toList();
        Menus.Menu m = new Menus.Menu(6, "<red><bold>Жалобы</bold> <dark_gray>| открыто " + open.size());
        int slot = 0;
        for (int i = open.size() - 1; i >= 0 && slot < 45; i--) {
            Report r = open.get(i);
            Player t = Bukkit.getPlayerExact(r.target);
            boolean ai = r.type.equals("ai"), waiting = aiWaiting(r);
            ItemStack h = Menus.icon(r.type.equals("xray") ? Material.DIAMOND_ORE : r.type.equals("spec") ? Material.ENDER_EYE : ai && !waiting ? Material.BEACON : Material.PLAYER_HEAD,
                    typeName(r.type) + " <white>" + esc(r.target) + " <dark_gray>#" + r.id,
                    "<gray>От: <white>" + esc(r.from), "<gray>Причина: <white>" + esc(r.reason), "<gray>Когда: <white>" + DF.format(Instant.ofEpochMilli(r.time)),
                    "<gray>Где был: <white>" + esc(r.where), t != null ? "<green>● В сети" : "<dark_gray>● Не в сети", r.handledBy != null ? "<yellow>Взял: " + esc(r.handledBy) : "",
                    "", waiting ? "<green>ЛКМ - принять проверку" : ai ? "<yellow>ЛКМ <gray>- досье ИИ" : "<yellow>ЛКМ <gray>- следить (/spec)", "<yellow>ПКМ <gray>- телепорт", "<yellow>Shift+клик <gray>- закрыть");
            if (h.getType() == Material.PLAYER_HEAD && t != null) { SkullMeta sm = (SkullMeta) h.getItemMeta(); sm.setOwningPlayer(t); h.setItemMeta(sm); }
            m.set(slot++, h, c -> {
                if (c.isShiftClick()) { r.open = false; r.handledBy = p.getName(); saveReports(); reportsMenu(p); return; }
                if (ai && !c.isRightClick()) {
                    p.closeInventory();
                    if (aiWaiting(r)) pl.anticheat().accept(p, r.target); else pl.anticheat().dossier(p, r.target);
                    return;
                }
                Player tt = Bukkit.getPlayerExact(r.target);
                if (tt == null) { pl.msg(p, "<red>Игрок не в сети"); return; }
                p.closeInventory();
                if (c.isRightClick()) p.teleport(tt.getLocation()); else spectate(p, tt);
            });
        }
        if (open.isEmpty()) m.set(22, Menus.icon(Material.LIME_DYE, "<green><bold>Жалоб нет"), null);
        m.set(49, Menus.icon(Material.ARROW, "<gray>« Админ-панель"), c -> pl.admin().panel(p));
        m.set(53, Menus.icon(Material.MILK_BUCKET, "<yellow><bold>Закрыть все"), c -> { reports.forEach(r -> r.open = false); saveReports(); reportsMenu(p); });
        m.fill();
        p.openInventory(m.getInventory());
    }

    // =====================================================================
    //                              ДЕТЕКТОР X-RAY
    // =====================================================================
    private static final class Mining {
        final Deque<long[]> veins = new ArrayDeque<>();      // [время, тип 0=алмаз 1=обломки]
        int stone, diamonds, debris, diamondVeins, debrisVeins; long lastAlert;
        Location lastOre; long lastOreTime; String lastOreType = "";
        final Deque<Long> stoneTimes = new ArrayDeque<>();
    }
    private final Map<UUID, Mining> mining = new HashMap<>();
    private final Set<Long> placedOres = new HashSet<>();

    private static long key(Block b) { return ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | ((long) b.getY() & 0xFFF) ^ (long) b.getWorld().getName().hashCode() << 60; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOrePlace(BlockPlaceEvent e) {
        Material m = e.getBlock().getType();
        if (isDiamond(m) || m == Material.ANCIENT_DEBRIS) { placedOres.add(key(e.getBlock())); if (placedOres.size() > 50_000) placedOres.clear(); }
    }

    private static boolean isDiamond(Material m) { return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE; }
    private static boolean isStone(Material m) {
        return switch (m) { case STONE, DEEPSLATE, TUFF, ANDESITE, DIORITE, GRANITE, NETHERRACK, BASALT, BLACKSTONE, COBBLED_DEEPSLATE -> true; default -> false; };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        Block b = e.getBlock();
        if (pl.worlds().isHub(b.getWorld())) return;   // автошахта на спавне - руда там не улика
        Material m = b.getType();
        Mining x = mining.computeIfAbsent(p.getUniqueId(), k -> new Mining());
        long now = System.currentTimeMillis();
        if (isStone(m)) {
            x.stone++;
            x.stoneTimes.addLast(now);
            while (!x.stoneTimes.isEmpty() && now - x.stoneTimes.peekFirst() > window()) x.stoneTimes.pollFirst();
            return;
        }
        boolean dia = isDiamond(m), deb = m == Material.ANCIENT_DEBRIS;
        if (!dia && !deb) return;
        if (placedOres.remove(key(b))) return;
        if (dia) x.diamonds++; else x.debris++;
        String type = dia ? "d" : "n";
        // новая жила - если далеко от прошлой руды того же типа или прошло много времени
        boolean newVein = x.lastOre == null || !x.lastOre.getWorld().equals(b.getWorld()) || !type.equals(x.lastOreType)
                || x.lastOre.distanceSquared(b.getLocation()) > 25 || now - x.lastOreTime > 60_000;
        x.lastOre = b.getLocation(); x.lastOreTime = now; x.lastOreType = type;
        if (!newVein) return;
        if (dia) x.diamondVeins++; else x.debrisVeins++;
        x.veins.addLast(new long[]{now, dia ? 0 : 1});
        while (!x.veins.isEmpty() && now - x.veins.peekFirst()[0] > window()) x.veins.pollFirst();
        long dv = x.veins.stream().filter(v -> v[1] == 0).count(), nv = x.veins.stream().filter(v -> v[1] == 1).count();
        int stoneW = x.stoneTimes.size();
        int maxD = pl.getConfig().getInt("xray.diamond-veins", 6), maxN = pl.getConfig().getInt("xray.debris-veins", 5);
        double ratio = stoneW == 0 ? 999 : (dv + nv) * 100.0 / stoneW;
        boolean suspicious = dv >= maxD || nv >= maxN || ((dv + nv) >= 4 && ratio > pl.getConfig().getDouble("xray.max-veins-per-100-stone", 3.0));
        if (suspicious && now - x.lastAlert > 10 * 60_000L) {
            x.lastAlert = now;
            String reason = "за " + window() / 60_000 + " мин: жил алмазов " + dv + ", обломков " + nv + ", камня " + stoneW + " (" + String.format(Locale.ROOT, "%.1f", ratio) + " жил на 100 камня)";
            addReport("xray", "Античит", p, reason);
            pl.getLogger().warning("[X-Ray?] " + p.getName() + " " + reason);
            if (pl.anticheat() != null) pl.anticheat().xray(p, reason);
        }
    }

    private long window() { return pl.getConfig().getLong("xray.window-minutes", 20) * 60_000L; }

    /** /xray <ник> */
    public void xrayCmd(CommandSender s, String[] a) {
        if (!pl.admin().staff(s)) { s.sendMessage(mm("<red>Нет прав")); return; }
        if (a.length < 1) {
            s.sendMessage(mm("<gold><bold>X-Ray: подозрительные за сессию</bold>"));
            mining.entrySet().stream().sorted((x, y) -> Integer.compare(y.getValue().diamondVeins + y.getValue().debrisVeins * 2, x.getValue().diamondVeins + x.getValue().debrisVeins * 2)).limit(10).forEach(en -> {
                PlayerData d = pl.store().get(en.getKey());
                Mining m = en.getValue();
                s.sendMessage(mm(" <white>" + esc(d == null ? "?" : d.name) + " <gray>- жил алмазов <aqua>" + m.diamondVeins + "</aqua>, обломков <gold>" + m.debrisVeins + "</gold>, камня <white>" + m.stone));
            });
            return;
        }
        PlayerData d = pl.store().byName(a[0]);
        if (d == null) { s.sendMessage(mm("<red>Игрок не найден")); return; }
        xrayInfo(s, d);
    }

    public void xrayInfo(CommandSender s, PlayerData d) {
        Mining m = mining.get(d.uuid);
        if (m == null) { s.sendMessage(mm("<gray>" + esc(d.name) + " ещё ничего не копал с перезапуска сервера")); return; }
        double r = m.stone == 0 ? 0 : (m.diamondVeins + m.debrisVeins) * 100.0 / m.stone;
        s.sendMessage(mm("<gold><bold>X-Ray: " + esc(d.name) + "</bold> <gray>(с перезапуска)"));
        s.sendMessage(mm(" <gray>Камня: <white>" + m.stone + " <gray>| алмазной руды: <aqua>" + m.diamonds + " (" + m.diamondVeins + " жил)</aqua> <gray>| обломков: <gold>" + m.debris + " (" + m.debrisVeins + " жил)"));
        s.sendMessage(mm(" <gray>Жил на 100 камня: " + (r > 3 ? "<red>" : r > 1.5 ? "<yellow>" : "<green>") + String.format(Locale.ROOT, "%.2f", r) + " <dark_gray>(норма у честных игроков ~0.2-1)"));
    }
}
