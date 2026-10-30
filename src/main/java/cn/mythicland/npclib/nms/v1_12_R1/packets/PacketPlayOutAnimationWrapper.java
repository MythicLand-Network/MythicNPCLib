package cn.mythicland.npclib.nms.v1_12_R1.packets;

import cn.mythicland.npclib.api.state.NPCAnimation;
import cn.mythicland.framework.bukkit.reflect.Reflection;
import net.minecraft.server.v1_12_R1.PacketPlayOutAnimation;

public class PacketPlayOutAnimationWrapper {

    public PacketPlayOutAnimation create(NPCAnimation npcAnimation, int entityId) {
        PacketPlayOutAnimation packetPlayOutAnimation = new PacketPlayOutAnimation();

        Reflection.getField(packetPlayOutAnimation.getClass(), "a", int.class)
                .set(packetPlayOutAnimation, entityId);
        Reflection.getField(packetPlayOutAnimation.getClass(), "b", int.class)
                .set(packetPlayOutAnimation, npcAnimation.getId());

        return packetPlayOutAnimation;
    }

}
