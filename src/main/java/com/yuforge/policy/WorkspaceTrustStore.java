package com.yuforge.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用户显式信任过的工作区列表。
 *
 * <p>只保存规范化后的精确目录路径；信任一个目录不会自动把其父目录或子目录纳入信任范围。</p>
 */
public final class WorkspaceTrustStore {
    private final Path storeFile;

    public WorkspaceTrustStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    public static WorkspaceTrustStore forCurrentUser() {
        return new WorkspaceTrustStore(Path.of(System.getProperty("user.home"), ".yuforge", "workspaces", "trusted.txt"));
    }

    public boolean isTrusted(Path workspace) {
        return readTrustedPaths().contains(normalize(workspace));
    }

    public void trust(Path workspace) throws IOException {
        Set<String> trustedPaths = readTrustedPaths();
        trustedPaths.add(normalize(workspace));
        writeTrustedPaths(trustedPaths);
    }

    public Path storeFile() {
        return storeFile;
    }

    static String normalize(Path workspace) {
        Path absolute = workspace.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath().toString();
        } catch (IOException ignored) {
            return absolute.toString();
        }
    }

    private Set<String> readTrustedPaths() {
        Set<String> result = new LinkedHashSet<>();
        if (!Files.isRegularFile(storeFile)) {
            return result;
        }
        try {
            for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                String path = line.trim();
                if (!path.isEmpty() && !path.startsWith("#")) {
                    result.add(path);
                }
            }
        } catch (IOException ignored) {
            // 信任存储不可读时采用最小权限：视为未信任，由启动交互重新确认。
        }
        return result;
    }

    private void writeTrustedPaths(Set<String> trustedPaths) throws IOException {
        Files.createDirectories(storeFile.getParent());
        Path temporary = Files.createTempFile(storeFile.getParent(), "trusted-", ".tmp");
        try {
            Files.write(temporary, trustedPaths, StandardCharsets.UTF_8);
            Files.move(temporary, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, storeFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
