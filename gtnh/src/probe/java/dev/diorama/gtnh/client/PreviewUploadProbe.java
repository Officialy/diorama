package dev.diorama.gtnh.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Numeric allocation ownership model; not substitute Minecraft or GL classes. */
public final class PreviewUploadProbe {

    private static int assertions;
    private static MessageDigest hash;

    public static void main(String[] args) throws Exception {
        hash = MessageDigest.getInstance("SHA-256");
        Ledger hazard = new Ledger();
        Allocation example = hazard.allocate();
        int old = example.id();
        example.dispose();
        require(example.id() != old, "model getter resurrects a deleted allocation");
        example.dispose();
        require(hazard.live.isEmpty(), "hazard example cleaned");

        for (int scenario = 0; scenario < 7; scenario++) {
            State state = new State();
            final int mode = scenario;
            boolean accepted = false, threw = false;
            try {
                accepted = PreviewUpload.transfer(() -> {
                    Allocation allocation = state.ledger.allocate();
                    if (mode == 1) state.current = false;
                    return allocation;
                }, allocation -> {
                    state.uploads++;
                    require(state.published == null, "in-flight allocation never published");
                    if (mode == 2) {
                        state.close();
                        require(state.ledger.deletes == 0, "reentrant close cannot delete local allocation");
                    }
                    if (mode == 3) state.current = false;
                    // The upload continues using a lazy allocating getter after close/revoke.
                    state.preparedId = allocation.id();
                    require(state.ledger.allocations == 1, "no resurrection while local owns upload");
                    if (mode == 4) throw new IllegalStateException("upload failure");
                    if (mode == 5) state.failAcceptance = true;
                }, () -> {
                    if (state.failAcceptance) throw new IllegalStateException("acceptance failure");
                    return state.current;
                }, allocation -> {
                    if (mode == 6) throw new IllegalStateException("publication failure before transfer");
                    state.published = allocation;
                    state.rawId = state.preparedId;
                }, Allocation::dispose);
            } catch (IllegalStateException expected) {
                threw = true;
            }
            require(threw == (mode >= 4), "exception contract");
            require(accepted == (mode == 0), "only current complete upload transfers");
            require(state.uploads == (mode == 1 ? 0 : 1), "revocation after create prevents upload");
            if (mode == 0) {
                require(
                    state.published != null && state.ledger.live.contains(state.rawId),
                    "success transfers live ID");
                require(state.ledger.deletes == 0, "success not prematurely deleted");
                int calls = state.ledger.getters;
                int drawId = state.rawId;
                require(
                    drawId > 0 && state.ledger.getters == calls,
                    "draw uses stored raw ID without allocating getter");
            } else {
                require(state.published == null, "failed or revoked allocation never published");
                require(state.ledger.live.isEmpty(), "failed or revoked local allocation disposed");
                require(state.ledger.deletes == 1, "local owner disposes once after callbacks unwind");
            }
            state.close();
            state.close();
            require(state.ledger.live.isEmpty(), "close releases transferred allocation");
            require(state.ledger.deletes == 1, "idempotent close never double deletes");
            require(state.ledger.allocations == 1, "every scenario has exactly one allocation");
        }
        State rejected = new State();
        rejected.current = false;
        require(
            !PreviewUpload.transfer(
                rejected.ledger::allocate,
                a -> { throw new AssertionError("upload"); },
                () -> rejected.current,
                a -> { throw new AssertionError("publish"); },
                Allocation::dispose),
            "initial rejection has no allocation");
        require(rejected.ledger.allocations == 0, "initial rejection does not call create");
        StringBuilder hex = new StringBuilder();
        for (byte value : hash.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS preview upload: " + assertions
                + " assertions; reentrant close, revoke, resurrecting getter model, exceptional cleanup, raw-ID transfer; native GL UNTESTED");
    }

    private static final class Ledger {

        final Set<Integer> live = new HashSet<>();
        int allocations, deletes, getters;

        Allocation allocate() {
            return new Allocation(this);
        }
    }

    private static final class Allocation {

        final Ledger ledger;
        int currentId = -1;

        Allocation(Ledger ledger) {
            this.ledger = ledger;
            id();
        }

        int id() {
            ledger.getters++;
            if (currentId == -1) {
                currentId = ++ledger.allocations;
                ledger.live.add(currentId);
            }
            return currentId;
        }

        void dispose() {
            require(currentId != -1 && ledger.live.remove(currentId), "dispose owns a live ID");
            ledger.deletes++;
            currentId = -1;
        }
    }

    private static final class State {

        final Ledger ledger = new Ledger();
        boolean current = true, failAcceptance;
        Allocation published;
        int rawId = -1, preparedId = -1, uploads;

        void close() {
            current = false;
            Allocation old = published;
            published = null;
            rawId = -1;
            if (old != null) old.dispose();
        }
    }

    private static void require(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
        hash.update(message.getBytes(StandardCharsets.UTF_8));
    }
}
