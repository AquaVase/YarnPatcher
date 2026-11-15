package com.github.yarnpatcher;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

final class SourceInstaller {
    static void requireDisjoint(Path a, Path b) throws IOException {
        a = a.toAbsolutePath().normalize();
        b = b.toAbsolutePath().normalize();
        if (a.startsWith(b) || b.startsWith(a)) throw new IOException("Directories must be disjoint: " + a + " and " + b);
    }

    static Path install(Path staged, Path destination, Path backupRoot) throws IOException {
        staged = staged.toAbsolutePath().normalize();
        destination = destination.toAbsolutePath().normalize();
        backupRoot = backupRoot.toAbsolutePath().normalize();
        requireDisjoint(staged, destination);
        requireDisjoint(backupRoot, destination);
        requireDisjoint(staged, backupRoot);
        try (Stream<Path> files = Files.walk(staged)) {
            if (files.noneMatch(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))) {
                throw new IOException("Refusing to install an empty Java source tree: " + staged);
            }
        }
        Files.createDirectories(destination.getParent());
        Path next = destination.resolveSibling(destination.getFileName() + ".yarnpatcher-" + UUID.randomUUID());
        Path backup = backupRoot.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID());
        // Copy everything before moving the original tree. Never follow symlinks.
        try {
            copyTree(staged, next);
            boolean hadOriginal = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
            if (hadOriginal) {
                Files.createDirectories(backupRoot);
                Files.move(destination, backup);
            }
            try {
                try {
                    Files.move(next, destination);
                } catch (AccessDeniedException windowsDirectoryLock) {
                    // Windows indexers/file watchers may prevent a rename of a newly copied tree.
                    // Preserve the backup until a full copy has succeeded instead.
                    copyTree(next, destination);
                }
            } catch (IOException failure) {
                try {
                    deleteTree(destination);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                if (hadOriginal) {
                    try {
                        Files.move(backup, destination);
                    } catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                    }
                }
                throw failure;
            }
            return hadOriginal ? backup : null;
        }
        finally {
            deleteTree(next);
        }
    }

    static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) throw new IOException("Symlinks are not supported in source trees: " + file);
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
    });
}

static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
