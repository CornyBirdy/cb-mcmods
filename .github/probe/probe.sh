#!/usr/bin/env bash
# Temporary: prints the 26.3 signatures Estate's block display borders need.
set -u
MC=""
for j in $(find ~/.gradle/caches/fabric-loom .gradle */*/.gradle -name "*.jar" 2>/dev/null | grep -iv sources); do
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/world/entity/Display.class"; then MC="$j"; break; fi
done
echo "MC=$MC"
for c in net.minecraft.network.protocol.game.ClientboundAddEntityPacket net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket \
  net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket net.minecraft.network.syncher.SynchedEntityData \
  'net.minecraft.world.entity.Display$BlockDisplay' net.minecraft.world.entity.Display net.minecraft.util.Brightness \
  'net.minecraft.network.protocol.game.ClientboundBundlePacket'; do
  echo "######## $c"; javap -p -cp "$MC" "$c" | grep -v 'lambda\$' | grep -vE "^  private static final net.minecraft.network.syncher.EntityDataAccessor" | head -80
done
echo "######## Entity id/data"; javap -p -cp "$MC" net.minecraft.world.entity.Entity | grep -E " getId\(|getEntityData|setGlowingTag|setId\(| getAddEntityPacket"
echo "######## EntityTypes"; javap -p -cp "$MC" net.minecraft.world.entity.EntityTypes | grep -E "BLOCK_DISPLAY|TEXT_DISPLAY"
