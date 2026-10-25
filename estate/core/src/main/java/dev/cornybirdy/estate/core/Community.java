package dev.cornybirdy.estate.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A group of players. Every member counts as an owner of the claims assigned to the community, and members' claims
 * don't get buffers or block each other. Only {@link ClaimManager} changes communities.
 */
public final class Community {

    private final int id;
    private String name;
    private UUID owner;
    private final Set<UUID> members = new LinkedHashSet<>();

    public Community(int id, String name, UUID owner) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        members.add(owner);
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** The player who made it. Only they can invite, remove members, rename or disband. */
    public UUID owner() {
        return owner;
    }

    /** Everyone in it, the owner included. */
    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public boolean isMember(UUID player) {
        return members.contains(player);
    }

    void setName(String name) {
        this.name = name;
    }

    void add(UUID player) {
        members.add(player);
    }

    void remove(UUID player) {
        members.remove(player);
    }

    /** Used by storage when loading. */
    public static Community load(int id, String name, UUID owner, Set<UUID> members) {
        Community community = new Community(id, name, owner);
        community.members.addAll(members);
        return community;
    }

    @Override
    public String toString() {
        return "Community#" + id + "(" + name + ", " + members.size() + " members)";
    }
}
