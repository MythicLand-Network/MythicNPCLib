/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.npclib.api.skin;

import com.mojang.authlib.properties.Property;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.Optional;

public final class PlayerSkinService {
    public Optional<Skin> resolve(Player player) {
        if (!(player instanceof CraftPlayer craftPlayer)) return Optional.empty();
        return craftPlayer.getProfile().getProperties().get("textures").stream()
                .filter(property -> property.getValue() != null && !property.getValue().isBlank())
                .filter(property -> property.getSignature() != null && !property.getSignature().isBlank())
                .findFirst()
                .map(property -> new Skin(property.getValue(), property.getSignature()));
    }
}
