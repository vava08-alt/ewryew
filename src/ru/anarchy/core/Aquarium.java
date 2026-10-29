package ru.anarchy.core;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Оформление спавна-аквариума: левитирующие свечи и рыбки в аквариумах. Настройки: aquarium в config.yml */
public final class Aquarium {
    private final AnarchyCore pl;
    private final NamespacedKey TAG;
    private final List<BlockDisplay> candles = new ArrayList<>();
    private final List<double[]> candlePos = new ArrayList<>();
    private final List<double[]> tanks = new ArrayList<>();
    private final Map<Integer, List<TropicalFish>> fish = new HashMap<>();
    private World world;
    private boolean up;
    private long tick;

    public Aquarium(AnarchyCore pl) {
        this.pl = pl;
        TAG = new NamespacedKey(pl, "aquarium");
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 60, 10);
    }

    private static List<double[]> nums(ConfigurationSection s, String path) {
        List<double[]> out = new ArrayList<>();
        for (Object o : s.getList(path, List.of())) {
            if (o instanceof List<?> l) {
                double[] d = new double[l.size()];
                for (int i = 0; i < l.size(); i++) d[i] = ((Number) l.get(i)).doubleValue();
                out.add(d);
            } else if (o instanceof String str) {
                String[] p = str.split("[,; ]+");
                double[] d = new double[p.length];
                for (int i = 0; i < p.length; i++) d[i] = Double.parseDouble(p[i]);
                out.add(d);
            }
        }
        return out;
    }

    public void spawnAll() {
        removeAll();
        ConfigurationSection s = pl.getConfig().getConfigurationSection("aquarium");
        if (s == null || !s.getBoolean("enabled", true)) return;
        world = Bukkit.getWorld(s.getString("world", "spawn"));
        if (world == null) world = pl.worlds().hub();
        if (world == null) return;
        try { candlePos.addAll(nums(s, "candles")); tanks.addAll(nums(s, "tanks")); }
        catch (Exception e) { pl.getLogger().warning("aquarium: неверные координаты - " + e.getMessage()); }
        BlockData data;
        try { data = Bukkit.createBlockData(s.getString("candle-block", "minecraft:white_candle[lit=true,candles=1]")); }
        catch (Exception e) { data = Bukkit.createBlockData("minecraft:white_candle[lit=true]"); }
        final BlockData cd = data;
        for (double[] p : candlePos) {
            if (p.length < 3) continue;
            Location l = new Location(world, p[0], p[1], p[2]);
            world.addPluginChunkTicket(l.getBlockX() >> 4, l.getBlockZ() >> 4, pl);
            BlockDisplay bd = world.spawn(l, BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.setBlock(cd);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(0.8f);
                d.setTransformation(tf(0, 0));
                d.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
            });
            candles.add(bd);
        }
        for (double[] t : tanks) if (t.length >= 4) world.addPluginChunkTicket((int) Math.floor(t[0]) >> 4, (int) Math.floor(t[2]) >> 4, pl);
    }

    /** Свеча 0.8 размера, центрирована по блоку, слегка повернута; dy - высота "парения" */
    private static Transformation tf(float dy, float yaw) {
        float sc = 0.8f;
        return new Transformation(new Vector3f(-0.5f * sc, dy, -0.5f * sc), new AxisAngle4f(yaw, 0, 1, 0),
                new Vector3f(sc, sc, sc), new AxisAngle4f());
    }

    public void removeAll() {
        for (BlockDisplay d : candles) if (d != null) d.remove();
        candles.clear(); candlePos.clear(); tanks.clear();
        for (List<TropicalFish> l : fish.values()) for (TropicalFish f : l) f.remove();
        fish.clear();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities())
            if (e.getPersistentDataContainer().has(TAG, PersistentDataType.BYTE)) e.remove();
    }

    private void tick() {
        if (world == null) return;
        tick++;
        boolean anyone = false;
        for (Player p : world.getPlayers()) { anyone = true; break; }
        if (!anyone) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // покачивание: раз в 2 сек меняем цель интерполяции
        if (tick % 4 == 0) {
            up = !up;
            for (int i = 0; i < candles.size(); i++) {
                BlockDisplay d = candles.get(i);
                if (d == null || !d.isValid()) continue;
                float ph = (i % 3) * 0.06f;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(40);
                Transformation t = d.getTransformation();
                d.setTransformation(new Transformation(new Vector3f(t.getTranslation().x, up ? 0.18f + ph : 0f, t.getTranslation().z),
                        t.getLeftRotation(), t.getScale(), t.getRightRotation()));
            }
        }
        // огонёк свечи (у BlockDisplay пламени нет - рисуем частицей)
        for (int i = 0; i < candles.size(); i++) {
            BlockDisplay d = candles.get(i);
            if (d == null || !d.isValid()) {
                if (i < candlePos.size() && world.isChunkLoaded((int) Math.floor(candlePos.get(i)[0]) >> 4, (int) Math.floor(candlePos.get(i)[2]) >> 4)) { spawnAll(); return; }
                continue;
            }
            double y = d.getTransformation().getTranslation().y;
            Location f = d.getLocation().add(0, 0.42 + y, 0);
            world.spawnParticle(Particle.SMALL_FLAME, f, 1, 0.01, 0.01, 0.01, 0.002);
            if (r.nextInt(6) == 0) world.spawnParticle(Particle.END_ROD, f.add(0, 0.15, 0), 1, 0.05, 0.05, 0.05, 0.005);
        }
        // рыбки: поддерживаем по N в каждом аквариуме
        if (tick % 10 == 0) {
            int per = pl.getConfig().getInt("aquarium.fish-per-tank", 6);
            for (int i = 0; i < tanks.size(); i++) {
                double[] t = tanks.get(i);
                if (t.length < 5) continue;
                List<TropicalFish> l = fish.computeIfAbsent(i, k -> new ArrayList<>());
                l.removeIf(f -> !f.isValid());
                Location c = new Location(world, t[0], (t[1] + t[4]) / 2, t[2]);
                if (!c.isChunkLoaded()) continue;
                for (TropicalFish f : l) // уплыла - возвращаем
                    if (f.getLocation().distanceSquared(c) > (t[3] + 3) * (t[3] + 3) + 64) f.teleport(c);
                for (int k = l.size(); k < per; k++) {
                    double a = r.nextDouble(Math.PI * 2), rr = r.nextDouble(t[3] * 0.7);
                    Location at = c.clone().add(Math.cos(a) * rr, r.nextDouble(-2, 2), Math.sin(a) * rr);
                    if (at.getBlock().getType() != Material.WATER) at = c;
                    if (at.getBlock().getType() != Material.WATER) break;
                    TropicalFish f = world.spawn(at, TropicalFish.class, e -> {
                        e.setPersistent(false);
                        e.setRemoveWhenFarAway(false);
                        e.setInvulnerable(true);
                        e.setPattern(TropicalFish.Pattern.values()[r.nextInt(TropicalFish.Pattern.values().length)]);
                        e.setBodyColor(DyeColor.values()[r.nextInt(DyeColor.values().length)]);
                        e.setPatternColor(DyeColor.values()[r.nextInt(DyeColor.values().length)]);
                        e.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
                    });
                    l.add(f);
                }
            }
        }
    }
}
