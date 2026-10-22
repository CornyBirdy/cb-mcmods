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


fapi_sig() { for c in "$@"; do echo "######## $c"; javap -p -cp "$CP" "$c" | grep -v 'lambda\$'; done; }
echo "================ FIND"
grep -iE "ender_?man|farm|silverfish|painting|minecart|/boat|chestboat|ContainerEntity|EntityTypes\.class|BaseContainerBlockEntity|TntBlock|MinecartTNT|Shulker|Hopper" /tmp/classes.txt | grep -v '\$' | head -60
for c in $(grep -iE "ender_?man[^/]*\$" /tmp/classes.txt | sed 's/\.class$//; s#/#.#g'); do echo "######## $c"; javap -p -cp "$CP" "$c" | grep -v 'lambda\$'; done
for c in $(grep -iE "/EnderMan\.class|/Enderman\.class" /tmp/classes.txt | sed 's/\.class$//; s#/#.#g'); do code "$(basename ${c//./\/})" "tick"; done
for c in $(grep -iE "ender_?man[^/]*Goal\.class" /tmp/classes.txt | sed 's/\.class$//; s#/#.#g'); do
  echo "######## CODE $c::tick"; javap -c -p -cp "$CP" "$c" | awk '/^  [^ ].*\(.*\)/ { inm = ($0 ~ / tick\(/); if (inm) print; next } inm && /invoke|getfield/ { sub(/^ +[0-9]+: /, "    "); print }'
done
for c in $(grep -iE "/Farm[A-Za-z]*Block\.class" /tmp/classes.txt | sed 's/\.class$//; s#/#.#g'); do
  echo "######## $c"; javap -p -cp "$CP" "$c" | grep -E "fallOn|turnToDirt|class "
  echo "######## CODE $c::fallOn"; javap -c -p -cp "$CP" "$c" | awk '/^  [^ ].*\(.*\)/ { inm = ($0 ~ / fallOn\(/); if (inm) print; next } inm && /invoke|getfield|instanceof/ { sub(/^ +[0-9]+: /, "    "); print }'
done
code Projectile mayInteract
code DispenserBlock dispenseFrom
sig Painting "hurtServer|class "
sig Level "isLoaded|hasChunkAt|getBlockEntity\(|getHeight\(|isInWorldBounds|getMinY|getMaxY|getChunk\("
sig Entity "getBoundingBox|getRootVehicle|isPassenger|getVehicle|getEyePosition|getYRot|getXRot|blockPosition|onGround|getName|getDisplayName"
sig LivingEntity "getLastHurtByMob|isAlive"
sig BlockStateBase " is\(|getBlock\(|isAir|hasBlockEntity|getMenuProvider|canBeReplaced|liquid|isSolid|getFluidState"
sig BlockBehaviour\\$BlockStateBase " is\(|getBlock\(|isAir|hasBlockEntity|getMenuProvider|canBeReplaced"
sig BuiltInRegistries "BLOCK;|ITEM;|ENTITY_TYPE;"
sig Registries " BLOCK;| ITEM;| DIMENSION;"
sig TagKey "create|location"
sig Identifier "parse|tryParse|fromNamespaceAndPath|getPath|getNamespace"
sig ServerCommonPacketListenerImpl " send\("
sig ServerGamePacketListenerImpl " send\("
sig BlockPlaceContext "getClickedPos|canPlace|replacingClickedOnBlock|getHorizontalDirection"
sig UseOnContext "getClickedPos|getClickedFace|getPlayer|getLevel|getItemInHand"
sig Heightmap\\$Types
sig GameProfile
sig Component " literal\(| empty\(| translatable\("
sig MutableComponent "append|withStyle"
sig SimpleMenuProvider
sig ChestMenu "<init>|ChestMenu\("
sig Slot "<init>|Slot\(|mayPlace|mayPickup|index"
sig DataComponents "CUSTOM_NAME|LORE|PROFILE|ENCHANTMENT_GLINT|TOOLTIP_DISPLAY|CUSTOM_DATA|DYED_COLOR|ITEM_NAME"
sig Item " useOn\(| use\("
sig Containers
sig EntityTypes "ARMOR_STAND|ITEM_FRAME"
sig AbstractMinecart "class "
sig AbstractBoat "class "
sig ContainerEntity "class |interface "
sig Silverfish "class "
fapi_sig net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents \
  'net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents$AllowDamage' \
  net.fabricmc.fabric.api.event.player.BlockEvents net.fabricmc.fabric.api.event.player.ItemEvents \
  net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents 'net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents$Before' 'net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents$After' \
  net.fabricmc.fabric.api.event.player.UseBlockCallback net.fabricmc.fabric.api.event.player.UseItemCallback \
  net.fabricmc.fabric.api.event.player.UseEntityCallback net.fabricmc.fabric.api.event.player.AttackEntityCallback \
  net.fabricmc.fabric.api.event.player.AttackBlockCallback net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents \
  'net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents$Unload' net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents \
  net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents 'net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Join' \
  net.fabricmc.fabric.api.permission.v1.PermissionPredicates net.fabricmc.fabric.api.permission.v1.PermissionContextOwner
for j in $(echo "$FAPI" | tr ':' '\n'); do unzip -l "$j" 2>/dev/null | awk '{print $4}' | grep -E "event/player/(BlockEvents|ItemEvents)\\$" ; done | sed 's/\.class$//; s#/#.#g' | sort -u | while read c; do fapi_sig "$c"; done
