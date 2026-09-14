package cn.mythicland.npclib.nms.v1_8_R3.packets;

import cn.mythicland.npclib.api.state.NPCAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayOutAnimation;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class PacketPlayOutAnimationWrapper {
    private static final VarHandle ENTITY_ID;
    private static final VarHandle ANIMATION_ID;

    static {
        try {
            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(PacketPlayOutAnimation.class, MethodHandles.lookup());
            ENTITY_ID = lookup.findVarHandle(PacketPlayOutAnimation.class, "a", int.class);
            ANIMATION_ID = lookup.findVarHandle(PacketPlayOutAnimation.class, "b", int.class);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    public PacketPlayOutAnimation create(NPCAnimation npcAnimation, int entityId) {
        PacketPlayOutAnimation packetPlayOutAnimation = new PacketPlayOutAnimation();
        ENTITY_ID.set(packetPlayOutAnimation, entityId);
        ANIMATION_ID.set(packetPlayOutAnimation, npcAnimation.getId());
        return packetPlayOutAnimation;
    }

}
