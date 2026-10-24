#!/usr/bin/env bash
# Temporary: prints the 26.3 signatures Estate's claim tool needs.
set -u
MC=""
for j in $(find ~/.gradle/caches/fabric-loom .gradle */*/.gradle -name "*.jar" 2>/dev/null | grep -iv sources); do
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/world/entity/Display.class"; then MC="$j"; break; fi
done
echo "MC=$MC"
echo "######## swing classes"; unzip -l "$MC" | grep -i "swing\|Animate" | head
echo "######## listener"; javap -p -cp "$MC" net.minecraft.server.network.ServerGamePacketListenerImpl | grep -iE "animate|swing|ServerPlayer player"
echo "######## Tool"; javap -p -cp "$MC" net.minecraft.world.item.component.Tool | head -20
echo "######## components"; javap -p -cp "$MC" net.minecraft.core.component.DataComponents | grep -E " (TOOL|MAX_DAMAGE|DAMAGE|ATTRIBUTE_MODIFIERS|UNBREAKABLE|WEAPON);"
echo "######## Blocks"; javap -p -cp "$MC" net.minecraft.world.level.block.Blocks | grep -E "STAINED_GLASS"
echo "######## ColorCollection"; javap -p -cp "$MC" net.minecraft.world.level.block.ColorCollection | head -40
echo "######## Block.byItem"; javap -p -cp "$MC" net.minecraft.world.level.block.Block | grep -E "byItem"
echo "######## ItemStack remove"; javap -p -cp "$MC" net.minecraft.world.item.ItemStack | grep -E " remove\(| isDamageableItem"
