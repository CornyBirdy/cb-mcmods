#!/usr/bin/env bash
# Temporary: prints vanilla class signatures so the Fabric port can be written without a local Minecraft jar.
set -u
echo "== jars"
find ~/.gradle/caches/fabric-loom .gradle fabric/.gradle -name "*.jar" 2>/dev/null | grep -iv sources | grep -i -E "minecraft|authlib" | head -30
MC=$(find ~/.gradle/caches/fabric-loom .gradle fabric/.gradle -name "*.jar" 2>/dev/null | grep -iv sources | grep -i -E "minecraft-(merged|common|server)" | head -1)
[ -z "$MC" ] && MC=$(find ~/.gradle/caches/fabric-loom .gradle fabric/.gradle -name "*.jar" 2>/dev/null | grep -iv sources | grep -i minecraft | xargs ls -S | head -1)
AUTH=$(find ~/.gradle/caches -name "authlib-*.jar" | grep -v sources | head -1)
echo "MC=$MC AUTH=$AUTH"
CP="$MC:$AUTH"
echo "== find"
unzip -l "$MC" | awk '{print $4}' | grep -E "Mannequin|/Avatar|ClickType|ContainerInput|NameAndId|LevelResource|/Interaction\.class|PermissionLevel|ResolvableProfile|TooltipDisplay|/ItemLore|NameToId|UserNameToId|ChestMenu|ProblemReporter|TagValue|ValueInput|ValueOutput" | grep -v '\$[0-9]'
p() { echo "######## $1"; javap -p -cp "$CP" "$1" 2>&1 | grep -v -E "lambda\\\$" | grep -E "${2:-.}"; }
p net.minecraft.world.entity.player.Inventory
p net.minecraft.world.entity.player.Player "dropEquipment|ExperienceReward|getGameProfile|experienceLevel|getInventory|containerMenu|openMenu|closeContainer|sendSystemMessage|displayClientMessage|giveExperience|inventoryMenu|isCreative|isSpectator|getName|nameAndId|getScoreboardName|giveExperiencePoints"
p net.minecraft.server.level.ServerPlayer "openMenu|level\(|sendSystemMessage|getGameProfile|closeContainer|connection|nextContainerCounter|serverLevel|teleport|getRespawn|server|permission|PermissionLevel|nameAndId|getTabListDisplayName|dropExperience|restoreFrom"
p net.minecraft.world.entity.LivingEntity "dropAllDeathLoot|dropExperience|getExperienceReward|BaseExperienceReward| die\(|isDeadOrDying|getCombatTracker|dropEquipment|shouldDropExperience|skipDropExperience|wasExperienceConsumed"
p net.minecraft.world.entity.Entity "getTags|addTag|removeTag|discard|getUUID|level\(|position\(|getYRot|setYRot|getX\(|getY\(|getZ\(|snapTo|moveTo|getStringUUID|isRemoved|setPos|Entity\(|EntityType|getDisplayName|getName\(|getCustomName|setCustomName|kill|remove\(|RemovalReason|setUUID|isAlive|load\(|saveWithoutId|getPersistentData|blockPosition|chunkPosition|setInvulnerable|setNoGravity|setSilent"
for c in $(unzip -l "$MC" | awk '{print $4}' | grep -E "Mannequin\.class|/Avatar\.class|decoration/Mannequin" | sed 's/\.class$//; s#/#.#g'); do p "$c"; echo "-- strings"; javap -c -p -constants -cp "$CP" "$c" | grep -o 'String [A-Za-z_]*' | sort -u | tr '\n' ' '; echo; done
p net.minecraft.world.entity.Interaction
javap -c -p -constants -cp "$CP" net.minecraft.world.entity.Interaction | grep -o 'String [A-Za-z_]*' | sort -u | tr '\n' ' '; echo
p 'net.minecraft.world.entity.Display' "String|static final|Billboard|setTransformation|setBillboard"
p 'net.minecraft.world.entity.Display$ItemDisplay'
p 'net.minecraft.world.entity.Display$TextDisplay' "set|String|Component"
p net.minecraft.world.entity.EntityType "MANNEQUIN|INTERACTION|ITEM_DISPLAY|TEXT_DISPLAY| create\(|spawn\(|loadEntityRecursive|getKey|ResourceKey"
p net.minecraft.world.entity.EntitySpawnReason
p net.minecraft.world.inventory.AbstractContainerMenu
p net.minecraft.world.inventory.ChestMenu
p net.minecraft.world.inventory.Slot
p net.minecraft.world.inventory.MenuType "GENERIC"
for c in $(unzip -l "$MC" | awk '{print $4}' | grep -E "inventory/(ClickType|ContainerInput)[^$]*\.class" | sed 's/\.class$//; s#/#.#g'); do p "$c"; done
p net.minecraft.world.SimpleContainer
p net.minecraft.world.Container
p net.minecraft.world.ContainerListener
p net.minecraft.world.SimpleMenuProvider
p net.minecraft.world.MenuProvider
p net.minecraft.world.item.ItemStack "CODEC|OPTIONAL|isSameItemSameComponents|public .* copy|isEmpty|getCount|setCount|set\(|get\(|ItemStack\(|getHoverName|getItem\(|matches|isStackable|getMaxStackSize|has\(|remove\(|split|grow|shrink|EMPTY|applyComponents|getComponents|is\("
p net.minecraft.core.component.DataComponents "CUSTOM_NAME|ITEM_NAME|LORE|CUSTOM_DATA|PROFILE|TOOLTIP_DISPLAY|HIDE|ENCHANTMENT_GLINT|ITEM_MODEL|ATTRIBUTE|MAX_STACK"
p net.minecraft.world.item.component.ItemLore
p net.minecraft.world.item.component.ResolvableProfile
p net.minecraft.world.item.component.TooltipDisplay
p net.minecraft.world.item.component.CustomData
p net.minecraft.world.item.Items "PLAYER_HEAD|GLASS_PANE|ARMOR_STAND|HOPPER|SKELETON_SKULL| BONE;|BARRIER|CHEST;|EXPERIENCE_BOTTLE|BOOK;|PAPER;|COMPASS|CLOCK"
p net.minecraft.network.chat.Component "static|literal|empty|translatable|withStyle|getString|append"
p net.minecraft.network.chat.MutableComponent
p net.minecraft.network.chat.Style "public"
p net.minecraft.network.chat.TextColor "public"
p net.minecraft.network.chat.ClickEvent
p 'net.minecraft.network.chat.ClickEvent$RunCommand'
p 'net.minecraft.network.chat.HoverEvent$ShowText'
p net.minecraft.ChatFormatting "static final|getColor|isColor|getByName"
p net.minecraft.nbt.NbtIo
p net.minecraft.nbt.CompoundTag "public"
p net.minecraft.nbt.ListTag "public"
p net.minecraft.nbt.NbtOps "INSTANCE"
p net.minecraft.nbt.NbtAccounter "static"
p net.minecraft.resources.RegistryOps "static|create"
p net.minecraft.core.HolderLookup\$Provider "createSerializationContext"
p net.minecraft.server.MinecraftServer "getCommands|createCommandSourceStack|getWorldPath|registryAccess|getLevel\(|getPlayerList|services|getProfileCache|nameToIdCache|getAllLevels|overworld|execute|tell|isSameThread|getTickCount|getProfilePermissions"
p net.minecraft.server.Services
for c in $(unzip -l "$MC" | awk '{print $4}' | grep -E "players/(NameAndId|UserNameToIdResolver|GameProfileCache|CachedUserNameToIdResolver)\.class" | sed 's/\.class$//; s#/#.#g'); do p "$c"; done
p net.minecraft.server.players.PlayerList "getPlayer|getPlayers|isOp|getPlayerByName"
p net.minecraft.commands.Commands "performPrefixedCommand|performCommand|literal|argument|LEVEL_|hasPermission"
p net.minecraft.commands.CommandSourceStack "public"
p net.minecraft.commands.arguments.EntityArgument "static"
p net.minecraft.server.permissions.PermissionLevel
p net.minecraft.world.level.storage.LevelResource "static"
p net.minecraft.server.level.ServerLevel "getEntity\(|dimension\(|getServer|getSharedSpawnPos|getRespawnData|getLevelData|isLoaded|hasChunkAt|getChunk|areEntitiesLoaded|isPositionEntityTicking|addFreshEntity|getMinY|getMaxY|getHeight\(|getBlockState|getRandomSequence"
p net.minecraft.world.level.Level "dimension\(|getBlockState|getMinY|getMaxY|getHeight|isLoaded|getGameRules|getServer|isClientSide|getFluidState"
p net.minecraft.world.level.block.state.BlockBehaviour\$BlockStateBase "isAir|blocksMotion|isSolid|canOcclude|isSolidRender|liquid|getFluidState|is\(|isRedstoneConductor|isCollisionShapeFullBlock"
p net.minecraft.world.level.material.FluidState "isEmpty|is\("
p net.minecraft.tags.FluidTags "LAVA"
p net.minecraft.world.damagesource.CombatTracker "getDeathMessage"
p com.mojang.authlib.GameProfile
p com.mojang.authlib.properties.Property
p com.mojang.authlib.properties.PropertyMap
p net.minecraft.resources.Identifier "static"
p net.minecraft.world.entity.Pose
p net.minecraft.world.level.levelgen.Heightmap\$Types
p net.minecraft.world.InteractionResult "static final|SUCCESS|PASS|FAIL|CONSUME"
p net.minecraft.world.entity.player.Player "public .*Inventory|getUUID|getPlainTextName|displayName"
p net.minecraft.server.level.ServerPlayer "public.*ServerLevel|getRespawnConfig"
p net.minecraft.world.level.Level "getGameRules|gameRules"
