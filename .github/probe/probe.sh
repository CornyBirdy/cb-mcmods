#!/usr/bin/env bash
# Temporary: prints the 26.3 signatures Estate's claim tool needs.
set -u
MC=""
for j in $(find ~/.gradle/caches/fabric-loom .gradle */*/.gradle -name "*.jar" 2>/dev/null | grep -iv sources); do
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/world/entity/Display.class"; then MC="$j"; break; fi
done
echo "######## handlers"; javap -p -cp "$MC" net.minecraft.server.network.ServerGamePacketListenerImpl | grep -E "public void handle"
echo "######## serverbound packets"; unzip -l "$MC" | grep -o "net/minecraft/network/protocol/game/Serverbound[A-Za-z]*\.class" | sort -u
