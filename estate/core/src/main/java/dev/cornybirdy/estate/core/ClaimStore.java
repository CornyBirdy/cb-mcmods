package dev.cornybirdy.estate.core;

import java.util.Collection;
import java.util.List;

/** Where claims, players and town zones are saved. Every call must be finished and durable when it returns. */
public interface ClaimStore {

    record Snapshot(List<Claim> claims, List<PlayerRecord> players, List<TownZone> towns, Integer cellSize) {
    }

    Snapshot loadAll();

    void saveClaim(Claim claim);

    void deleteClaim(int claimId);

    void savePlayer(PlayerRecord player);

    void saveTown(TownZone town);

    void deleteTown(int townId);

    void saveCellSize(int cellSize);

    void log(Action action);

    /** Nothing is saved; for tests. */
    ClaimStore MEMORY = new ClaimStore() {
        @Override
        public Snapshot loadAll() {
            return new Snapshot(List.of(), List.of(), List.of(), null);
        }

        @Override
        public void saveClaim(Claim claim) {
        }

        @Override
        public void deleteClaim(int claimId) {
        }

        @Override
        public void savePlayer(PlayerRecord player) {
        }

        @Override
        public void saveTown(TownZone town) {
        }

        @Override
        public void deleteTown(int townId) {
        }

        @Override
        public void saveCellSize(int cellSize) {
        }

        @Override
        public void log(Action action) {
        }
    };

    static <T> List<T> copy(Collection<T> values) {
        return List.copyOf(values);
    }
}
