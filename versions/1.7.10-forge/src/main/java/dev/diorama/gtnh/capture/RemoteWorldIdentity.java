package dev.diorama.gtnh.capture;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import org.jspecify.annotations.Nullable;

/** Explicit local remote-world identity. No automatic inference of proxy sub-worlds. */
public final class RemoteWorldIdentity {

    private RemoteWorldIdentity() {}

    public static @Nullable Path open(Path game, @Nullable String connected, @Nullable String configured,
        @Nullable String worldLabel) throws IOException {
        if (connected == null || configured == null
            || worldLabel == null
            || !connected.equals(configured)
            || !valid(connected, 255)
            || !valid(worldLabel, 128)) return null;
        Path base = game.toAbsolutePath()
            .normalize()
            .resolve("diorama-remote-v1");
        Path root = base.resolve(digest(connected, worldLabel));
        directory(base);
        directory(root);
        Path lock = root.resolve("session.lock");
        if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
            try (DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                out.writeLong(new SecureRandom().nextLong());
            } catch (FileAlreadyExistsException concurrent) {
                // Validate the winning file below. Never replace an existing identity.
            }
        }
        if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS) || Files.size(lock) != Long.BYTES)
            throw new IOException("Invalid Diorama remote identity lock");
        try (DataInputStream in = new DataInputStream(Files.newInputStream(lock, LinkOption.NOFOLLOW_LINKS))) {
            in.readLong();
            if (in.read() != -1) throw new IOException("Invalid Diorama remote identity lock");
        }
        return root;
    }

    private static boolean valid(String value, int limit) {
        if (value.isEmpty() || value.length() > limit || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isSurrogate(c)) return false;
        }
        return true;
    }

    private static void directory(Path path) throws IOException {
        try {
            Files.createDirectory(path);
        } catch (FileAlreadyExistsException exists) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw exists;
        }
    }

    private static String digest(String endpoint, String label) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(endpoint.getBytes(StandardCharsets.UTF_8));
            hash.update((byte) 0);
            byte[] bytes = hash.digest(label.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
