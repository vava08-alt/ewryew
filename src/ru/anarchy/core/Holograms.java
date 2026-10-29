package ru.anarchy.core;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Голограммы на спавне (TextDisplay): приветствие, топы, кейсы, портал. */
public final class Holograms {
    private record Holo(String id, Location loc, List<String> lines, float scale, boolean dynamic, TextDisplay[] ent) {}

    private final AnarchyCore pl;
    private final NamespacedKey TAG;
    private final List<Holo> holos = new ArrayList<>();

    public Holograms(AnarchyCore pl) {
        this.pl = pl;
        TAG = new NamespacedKey(pl, "holo");
        Bukkit.getScheduler().runTaskTimer(pl, this::update, 100, 200);
    }

    public void spawnAll() {
        removeAll();
        ConfigurationSection s = pl.getConfig().getConfigurationSection("holograms");
        if (s != null) for (String id : s.getKeys(false)) {
            ConfigurationSection h = s.getConfigurationSection(id);
            if (h == null) continue;
            Location l = PlayerData.locFromString(h.getString("location", ""));
            if (l == null) { pl.getLogger().warning("Голограмма " + id + ": неверная позиция"); continue; }
            add(id, l, h.getStringList("lines"), (float) h.getDouble("scale", 1.0));
        }
        for (Cases.Case c : pl.cases().all()) {
            if (c.loc == null) continue;
            List<String> lines = new ArrayList<>();
            lines.add(c.display);
            lines.addAll(pl.getConfig().getStringList("cases." + c.id + ".hologram"));
            lines.add("<gray>Ключ: " + (c.priceAri > 0 ? "<light_purple>" + c.priceAri + " " + pl.ariName() : "")
                    + (c.priceAri > 0 && c.priceCoins > 0 ? " <gray>или " : "") + (c.priceCoins > 0 ? "<gold>" + c.priceCoins + " " + pl.cur() : ""));
            lines.add("<yellow><bold>▶ ПКМ - открыть");
            add("case_" + c.id, c.loc.clone().add(0.5, 1.35, 0.5), lines, 0.9f);
        }
        update();
    }

    private void add(String id, Location l, List<String> lines, float scale) {
        World w = l.getWorld();
        w.addPluginChunkTicket(l.getBlockX() >> 4, l.getBlockZ() >> 4, pl);
        boolean dyn = String.join("", lines).contains("{");
        TextDisplay td = w.spawn(l, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setSeeThrough(false);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(110, 0, 20, 40));
            t.setLineWidth(400);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setViewRange(1.2f);
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            t.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, id);
            t.text(mm(render(lines)));
        });
        holos.add(new Holo(id, l, lines, scale, dyn, new TextDisplay[]{td}));
    }

    public void removeAll() {
        for (Holo h : holos) if (h.ent()[0] != null) h.ent()[0].remove();
        holos.clear();
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntitiesByClass(TextDisplay.class))
                if (e.getPersistentDataContainer().has(TAG, PersistentDataType.STRING)) e.remove();
    }

    private void update() {
        // сущности, пропавшие при выгрузке чанка, пересоздаём все сразу (раньше - по одной за 10 сек)
        for (Holo h : new ArrayList<>(holos)) {
            TextDisplay t = h.ent()[0];
            if (t == null || !t.isValid()) {
                Location l = h.loc();
                if (!l.isChunkLoaded()) continue;
                holos.remove(h);
                add(h.id(), l, h.lines(), h.scale());
                continue;
            }
            if (h.dynamic()) t.text(mm(render(h.lines())));
        }
    }

    private String render(List<String> lines) {
        String s = String.join("\n", lines);
        if (!s.contains("{")) return s;
        List<PlayerData> all = new ArrayList<>(pl.store().all());
        if (s.contains("{top_kills}")) s = s.replace("{top_kills}", top(all, Comparator.comparingInt((PlayerData d) -> d.kills).reversed(), d -> "<red>" + d.kills));
        if (s.contains("{top_balance}")) s = s.replace("{top_balance}", top(all, Comparator.comparingLong((PlayerData d) -> d.balance).reversed(), d -> "<gold>" + AnarchyCore.fmt(d.balance) + " " + pl.cur()));
        if (s.contains("{top_playtime}")) s = s.replace("{top_playtime}", top(all, Comparator.comparingLong((PlayerData d) -> d.playMinutes).reversed(), d -> "<green>" + (d.playMinutes / 60) + " ч"));
        if (s.contains("{top_clans}")) s = s.replace("{top_clans}", pl.clans().topText());
        return s.replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("{event}", pl.events().status().isEmpty() ? "<gray>скоро" : pl.events().status())
                .replace("{site}", pl.getConfig().getString("donate-site", ""))
                .replace("{players}", String.valueOf(all.size()));
    }

    private String top(List<PlayerData> all, Comparator<PlayerData> cmp, java.util.function.Function<PlayerData, String> val) {
        List<PlayerData> list = all.stream().filter(d -> d.passHash != null && !"admin".equals(d.rank)).sorted(cmp).limit(10).toList();
        StringBuilder b = new StringBuilder();
        String[] medal = {"<#ffd700>①", "<#c0c0c0>②", "<#cd7f32>③"};
        for (int i = 0; i < 10; i++) {
            if (i > 0) b.append("\n");
            String n = i < 3 ? medal[i] : "<gray>" + (i + 1) + ".";
            if (i < list.size()) b.append(n).append(" <white>").append(AnarchyCore.esc(list.get(i).name)).append(" <dark_gray>- ").append(val.apply(list.get(i)));
            else b.append(n).append(" <dark_gray>---");
        }
        return b.toString();
    }
}
