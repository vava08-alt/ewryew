package ru.anarchy.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static ru.anarchy.core.AnarchyCore.mm;

/** Автошахта на спавне: куб руды, слои сверху вниз от обычных к легендарным, полное обновление каждые reset-minutes. */
public final class Mine {
    private record Layer(String name, int depth, Material[] blocks, int[] cum) {
        Material roll() {
            int r = ThreadLocalRandom.current().nextInt(cum[cum.length - 1]);
            for (int i = 0; i < cum.length; i++) if (r < cum[i]) return blocks[i];
            return blocks[blocks.length - 1];
        }
    }

    private final AnarchyCore pl;
    private final NamespacedKey TAG;
    private final File backup;
    private final List<Layer> layers = new ArrayList<>();
    private final Set<Integer> warned = new HashSet<>();
    private boolean enabled;
    private World world;
    private int x1, y1, z1, x2, y2, z2;
    private long resetMs, nextReset;
    private List<Integer> warnAt = List.of();
    private Location holoLoc;
    private TextDisplay holo;
    private int orePercent = 100;

    public Mine(AnarchyCore pl) {
        this.pl = pl;
        TAG = new NamespacedKey(pl, "mine_holo");
        backup = new File(pl.getDataFolder(), "mine-original.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40, 20);
    }

    public void load() {
        removeHolo();
        layers.clear();
        ConfigurationSection c = pl.getConfig().getConfigurationSection("mine");
        enabled = c != null && c.getBoolean("enabled", true);
        if (c == null) return;
        world = Bukkit.getWorld(c.getString("world", "spawn"));
        int[] a = xyz(c.getString("pos1")), b = xyz(c.getString("pos2"));
        if (world == null || a == null || b == null) { enabled = false; pl.getLogger().warning("Автошахта: проверь mine.world / pos1 / pos2"); return; }
        x1 = Math.min(a[0], b[0]); y1 = Math.min(a[1], b[1]); z1 = Math.min(a[2], b[2]);
        x2 = Math.max(a[0], b[0]); y2 = Math.max(a[1], b[1]); z2 = Math.max(a[2], b[2]);
        resetMs = Math.max(1, c.getLong("reset-minutes", 7)) * 60_000L;
        warnAt = c.getIntegerList("warn-seconds");
        for (Map<?, ?> m : c.getMapList("layers")) {
            List<Material> mats = new ArrayList<>();
            List<Integer> cum = new ArrayList<>();
            int sum = 0;
            Object bl = m.get("blocks");
            if (bl instanceof List<?> list) for (Object o : list) {
                String[] p = String.valueOf(o).trim().split("\\s+");
                Material mt = Material.matchMaterial(p[0]);
                int w = p.length > 1 ? Integer.parseInt(p[1]) : 1;
                if (mt == null || !mt.isBlock() || w <= 0) { pl.getLogger().warning("Автошахта: неизвестный блок " + o); continue; }
                sum += w; mats.add(mt); cum.add(sum);
            }
            if (mats.isEmpty()) continue;
            Object d = m.get("depth");
            layers.add(new Layer(String.valueOf(m.get("name") == null ? "?" : m.get("name")), d instanceof Number n ? Math.max(1, n.intValue()) : 1,
                    mats.toArray(new Material[0]), cum.stream().mapToInt(Integer::intValue).toArray()));
        }
        if (layers.isEmpty()) { enabled = false; pl.getLogger().warning("Автошахта: нет слоёв (mine.layers)"); return; }
        double[] h = dxyz(c.getString("hologram", ""));
        holoLoc = h == null ? null : new Location(world, h[0], h[1], h[2]);
        for (int cx = x1 >> 4; cx <= x2 >> 4; cx++) for (int cz = z1 >> 4; cz <= z2 >> 4; cz++) world.addPluginChunkTicket(cx, cz, pl);
        nextReset = 0;
    }

    private static int[] xyz(String s) {
        if (s == null) return null;
        String[] p = s.trim().split("[\\s,;]+");
        if (p.length < 3) return null;
        try { return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])}; } catch (NumberFormatException e) { return null; }
    }

    private static double[] dxyz(String s) {
        String[] p = s.trim().split("[\\s,;]+");
        if (p.length < 3) return null;
        try { return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])}; } catch (NumberFormatException e) { return null; }
    }

    public boolean contains(Location l) {
        if (!enabled || l.getWorld() == null || !l.getWorld().equals(world)) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    private Layer layerAt(int y) {
        int depth = y2 - y, acc = 0;
        for (Layer l : layers) { acc += l.depth(); if (depth < acc) return l; }
        return layers.get(layers.size() - 1);
    }

    private long left() { return Math.max(0, nextReset - System.currentTimeMillis()); }

    private void tick() {
        if (!enabled || world == null) { removeHolo(); return; }
        if (nextReset == 0 || System.currentTimeMillis() >= nextReset) reset();
        int sec = (int) Math.ceil(left() / 1000.0);
        for (int w : warnAt) if (w > 0 && sec <= w && warned.add(w))
            near(p -> { pl.msg(p, "<gold>⛏ Автошахта обновится через <white>" + w + " сек"); p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.4f); });
        if (sec % 5 == 0) orePercent = countOre();
        updateHolo(sec);
    }

    private void near(java.util.function.Consumer<Player> f) {
        Location c = new Location(world, (x1 + x2 + 1) / 2.0, y2, (z1 + z2 + 1) / 2.0);
        for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(c) < 40 * 40) f.accept(p);
    }

    private int countOre() {
        int total = 0, full = 0;
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) { total++; if (!world.getBlockAt(x, y, z).getType().isAir()) full++; }
        return total == 0 ? 0 : full * 100 / total;
    }

    public void reset() {
        if (world == null || layers.isEmpty()) return;
        if (!backup.exists()) saveOriginal();
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            if (l.getX() >= x1 && l.getX() < x2 + 1 && l.getZ() >= z1 && l.getZ() < z2 + 1 && l.getY() >= y1 && l.getY() < y2 + 1) {
                Location up = l.clone(); up.setY(y2 + 1);
                p.teleport(up);
                p.setFallDistance(0);
            }
        }
        for (int y = y2; y >= y1; y--) {
            Layer layer = layerAt(y);
            for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) world.getBlockAt(x, y, z).setType(layer.roll(), false);
        }
        nextReset = System.currentTimeMillis() + resetMs;
        warned.clear();
        orePercent = 100;
        near(p -> { pl.msg(p, "<gold>⛏ Автошахта обновлена! <gray>Чем глубже - тем ценнее руда."); p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.8f); });
    }

    private void saveOriginal() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (int x = x1; x <= x2; x++) for (int yy = y1; yy <= y2; yy++) for (int z = z1; z <= z2; z++)
            list.add(x + " " + yy + " " + z + " " + world.getBlockAt(x, yy, z).getBlockData().getAsString());
        y.set("world", world.getName());
        y.set("blocks", list);
        try { y.save(backup); pl.getLogger().info("Автошахта: исходные блоки (" + list.size() + ") сохранены в " + backup.getName() + ", откат: /mine remove"); }
        catch (Exception e) { pl.getLogger().warning("Автошахта: не удалось сохранить исходные блоки: " + e); }
    }

    private int restoreOriginal() {
        if (!backup.exists()) return 0;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(backup);
        World w = Bukkit.getWorld(y.getString("world", ""));
        if (w == null) return 0;
        int n = 0;
        for (String line : y.getStringList("blocks")) {
            String[] p = line.split(" ", 4);
            try { w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])).setBlockData(Bukkit.createBlockData(p[3]), false); n++; }
            catch (Exception ignored) {}
        }
        return n;
    }

    // ---------- голограмма ----------
    private void updateHolo(int sec) {
        if (holoLoc == null) return;
        if (holo == null || !holo.isValid()) {
            if (!holoLoc.isChunkLoaded()) return;
            holo = world.spawn(holoLoc, TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setShadowed(true);
                t.setDefaultBackground(false);
                t.setBackgroundColor(Color.fromARGB(110, 0, 20, 40));
                t.setLineWidth(400);
                t.setAlignment(TextDisplay.TextAlignment.CENTER);
                t.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
            });
        }
        StringBuilder lv = new StringBuilder();
        for (int i = 0; i < layers.size(); i++) lv.append(i == 0 ? "" : i % 3 == 0 ? "\n" : " <dark_gray>› ").append(layers.get(i).name());
        String col = orePercent >= 60 ? "<green>" : orePercent >= 25 ? "<yellow>" : "<red>";
        holo.text(mm("<gradient:#ffd36b:#ff8a00><bold>⛏ АВТОШАХТА</bold></gradient>\n<gray>Обновление через <white>" + (sec / 60) + ":" + String.format("%02d", sec % 60)
                + " <dark_gray>| <gray>руды " + col + orePercent + "%\n" + lv + "\n<dark_gray>чем глубже - тем ценнее"));
    }

    public void removeHolo() {
        if (holo != null) holo.remove();
        holo = null;
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntitiesByClass(TextDisplay.class)) if (e.getPersistentDataContainer().has(TAG)) e.remove();
    }

    // ---------- /mine ----------
    public void command(CommandSender s, String[] a) {
        boolean admin = !(s instanceof Player p) || p.hasPermission("anarchy.admin");
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        if (admin) switch (sub) {
            case "reset" -> { if (!enabled) { pl.msg(s, "<red>Шахта выключена: /mine build"); return; } reset(); pl.msg(s, "<green>Шахта обновлена"); return; }
            case "build" -> {
                pl.getConfig().set("mine.enabled", true); pl.saveConfig(); load();
                if (enabled) { reset(); pl.msg(s, "<green>Шахта построена и включена"); } else pl.msg(s, "<red>Ошибка в настройках mine - см. консоль");
                return;
            }
            case "remove" -> {
                enabled = false; removeHolo();
                pl.getConfig().set("mine.enabled", false); pl.saveConfig();
                int n = restoreOriginal();
                pl.msg(s, n > 0 ? "<green>Шахта выключена, исходные блоки возвращены: " + n : "<yellow>Шахта выключена (сохранённых исходных блоков нет)");
                return;
            }
            case "reload" -> { load(); pl.msg(s, enabled ? "<green>Настройки шахты перечитаны" : "<red>Шахта выключена или ошибка в настройках"); return; }
        }
        if (!enabled) { pl.msg(s, "<red>Автошахта сейчас закрыта"); return; }
        int sec = (int) Math.ceil(left() / 1000.0);
        pl.msg(s, "<gold>⛏ Автошахта <gray>(спавн, <white>/warp mine<gray>): обновление через <white>" + AnarchyCore.time(sec) + "<gray>, руды осталось <white>" + orePercent + "%");
        for (Layer l : layers) {
            StringBuilder b = new StringBuilder();
            int total = l.cum()[l.cum().length - 1], prev = 0;
            for (int i = 0; i < l.blocks().length; i++) {
                int w = l.cum()[i] - prev; prev = l.cum()[i];
                if (i > 0) b.append("<dark_gray>, ");
                b.append("<white><lang:").append(l.blocks()[i].translationKey()).append("> <gray>").append(Math.round(w * 100.0 / total)).append("%");
            }
            s.sendMessage(mm(" " + l.name() + " <dark_gray>(" + l.depth() + " сл.)<gray>: " + b));
        }
        if (admin) pl.msg(s, "<gray>Админ: <white>/mine reset | build | remove | reload");
    }
}
