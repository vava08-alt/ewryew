package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.Locale;

public final class Util {
    /** Настоящая земля: ниже брёвен, листвы и растений. */
    public static int groundY(org.bukkit.World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        for (int i = 0; i < 40 && y > w.getMinHeight(); i++) {
            org.bukkit.block.Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (org.bukkit.Tag.LOGS.isTagged(m) || org.bukkit.Tag.LEAVES.isTagged(m) || !m.isSolid() && !b.isLiquid() || m == Material.MUSHROOM_STEM || m == Material.BAMBOO) { y--; continue; }
            break;
        }
        return y;
    }

    /** Игрока замуровало (блоки вернулись на место) - поднять на поверхность. */
    public static void unstuck(org.bukkit.entity.Player p) {
        org.bukkit.Location l = p.getLocation();
        org.bukkit.block.Block feet = l.getBlock(), head = feet.getRelative(0, 1, 0);
        if (feet.isPassable() && head.isPassable()) return;
        int y = l.getWorld().getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) + 1;
        org.bukkit.Location to = new org.bukkit.Location(l.getWorld(), l.getBlockX() + 0.5, y, l.getBlockZ() + 0.5, l.getYaw(), l.getPitch());
        p.teleport(to);
        p.setFallDistance(0);
    }

    private Util() {}

    public static String itemToString(ItemStack it) { return it == null ? null : Base64.getEncoder().encodeToString(it.serializeAsBytes()); }
    public static ItemStack itemFromString(String s) {
        try { return s == null ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(s)); } catch (Exception e) { return null; }
    }

    /** 10m, 2h, 7d, 30s, perm -> миллисекунды (0 = навсегда, -1 = ошибка). */
    public static long parseTime(String s) {
        if (s == null) return -1;
        s = s.toLowerCase(Locale.ROOT);
        if (s.equals("perm") || s.equals("навсегда") || s.equals("forever")) return 0;
        try {
            long n = Long.parseLong(s.substring(0, s.length() - 1));
            return switch (s.charAt(s.length() - 1)) {
                case 's', 'с' -> n * 1000;
                case 'm', 'м' -> n * 60_000;
                case 'h', 'ч' -> n * 3_600_000;
                case 'd', 'д' -> n * 86_400_000;
                case 'w', 'н' -> n * 7 * 86_400_000;
                default -> -1;
            };
        } catch (Exception e) { return -1; }
    }

    public static boolean isTime(String s) { return parseTime(s) >= 0; }

    public static String left(long until) {
        if (until <= 0) return "навсегда";
        return AnarchyCore.time(Math.max(0, (until - System.currentTimeMillis()) / 1000));
    }

    public static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    public static String join(String[] a, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < a.length; i++) { if (b.length() > 0) b.append(' '); b.append(a[i]); }
        return b.toString();
    }

    public static String matName(Material m) { return m.name().toLowerCase(Locale.ROOT).replace('_', ' '); }

    public static void sync(Runnable r) { Bukkit.getScheduler().runTask(AnarchyCore.INSTANCE, r); }
}
