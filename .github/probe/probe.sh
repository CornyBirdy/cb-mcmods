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

echo "================ REST"
sig2() { c="$1"; shift; echo "######## $c"; javap -p -cp "$CP" "$c" | grep -v 'lambda\$' | grep -E "$1|class |interface "; }
sig2 'net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase' " is\(|getBlock\(|isAir|hasBlockEntity|getMenuProvider|canBeReplaced"
sig2 net.minecraft.core.registries.BuiltInRegistries " BLOCK;| ITEM;| ENTITY_TYPE;"
sig2 net.minecraft.core.registries.Registries " BLOCK;| ITEM;| DIMENSION;| ENTITY_TYPE;"
sig2 net.minecraft.tags.TagKey "create|location"
sig2 net.minecraft.resources.Identifier "parse|tryParse|fromNamespaceAndPath|getPath|getNamespace"
sig2 net.minecraft.server.network.ServerCommonPacketListenerImpl " send\("
sig2 net.minecraft.world.item.context.BlockPlaceContext "getClickedPos|canPlace|replacingClickedOnBlock|getHorizontalDirection"
sig2 net.minecraft.world.item.context.UseOnContext "getClickedPos|getClickedFace|getPlayer|getLevel|getItemInHand"
sig2 'net.minecraft.world.level.levelgen.Heightmap$Types' "public static final"
sig2 com.mojang.authlib.GameProfile "name|id"
sig2 net.minecraft.network.chat.Component " literal\(| empty\(| translatable\("
sig2 net.minecraft.world.SimpleMenuProvider "<init>|SimpleMenuProvider\("
sig2 net.minecraft.world.inventory.ChestMenu "ChestMenu\("
sig2 net.minecraft.world.inventory.Slot "Slot\(|mayPlace|mayPickup| index"
sig2 net.minecraft.core.component.DataComponents "CUSTOM_NAME|LORE|PROFILE|ENCHANTMENT_GLINT|TOOLTIP_DISPLAY|CUSTOM_DATA|ITEM_NAME"
sig2 net.minecraft.world.item.Item " useOn\(| use\(|getDefaultInstance|builtInRegistryHolder"
sig2 net.minecraft.world.entity.player.Player "mayInteract|getGameProfile|nameAndId"
sig2 net.minecraft.world.entity.Entity "mayInteract"
sig2 net.minecraft.world.level.block.FarmlandBlock "turnToBaseBlock"
sig2 net.minecraft.server.level.ServerLevel "getChunkSource|hasChunk|isPositionEntityTicking|getRespawnData"
sig2 net.minecraft.world.entity.item.PrimedTnt "getOwner"
sig2 net.minecraft.world.entity.Mob "isLeashed|getLeashHolder"
fapi() { for c in "$@"; do echo "######## $c"; javap -p -cp "$CP" "$c" | grep -v 'lambda\$'; done; }
fapi net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents \
  'net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents$AllowDamage' \
  net.fabricmc.fabric.api.event.player.BlockEvents net.fabricmc.fabric.api.event.player.ItemEvents \
  'net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents$Before' 'net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents$After' \
  net.fabricmc.fabric.api.event.player.UseBlockCallback net.fabricmc.fabric.api.event.player.UseItemCallback \
  net.fabricmc.fabric.api.event.player.UseEntityCallback net.fabricmc.fabric.api.event.player.AttackEntityCallback \
  'net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents$Unload' \
  'net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Join' 'net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents$Disconnect' \
  net.fabricmc.fabric.api.permission.v1.PermissionPredicates net.fabricmc.fabric.api.permission.v1.PermissionContextOwner \
  net.fabricmc.fabric.api.entity.FakePlayer
for j in $(echo "$FAPI" | tr ':' '\n'); do unzip -l "$j" 2>/dev/null | awk '{print $4}' | grep -E 'event/player/(BlockEvents|ItemEvents)\$' ; done | sed 's/\.class$//; s#/#.#g' | sort -u | while read c; do fapi "$c"; done
for j in $(echo "$FAPI" | tr ':' '\n'); do unzip -l "$j" 2>/dev/null | awk '{print $4}' | grep -iE 'gametest.*\.class$' | grep -v '\$' | head -20; done | sort -u
