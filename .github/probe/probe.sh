#!/usr/bin/env bash
# Temporary: prints vanilla smithing menu signatures for Gatekeeper's netherite ban.
set -u
MC=""
for j in $(find ~/.gradle/caches/fabric-loom .gradle */*/.gradle -name "*.jar" 2>/dev/null | grep -iv sources); do
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/world/inventory/SmithingMenu.class"; then MC="$j"; break; fi
done
echo "MC=$MC"
for c in net.minecraft.world.inventory.SmithingMenu net.minecraft.world.inventory.ItemCombinerMenu net.minecraft.world.inventory.ResultSlot net.minecraft.world.entity.player.Inventory; do
  echo "######## $c"; javap -p -cp "$MC" "$c" | grep -v 'lambda\$'
done
for c in $(unzip -l "$MC" | awk '{print $4}' | grep -E "inventory/(SmithingMenu|ItemCombinerMenu)\\$" | sed 's/\.class$//; s#/#.#g'); do
  echo "######## $c"; javap -p -cp "$MC" "$c"
done
echo "######## SmithingMenu bytecode (createResult / mayPickup / onTake)"
javap -c -p -cp "$MC" net.minecraft.world.inventory.SmithingMenu | grep -E "^  [a-z].*\(|invoke" | head -150
