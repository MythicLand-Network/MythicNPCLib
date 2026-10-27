package cn.mythicland.npclib.api;

import cn.mythicland.npclib.api.skin.Skin;
import org.bukkit.entity.Villager;

import java.util.List;
import java.util.Objects;

public final class PacketNpcOptions {
    private Skin skin;
    private List<String> text = List.of();
    private boolean autoShow;
    private Villager.Profession profession;

    public void skin(Skin skin) { this.skin = skin; }

    public void text(List<String> text) { this.text = List.copyOf(Objects.requireNonNull(text, "text")); }

    public void autoShow(boolean autoShow) { this.autoShow = autoShow; }

    public void villager(Villager.Profession profession) {
        this.profession = Objects.requireNonNull(profession, "profession");
    }

    public Skin skin() { return this.skin; }

    public List<String> text() { return this.text; }

    public boolean autoShow() { return this.autoShow; }

    public Villager.Profession profession() { return this.profession; }
}
