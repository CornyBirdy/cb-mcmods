#!/usr/bin/env bash
# Temporary: prints vanilla and Fabric API signatures that Estate's protection hooks need.
set -u
MC=""
for j in $(find ~/.gradle/caches/fabric-loom .gradle */*/.gradle -name "*.jar" 2>/dev/null | grep -iv sources); do
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/world/level/block/piston/PistonBaseBlock.class"; then MC="$j"; break; fi
done
echo "MC=$MC"
unzip -l "$MC" | awk '{print $4}' | grep '\.class$' > /tmp/classes.txt
FAPI=$(find ~/.gradle/caches/modules-2 -path "*net.fabricmc.fabric-api*" -name "*.jar" | grep -v sources | tr '\n' ':')
CP="$MC:$FAPI"

fq() { grep -E "(^|/)$1\.class$" /tmp/classes.txt | sed 's/\.class$//; s#/#.#g'; }
sig() { # sig SimpleName [grep regex]
  for c in $(fq "$1"); do
    echo "######## $c"
    if [ $# -ge 2 ]; then javap -p -cp "$CP" "$c" | grep -v 'lambda\$' | grep -E "$2|class |interface "; else javap -p -cp "$CP" "$c" | grep -v 'lambda\$'; fi
  done
}
code() { # code SimpleName methodName : invokes inside that method
  for c in $(fq "$1"); do
    echo "######## CODE $c::$2"
    javap -c -p -cp "$CP" "$c" | awk -v m="$2" '
      /^  [^ ].*\(.*\)/ { inm = ($0 ~ (" " m "\\(")); if (inm) print; next }
      inm && /invoke|getfield|putfield|instanceof|getstatic/ { sub(/^ +[0-9]+: /, "    "); print }'
  done
}

echo "================ CLASS LOCATIONS"
for n in EnderMan BreakDoorGoal DoorInteractGoal Rabbit Silverfish WitherBoss Ravager ServerExplosion Explosion FlowingFluid \
  PistonBaseBlock PistonStructureResolver FireBlock BaseFireBlock DispenserBlock Projectile FarmBlock HangingEntity BlockAttachedEntity \
  ItemFrame VehicleEntity ArmorStand TamableAnimal Enemy DustParticleOptions ClientboundSetActionBarTextPacket UserBanList \
  UserBanListEntry BanListEntry NameAndId BedBlock DoorBlock ButtonBlock BucketItem PlaceOnWaterBlockItem LecternBlockEntity \
  Container MenuProvider BlockItem ChestMenu MenuType Heightmap LeashFenceKnotEntity AbstractVillager Villager Animal \
  ServerPlayerGameMode LivingEntity DamageSource FallingBlockEntity PrimedTnt EndCrystal Fox SnowGolem Sheep Mob Monster \
  ParticleTypes ParticleOptions LevelResource TurtleEggBlock SimpleContainer ContainerInput PermissionLevel Permissions \
  BlockHitResult HitResult ClipContext ArmorStandItem SignBlock FlowerPotBlock; do
  echo "$n -> $(fq "$n" | tr '\n' ' ')"
done

echo "================ FABRIC API EVENTS"
for j in $(echo "$FAPI" | tr ':' '\n'); do unzip -l "$j" 2>/dev/null | awk '{print $4}' | grep -E "^net/fabricmc/fabric/api/.*(event|permission|Permission|entity/FakePlayer).*\.class$" | grep -v '\$'; done | sort -u

echo "================ SIGNATURES"
sig ServerLevel "sendParticles|mayInteract|destroyBlock|getPlayerByUUID|dimension\(|explode"
sig Level "mayInteract|removeBlock\(|destroyBlock\(|setBlock\(|getGameRules|isClientSide|dimension\(|explode\("
sig LevelAccessor "setBlock|getLevel"
sig Entity " pick\(|level\(\)|blockPosition|position\(\)|getUUID|hurtServer|getType\(\)|entityTags|isSpectator|getX\(\)|getBlockY"
sig Player "displayClientMessage|sendOverlay|isSecondaryUseActive|blockInteractionRange|isCreative|getAbilities|mayBuild|isSpectator"
sig ServerPlayer "sendSystemMessage|checkPermission|nameAndId|getGameProfile|connection|displayClientMessage|sendOverlay|openMenu|closeContainer|level\(\)|serverLevel|getCamera|getRespawn|isChangingDimension"
sig CommandSourceStack "checkPermission|getPlayer|sendSystemMessage|sendSuccess|sendFailure|getPosition|getLevel|getTextName|getEntity"
sig ServerExplosion
sig Explosion
code ServerExplosion explode
code ServerExplosion interactWithBlocks
sig FlowingFluid "spreadTo|canSpreadTo|canHoldFluid|canPassThrough|canMaybePassThrough|spread\("
code FlowingFluid spread
sig PistonBaseBlock "moveBlocks|triggerEvent|checkIfExtend|isPushable"
sig PistonStructureResolver
sig FireBlock "tick|checkBurnOut|tryCatchFire|getIgniteOdds|canBurn|onPlace"
code FireBlock tick
code FireBlock checkBurnOut
sig DispenserBlock "dispenseFrom|FACING|tick"
sig Projectile "mayInteract|getOwner|onHitBlock|mayBreak"
sig FarmBlock "fallOn|turnToDirt"
code FarmBlock fallOn
sig BlockAttachedEntity "hurtServer|survives|dropItem"
sig HangingEntity "hurtServer"
sig ItemFrame "hurtServer|interact"
sig VehicleEntity "hurtServer|shouldSourceDestroy"
sig ArmorStand "hurtServer|interactAt|interact"
sig LivingEntity " hurtServer\(|isInvulnerableTo|actuallyHurt"
sig DamageSource "getEntity|getDirectEntity|is\(|getSourcePosition|type\("
sig TamableAnimal "isOwnedBy|getOwner|isTame"
sig OwnableEntity
sig DustParticleOptions
sig ParticleTypes "DUST|END_ROD|HAPPY|FLAME|ELECTRIC|WAX|COMPOSTER"
sig ClientboundSetActionBarTextPacket
sig UserBanList
sig UserBanListEntry
sig BanListEntry "getExpires|getReason"
sig StoredUserList "get\(|isEmpty|getEntries|contains"
sig PlayerList "getBans|getPlayers|getPlayerByName|getPlayer\("
sig NameAndId
sig EnderMan "Goal|carried|registerGoals"
for c in $(grep -E "EnderMan\\$" /tmp/classes.txt | sed 's/\.class$//; s#/#.#g'); do echo "######## $c"; javap -p -cp "$CP" "$c" | grep -v 'lambda\$'; done
code 'EnderMan\$EndermanTakeBlockGoal' tick
code 'EnderMan\$EndermanLeaveBlockGoal' tick
sig BreakDoorGoal
sig DoorInteractGoal "doorPos|mob|canUse"
for c in $(grep -E "/Rabbit\\$|/Silverfish\\$|/Fox\\$" /tmp/classes.txt | sed 's/\.class$//; s#/#.#g'); do echo "######## $c"; javap -p -cp "$CP" "$c" | grep -v 'lambda\$'; done
sig WitherBoss "customServerAiStep|destroyBlocksTick"
code WitherBoss customServerAiStep
sig Ravager "aiStep"
code Ravager aiStep
sig BucketItem "use\(|emptyContents|playEmptySound"
code BucketItem use
sig BlockItem "place\(|useOn|getPlacementState|updatePlacementContext"
sig Block "useWithoutItem|useItemOn|fallOn|stepOn"
sig BlockBehaviour " useWithoutItem\(| useItemOn\("
sig ChestMenu "<init>|clicked|quickMoveStack|stillValid|getContainer"
sig AbstractContainerMenu " clicked\(|doClick|canDragTo|sendAllDataToRemote|broadcastChanges|removed\("
sig MenuType "GENERIC_9x"
sig ResolvableProfile "create"
sig Heightmap "MOTION|WORLD_SURFACE|OCEAN"
sig ChunkPos "<init>|x\(\)|z\(\)|asLong|pack|getMinBlock|minBlock"
sig BlockPos "asLong|of\(|getFromLong|containing|relative"
sig MinecraftServer "getWorldPath|getPlayerList|overworld|getLevel\(|services|isUnderSpawnProtection|getTickCount|execute\(|isSameThread"
sig Services "nameToIdCache|profileResolver"
sig UserNameToIdResolver "get|add"
sig GameProfile
sig Items "PLAYER_HEAD|LIME_DYE|STAINED_GLASS|GRAY_DYE|ENDER_EYE|BARRIER|PISTON"
sig ItemStack "<init>|set\(|CODEC"
sig PermissionLevel
sig ContainerInput
code ServerPlayerGameMode useItemOn
code ServerPlayerGameMode destroyBlock
