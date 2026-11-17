package dev.diorama.gtnh.client;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Synchronous local ownership transaction, not a renderer or a scheduling surface. */
final class PreviewUpload {

    private PreviewUpload() {}

    /**
     * The local owner alone may dispose an in-flight allocation. publish must consist only of
     * nonreentrant field assignments: no native calls, events or user callbacks. Once it returns,
     * the caller owns the allocation; every other exit disposes it after upload has unwound.
     */
    static <T> boolean transfer(Supplier<T> create, Consumer<T> upload, BooleanSupplier accepts, Consumer<T> publish,
        Consumer<T> dispose) {
        Objects.requireNonNull(create, "create");
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(accepts, "accepts");
        Objects.requireNonNull(publish, "publish");
        Objects.requireNonNull(dispose, "dispose");
        if (!accepts.getAsBoolean()) return false;
        T local = Objects.requireNonNull(create.get(), "allocation");
        boolean transferred = false;
        try {
            if (!accepts.getAsBoolean()) return false;
            upload.accept(local);
            if (!accepts.getAsBoolean()) return false;
            publish.accept(local);
            transferred = true;
            return true;
        } finally {
            if (!transferred) dispose.accept(local);
        }
    }
}
