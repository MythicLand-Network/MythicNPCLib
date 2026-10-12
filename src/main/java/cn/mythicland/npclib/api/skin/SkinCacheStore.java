package cn.mythicland.npclib.api.skin;

import java.util.Optional;

public interface SkinCacheStore {
    Optional<Skin> load(String name);

    void save(String name, Skin skin);
}
