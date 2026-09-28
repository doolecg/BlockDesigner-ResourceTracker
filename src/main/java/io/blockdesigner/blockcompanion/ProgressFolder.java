package io.blockdesigner.blockcompanion;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * BlockCompanion's progress folder ({@code ~/.blockcompanion/progress}): the builds the game has written progress for.
 * Scanning re-reads only files that changed, and a file that can't be read (partly written, damaged, a newer format)
 * keeps the last good data it had.
 */
final class ProgressFolder {
    private final Path folder;
    private final Map<Path, Entry> cache = new HashMap<>();

    /** What was read for a file: when it was last looked at, and its last good data (or null). */
    private record Entry(FileTime modified, long size, GameProgress good) {
    }

    /** One scan: whether the folder exists, and the builds in it, most recently updated first. */
    record Snapshot(boolean folderExists, List<GameProgress> builds) {
        static final Snapshot NONE = new Snapshot(false, List.of());

        Optional<GameProgress> byFileName(String name) {
            return builds.stream().filter(p -> p.file().getFileName().toString().equalsIgnoreCase(name)).findFirst();
        }
    }

    ProgressFolder(Path folder) {
        this.folder = folder;
    }

    /** Where BlockCompanion writes: {@code <user home>/.blockcompanion/progress}. */
    static Path defaultFolder() {
        return Path.of(System.getProperty("user.home"), ".blockcompanion", "progress");
    }

    Path folder() {
        return folder;
    }

    /** Looks at the folder again. Safe to call from any thread. */
    synchronized Snapshot scan() {
        if (!Files.isDirectory(folder)) {
            cache.clear();
            return Snapshot.NONE;
        }
        Set<Path> seen = new HashSet<>();
        List<GameProgress> builds = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
            for (Path f : files) {
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(f, BasicFileAttributes.class);
                } catch (IOException e) {
                    continue;
                }
                if (!attrs.isRegularFile()) continue;
                seen.add(f);
                Entry old = cache.get(f);
                Entry now = old;
                if (old == null || !old.modified().equals(attrs.lastModifiedTime()) || old.size() != attrs.size()) {
                    GameProgress good = old == null ? null : old.good();
                    try {
                        good = GameProgress.read(f);
                    } catch (IOException | RuntimeException e) {
                        // partly written or damaged: keep what we had
                    }
                    now = new Entry(attrs.lastModifiedTime(), attrs.size(), good);
                    cache.put(f, now);
                }
                if (now.good() != null) builds.add(now.good());
            }
        } catch (IOException e) {
            return new Snapshot(true, cache.values().stream().map(Entry::good).filter(g -> g != null).sorted(NEWEST_FIRST).toList());
        }
        cache.keySet().retainAll(seen);
        builds.sort(NEWEST_FIRST);
        return new Snapshot(true, List.copyOf(builds));
    }

    private static final Comparator<GameProgress> NEWEST_FIRST = Comparator.comparing(GameProgress::updated).reversed()
            .thenComparing(p -> p.file().getFileName().toString());

    /**
     * The build that belongs to a project: one whose {@code projectName} is the project's name, or whose schematic file
     * name is the project file's name (both ignoring case and surrounding spaces). If several match, the most recently
     * updated wins.
     */
    static Optional<GameProgress> match(List<GameProgress> builds, String projectName, String projectFileName) {
        String name = norm(projectName), fileName = norm(projectFileName);
        if (name == null && fileName == null) return Optional.empty();
        return builds.stream()
                .filter(p -> (name != null && name.equals(norm(p.projectName())))
                        || (fileName != null && fileName.equals(norm(p.schematicFileName()))))
                .min(NEWEST_FIRST);
    }

    private static String norm(String s) {
        if (s == null || s.isBlank()) return null;
        return s.strip().toLowerCase(Locale.ROOT);
    }
}
