#!/usr/bin/env bash
# Temporary: prints vanilla smithing menu signatures for Gatekeeper's netherite ban.
set -u
MC=$(find ~/.gradle/caches/fabric-loom .gradle */*/.gradle -name "*.jar" 2>/dev/null | grep -iv sources | grep -i -E "minecraft-(merged|common|server)" | head -1)
[ -z "$MC" ] && MC=$(find ~/.gradle/caches/fabric-loom .gradle */*/.gradle -name "*.jar" 2>/dev/null | grep -iv sources | grep -i minecraft | xargs ls -S | head -1)
echo "MC=$MC"
for c in net.minecraft.world.inventory.SmithingMenu net.minecraft.world.inventory.ItemCombinerMenu net.minecraft.world.inventory.ResultSlot; do
  echo "######## $c"; javap -p -cp "$MC" "$c" | grep -v 'lambda\$'
done
unzip -l "$MC" | awk '{print $4}' | grep -E "inventory/(SmithingMenu|ItemCombinerMenu)" 
for c in $(unzip -l "$MC" | awk '{print $4}' | grep -E "inventory/(SmithingMenu|ItemCombinerMenu)\\$" | sed 's/\.class$//; s#/#.#g'); do
  echo "######## $c"; javap -p -cp "$MC" "$c"
done
