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
strs() { echo "######## strings $1"; javap -c -p -constants -cp "$CP" "$1" | grep -o 'String [A-Za-z_]*' | sort -u | tr '\n' ' '; echo; }
p net.minecraft.world.entity.Entity "Tag|Invulnerable|isInvulnerable|getEntityData|getSelfAndPassengers|getBoundingBox|setGlowing|teleportTo|lookAt|setXRot"
strs net.minecraft.world.entity.Entity
p net.minecraft.world.entity.LivingEntity "setYBodyRot|setYHeadRot|yBodyRot|yHeadRot|setHealth"
strs 'net.minecraft.world.entity.Display'
strs 'net.minecraft.world.entity.Display$ItemDisplay'
strs 'net.minecraft.world.entity.Display$TextDisplay'
p 'net.minecraft.world.entity.Display$BillboardConstraints'
p net.minecraft.world.entity.EntityProcessor
p net.minecraft.world.entity.EntityType "public static final net.minecraft.world.entity.EntityType<net.minecraft.world.entity.(decoration.Mannequin|Interaction|Display)"
p net.minecraft.world.level.block.ColorCollection
p net.minecraft.world.item.DyeColor "BLACK|GRAY|LIGHT_GRAY"
p net.minecraft.world.level.Level "getEntity|getEntities|getEntitiesOfClass"
p net.minecraft.server.level.ServerLevel "getEntity|getEntities|getAllEntities|getEntitiesOfClass"
p net.minecraft.world.level.EntityGetter "getEntit"
p net.minecraft.core.DataComponentHolder
p net.minecraft.core.component.DataComponentGetter
p net.minecraft.SharedConstants "getCurrentVersion|DATA_VERSION|WORLD_VERSION"
p net.minecraft.WorldVersion
p net.minecraft.world.level.storage.DataVersion
p net.minecraft.server.MinecraftServer "getFixerUpper|fixer|DataFixer|isRunning|getServerDirectory|getPlayerList"
p net.minecraft.util.datafix.fixes.References "ITEM_STACK"
p net.minecraft.util.datafix.DataFixTypes "static|update"
p net.minecraft.nbt.NbtUtils "DataVersion|addCurrentDataVersion|addDataVersion|getDataVersion"
p net.minecraft.network.chat.ComponentSerialization "CODEC|static"
p net.minecraft.network.chat.HoverEvent "ShowText"
p net.minecraft.world.item.component.ResolvableProfile "static"
p net.minecraft.world.entity.player.Player "getAbilities|isCreative|drop\\(|addItem|getUUID"
p net.minecraft.world.entity.player.Inventory "add|placeItemBackInInventory"
p net.minecraft.core.registries.BuiltInRegistries "ITEM;"
p net.minecraft.core.Registry "getValue|get\\("
p net.minecraft.world.inventory.InventoryMenu "SLOT"
p net.minecraft.server.level.ServerPlayer "containerMenu|initMenu|sendContainer|connection|getLastDeathLocation|setLastDeathLocation|experience|totalExperience"
p net.minecraft.world.entity.player.Player "totalExperience|experienceProgress|experienceLevel|giveExperiencePoints|getXpNeededForNextLevel|getLastDeathLocation"
p net.minecraft.core.GlobalPos
p net.minecraft.world.level.storage.LevelData\$RespawnData
p net.minecraft.core.BlockPos "containing|above|below|getY|public static"
p net.minecraft.world.level.Level "getMinY|getMaxY|isInsideBuildHeight|getHeight\\("
p net.minecraft.world.level.LevelHeightAccessor
p com.mojang.math.Transformation
p net.minecraft.world.item.ItemDisplayContext "static final"
p net.minecraft.world.damagesource.DamageSource "getLocalizedDeathMessage"
p net.minecraft.world.level.gamerules.GameRules "KEEP_INVENTORY|get\\("
p net.minecraft.world.inventory.ClickAction
p net.minecraft.server.players.PlayerList "broadcastSystemMessage"
p net.minecraft.world.InteractionHand
p net.minecraft.world.Clearable
p net.minecraft.world.level.storage.TagValueOutput "static|public"
p net.minecraft.util.ProblemReporter "static|DISCARDING"
unzip -l "$MC" | awk '{print $4}' | grep -E "gamerules?/GameRules|/EntityProcessor|/ColorCollection|DataVersion" | head
