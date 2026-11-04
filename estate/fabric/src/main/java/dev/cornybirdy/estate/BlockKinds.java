package dev.cornybirdy.estate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sorts blocks and items into what a player needs to be trusted with to use them. */
final class BlockKinds {

    enum Kind {
        /** Doors, buttons, beds, workstations: the "interact" permission. */
        ACCESS,
        /** Holds items: the "containers" permission. */
        CONTAINER,
        /** Does something else when right-clicked (repeaters, cake, berries, signs...): the "build" permission. */
        INTERACTIVE,
        /** Right-clicking it does nothing by itself. */
        PLAIN
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Estate");

    private static final ClassValue<Boolean> BLOCK_USABLE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return declares(type, BlockBehaviour.class, "useWithoutItem", "useItemOn");
        }
    };

    private static final ClassValue<Boolean> ITEM_USED_ON_BLOCKS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return declares(type, Item.class, "useOn");
        }
    };

    private final Set<Block> access;
    private final Set<Block> containers;
    private final Set<String> autoClaimIgnored;

    BlockKinds(List<String> accessBlocks, List<String> containerBlocks, List<String> ignoredBlocks) {
        this.access = resolve(accessBlocks);
        this.containers = resolve(containerBlocks);
        Set<String> ignored = new HashSet<>();
        for (Block block : resolve(ignoredBlocks)) {
            ignored.add(id(block));
        }
        this.autoClaimIgnored = ignored;
    }

    Kind classify(Level level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (access.contains(block)) {
            return Kind.ACCESS;
        }
        if (containers.contains(block)) {
            return Kind.CONTAINER;
        }
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        if (blockEntity instanceof Container || blockEntity instanceof MenuProvider) {
            return Kind.CONTAINER;
        }
        return BLOCK_USABLE.get(block.getClass()) ? Kind.INTERACTIVE : Kind.PLAIN;
    }

    /** Whether using this item on a block can change the world (tools, buckets, bone meal, spawn eggs...). */
    static boolean changesBlocks(Item item) {
        return item instanceof BlockItem || ITEM_USED_ON_BLOCKS.get(item.getClass());
    }

    /** Whether a placed block of this id counts towards auto-claim. */
    boolean countsForAutoClaim(String blockId) {
        return !autoClaimIgnored.contains(blockId);
    }

    static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    /** True if a class between {@code type} and {@code base} (exclusive) declares one of the methods. */
    private static boolean declares(Class<?> type, Class<?> base, String... names) {
        for (Class<?> c = type; c != null && c != base && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                for (String name : names) {
                    if (method.getName().equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Turns ids and {@code #tags} from the config into blocks. Unknown entries are logged and skipped. */
    private static Set<Block> resolve(List<String> entries) {
        Set<Block> blocks = new HashSet<>();
        for (String entry : entries) {
            if (entry.startsWith("#")) {
                Identifier tagId = Identifier.tryParse(entry.substring(1));
                if (tagId == null) {
                    LOGGER.warn("Ignoring '{}' in config/estate.json: not a valid tag", entry);
                    continue;
                }
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
                for (Block block : BuiltInRegistries.BLOCK) {
                    if (block.defaultBlockState().is(tag)) {
                        blocks.add(block);
                    }
                }
            } else {
                Identifier blockId = Identifier.tryParse(entry);
                if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
                    LOGGER.warn("Ignoring '{}' in config/estate.json: no such block", entry);
                    continue;
                }
                blocks.add(BuiltInRegistries.BLOCK.getValue(blockId));
            }
        }
        return blocks;
    }
}
