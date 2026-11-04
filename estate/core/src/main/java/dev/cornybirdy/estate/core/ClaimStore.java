package dev.cornybirdy.estate.core;

import java.util.Collection;
import java.util.List;

/** Where claims, players and town zones are saved. Every call must be finished and durable when it returns. */
public interface ClaimStore {

    record Snapshot(List<Claim> claims, List<PlayerRecord> players, List<TownZone> towns, List<Community> communities) {
        public Snapshot(List<Claim> claims, List<PlayerRecord> players, List<TownZone> towns) {
            this(claims, players, towns, List.of());
        }
    }

    Snapshot loadAll();

    void saveClaim(Claim claim);

    void deleteClaim(int claimId);

    void savePlayer(PlayerRecord player);

    void saveCommunity(Community community);

    void deleteCommunity(int communityId);

    void saveTown(TownZone town);

    void deleteTown(int townId);

    void log(Action action);

    /** Nothing is saved; for tests. */
    ClaimStore MEMORY = new ClaimStore() {
        @Override
        public Snapshot loadAll() {
            return new Snapshot(List.of(), List.of(), List.of());
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
        public void saveCommunity(Community community) {
        }

        @Override
        public void deleteCommunity(int communityId) {
        }

        @Override
        public void saveTown(TownZone town) {
        }

        @Override
        public void deleteTown(int townId) {
        }

        @Override
        public void log(Action action) {
        }
    };

    static <T> List<T> copy(Collection<T> values) {
        return List.copyOf(values);
    }
}
