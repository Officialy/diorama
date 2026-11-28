package dev.diorama.gtnh.capture;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Properties;

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

    /** Deliberate local profile for one exact endpoint. No automatic proxy-world guessing. */
    public static synchronized @Nullable String profile(Path game,String endpoint) throws IOException {
        if (!valid(endpoint,255)) return null;
        String label=profiles(game).getProperty(endpoint);
        return label!=null&&valid(label,128)?label:null;
    }

    /** Change future connections only; the caller freezes the active connection's chosen label. */
    public static synchronized void setProfile(Path game,String endpoint,@Nullable String label) throws IOException {
        if (!valid(endpoint,255) || label!=null&&!valid(label,128)) throw new IllegalArgumentException("Invalid server profile");
        Properties props=profiles(game);
        if(label==null)props.remove(endpoint);
        else props.setProperty(endpoint,label);
        if(props.size()>256)throw new IOException("Too many remote profiles");
        Path target=profileFile(game), temp=Files.createTempFile(target.getParent(),"diorama-profiles-",".tmp");
        try {
            try(OutputStream out=Files.newOutputStream(temp)){props.store(out,"Explicit Diorama server/world identities");}
            try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException fallback) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static Path profileFile(Path game) {
        return game.toAbsolutePath().normalize().resolve("diorama-remote-profiles.properties");
    }

    private static Properties profiles(Path game) throws IOException {
        Path path=profileFile(game);
        Properties props=new Properties();
        BasicFileAttributes attributes;
        try { attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
        catch(NoSuchFileException missing) { return props; }
        if(!attributes.isRegularFile()||attributes.size()>65536)throw new IOException("Invalid remote profile file");
        try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) { props.load(in); }
        if(props.size()>256)throw new IOException("Too many remote profiles");
        for(String endpoint:props.stringPropertyNames())
            if(!valid(endpoint,255)||!valid(props.getProperty(endpoint),128))throw new IOException("Invalid stored remote profile");
        return props;
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
