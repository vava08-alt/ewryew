package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Автобэкапы: каждые N часов сохраняет миры и архивирует их (+ данные плагина) в папку backups/. Без игровых команд. */
public final class Backups {
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
    private final AnarchyCore pl;
    private final AtomicBoolean running = new AtomicBoolean();

    public Backups(AnarchyCore pl) {
        this.pl = pl;
        if (!pl.getConfig().getBoolean("backups.enabled", true)) return;
        long ticks = Math.max(1, (long) (pl.getConfig().getDouble("backups.interval-hours", 6) * 72_000));
        long first = Math.max(1200, (long) (pl.getConfig().getDouble("backups.first-after-minutes", 30) * 1200));
        Bukkit.getScheduler().runTaskTimer(pl, this::run, first, ticks);
    }

    private File root() { return Bukkit.getWorldContainer().getAbsoluteFile(); }

    private File dir() {
        String d = pl.getConfig().getString("backups.folder", "backups");
        File f = new File(d);
        return f.isAbsolute() ? f : new File(root(), d);
    }

    /** Запуск на главном потоке: сохранить всё, затем архивировать асинхронно. */
    public void run() {
        if (!running.compareAndSet(false, true)) { pl.getLogger().warning("[Бэкап] предыдущий бэкап ещё идёт, пропуск"); return; }
        List<File> sources = new ArrayList<>();
        try {
            Bukkit.savePlayers();
            pl.store().save(true);
            try { pl.clans().save(); } catch (Throwable ignored) {}
            List<String> names = pl.getConfig().getStringList("backups.worlds");
            for (World w : Bukkit.getWorlds()) {
                if (!names.isEmpty() && !names.contains(w.getName())) continue;
                if (w.getName().equals("captcha") || w.getName().equals("arena")) continue;
                w.save();
                sources.add(w.getWorldFolder());
            }
            if (pl.getConfig().getBoolean("backups.include-plugins", true)) sources.add(pl.getDataFolder());
            for (String extra : pl.getConfig().getStringList("backups.extra")) { File f = new File(root(), extra); if (f.exists()) sources.add(f); }
        } catch (Throwable t) { running.set(false); pl.getLogger().severe("[Бэкап] ошибка сохранения: " + t); return; }
        pl.getLogger().info("[Бэкап] миры сохранены, архивирую...");
        // небольшая пауза, чтобы асинхронная запись чанков успела закончиться
        Bukkit.getScheduler().runTaskLaterAsynchronously(pl, () -> zip(sources), 100);
    }

    private void zip(List<File> sources) {
        long start = System.currentTimeMillis();
        File out = null;
        try {
            File dir = dir();
            dir.mkdirs();
            long need = 0;
            for (File s : sources) need += size(s.toPath());
            if (dir.getUsableSpace() < need + 512L * 1024 * 1024) { pl.getLogger().severe("[Бэкап] мало места на диске (нужно ~" + mb(need) + " МБ), пропуск"); return; }
            out = new File(dir, "backup_" + LocalDateTime.now().format(DF) + ".zip");
            File tmp = new File(dir, out.getName() + ".part");
            Path rootPath = root().toPath();
            try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
                z.setLevel(Math.max(0, Math.min(9, pl.getConfig().getInt("backups.compression", 3))));
                byte[] buf = new byte[1 << 16];
                for (File s : sources) {
                    Path base = s.toPath().toAbsolutePath();
                    Files.walkFileTree(base, new SimpleFileVisitor<>() {
                        @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                            return d.toAbsolutePath().startsWith(dir.toPath().toAbsolutePath()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                            String n = f.getFileName().toString();
                            if (n.equals("session.lock") || n.endsWith(".tmp")) return FileVisitResult.CONTINUE;
                            Path rel = f.toAbsolutePath().startsWith(rootPath) ? rootPath.relativize(f.toAbsolutePath()) : base.getParent().relativize(f.toAbsolutePath());
                            try (InputStream in = new BufferedInputStream(Files.newInputStream(f))) {
                                ZipEntry e = new ZipEntry(rel.toString().replace('\\', '/'));
                                e.setTime(a.lastModifiedTime().toMillis());
                                z.putNextEntry(e);
                                int r; while ((r = in.read(buf)) > 0) z.write(buf, 0, r);
                                z.closeEntry();
                            } catch (IOException ex) { pl.getLogger().warning("[Бэкап] пропущен " + rel + ": " + ex.getMessage()); }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFileFailed(Path f, IOException ex) { return FileVisitResult.CONTINUE; }
                    });
                }
            }
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            pl.getLogger().info("[Бэкап] готово: " + out.getName() + " (" + mb(out.length()) + " МБ, " + (System.currentTimeMillis() - start) / 1000 + " с)");
            cleanup(dir);
        } catch (Throwable t) {
            pl.getLogger().severe("[Бэкап] ошибка: " + t);
        } finally { running.set(false); }
    }

    private void cleanup(File dir) {
        File[] list = dir.listFiles((d, n) -> n.startsWith("backup_") && n.endsWith(".zip"));
        if (list == null) return;
        Arrays.sort(list, Comparator.comparing(File::getName));
        int keep = Math.max(1, pl.getConfig().getInt("backups.keep", 8));
        double maxDays = pl.getConfig().getDouble("backups.max-age-days", 0);
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.length - 1; i++) {   // самый свежий не удаляем никогда
            boolean tooMany = i < list.length - keep;
            boolean tooOld = maxDays > 0 && now - list[i].lastModified() > maxDays * 86_400_000L;
            if ((tooMany || tooOld) && list[i].delete()) pl.getLogger().info("[Бэкап] удалён старый " + list[i].getName());
        }
        File[] parts = dir.listFiles((d, n) -> n.endsWith(".zip.part"));
        if (parts != null) for (File f : parts) if (now - f.lastModified() > 86_400_000L) f.delete();
    }

    private static long size(Path p) {
        try (var s = Files.walk(p)) { return s.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum(); } catch (Exception e) { return 0; }
    }

    private static long mb(long b) { return b / 1024 / 1024; }
}
