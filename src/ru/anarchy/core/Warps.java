package ru.anarchy.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Варпы сервера: /warp - меню, /warp <название> - перенос к NPC на спавне (встаёшь перед ним, лицом к нему). */
public final class Warps {
    private final AnarchyCore pl;

    public Warps(AnarchyCore pl) { this.pl = pl; }

    private ConfigurationSection sec() {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("warps");
        return s != null ? s : new YamlConfiguration();
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (String id : sec().getKeys(false)) {
            out.add(id);
            out.addAll(sec().getStringList(id + ".aliases"));
        }
        return out;
    }

    private String resolve(String in) {
        String q = in.toLowerCase(Locale.ROOT);
        for (String id : sec().getKeys(false)) {
            if (id.equalsIgnoreCase(q)) return id;
            for (String al : sec().getStringList(id + ".aliases")) if (al.toLowerCase(Locale.ROOT).equals(q)) return id;
        }
        return null;
    }

    public void command(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Варпы - только в игре"); return; }
        if (a.length == 0) { menu(p); return; }
        String id = resolve(a[0]);
        if (id == null) { pl.msg(p, "<red>Нет такого варпа. Список: <white>/warp"); return; }
        go(p, id);
    }

    public void go(Player p, String id) {
        Location to = target(id);
        if (to == null) { pl.msg(p, "<red>Варп сейчас недоступен"); return; }
        String name = sec().getString(id + ".name", id);
        pl.teleports().start(p, plain(name), () -> {
            if (!p.isOnline()) return;
            p.teleport(to);
            p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.1f);
            pl.msg(p, "<gray>Варп: " + name);
        });
    }

    private static String plain(String mini) { return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(mm(mini)); }

    /** Точка перед NPC лицом к нему; если перед ним занято - чуть сбоку или дальше. Варп без NPC - location из конфига. */
    private Location target(String id) {
        ConfigurationSection w = sec().getConfigurationSection(id);
        if (w == null) return null;
        Location n = PlayerData.locFromString(pl.getConfig().getString("npcs." + w.getString("npc", id) + ".location", ""));
        if (n == null || n.getWorld() == null) {
            Location own = PlayerData.locFromString(w.getString("location", ""));
            return own != null && own.getWorld() != null ? own : null;
        }
        double yaw = Math.toRadians(n.getYaw()), fx = -Math.sin(yaw), fz = Math.cos(yaw);
        for (double dist : new double[]{2.5, 2.0, 3.0, 1.5}) for (double turn : new double[]{0, 35, -35, 70, -70}) {
            double t = Math.toRadians(turn), dx = fx * Math.cos(t) - fz * Math.sin(t), dz = fx * Math.sin(t) + fz * Math.cos(t);
            Location c = n.clone().add(dx * dist, 0, dz * dist);
            Block feet = c.getBlock();
            if (!safe(feet)) {
                if (safe(feet.getRelative(BlockFace.UP))) feet = feet.getRelative(BlockFace.UP);
                else if (safe(feet.getRelative(BlockFace.DOWN))) feet = feet.getRelative(BlockFace.DOWN);
                else continue;
            }
            c.setY(feet.getY());
            c.setYaw((float) Math.toDegrees(-Math.atan2(n.getX() - c.getX(), n.getZ() - c.getZ())));
            c.setPitch(8f);
            return c;
        }
        return n.clone();
    }

    private static boolean safe(Block feet) {
        return feet.isPassable() && !feet.isLiquid() && feet.getRelative(BlockFace.UP).isPassable() && !feet.getRelative(BlockFace.DOWN).isPassable();
    }

    public void menu(Player p) {
        Menus.Menu m = new Menus.Menu(3, "<gradient:#00f0ff:#0080ff><bold>✦ Варпы</bold></gradient>");
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        int i = 0;
        for (String id : sec().getKeys(false)) {
            if (i >= slots.length) break;
            ConfigurationSection w = sec().getConfigurationSection(id);
            if (w == null) continue;
            Material icon = Material.matchMaterial(w.getString("icon", "ENDER_PEARL"));
            List<String> lore = new ArrayList<>(w.getStringList("lore"));
            lore.add("");
            lore.add("<yellow>▶ /warp " + id);
            m.set(slots[i++], Menus.icon(icon != null ? icon : Material.ENDER_PEARL, "<bold>" + w.getString("name", id), lore.toArray(new String[0])), c -> { p.closeInventory(); go(p, id); });
        }
        m.fill();
        p.openInventory(m.inv);
    }
}
