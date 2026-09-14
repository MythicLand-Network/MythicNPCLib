/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib.internal;

import cn.mythicland.npclib.api.state.NPCAnimation;
import cn.mythicland.npclib.api.state.NPCSlot;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * @author Jitse Boonstra
 */
interface NPCPacketHandler {

    void createPackets();

    void createPackets(Player player);

    void sendShowPackets(Player player);

    void sendHidePackets(Player player, boolean auto);

    void sendMetadataPacket(Player player);

    void sendEquipmentPacket(Player player, NPCSlot slot, boolean auto);

    void sendAnimationPacket(Player player, NPCAnimation animation);

    void sendHeadRotationPackets(Location location);

    default void sendEquipmentPackets(Player player) {
        for (NPCSlot slot : NPCSlot.values())
            sendEquipmentPacket(player, slot, true);
    }
}
