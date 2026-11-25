package com.muoniumplayer.configskija.host.avatar;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** 26.1.2: declarations and pixel behavior verified separately against both actual loader classpaths. */
public final class AvatarSkinAccess implements AvatarAccess {
    private final Minecraft mc;

    public AvatarSkinAccess() {
        this(Minecraft.getInstance());
    }

    public AvatarSkinAccess(Minecraft mc) {
        this.mc = java.util.Objects.requireNonNull(mc);
    }

    public boolean isClientThread() {
        return mc.isSameThread();
    }

    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        return mc.submit(task);
    }

    private void client() {
        if (!mc.isSameThread())
            throw new IllegalStateException("Skin access requires the client thread");
    }

    public AvatarLoader.Profile profile() {
        client();
        var id = mc.getUser().getProfileId();
        return id == null ? null : new AvatarLoader.Profile(id);
    }

    private boolean matches(AvatarLoader.Profile expected) {
        return expected != null && expected.equals(profile());
    }

    public AvatarPixels local(AvatarLoader.Profile expected) {
        client();
        if (!matches(expected)) return null;
        var player = mc.player;
        if (player == null) return null;
        return extract(expected, player.getSkin().body().texturePath());
    }

    public CompletableFuture<Supplier<AvatarPixels>> resolve(AvatarLoader.Profile expected) {
        client();
        if (!matches(expected)) return CompletableFuture.completedFuture(null);
        return mc.getSkinManager()
                .get(mc.getGameProfile())
                .thenApply(
                        optional -> {
                            var skin = optional.orElse(null);
                            if (skin == null) return null;
                            var path = skin.body().texturePath();
                            return () -> extract(expected, path);
                        });
    }

    private AvatarPixels extract(AvatarLoader.Profile expected, Identifier path) {
        client();
        if (!matches(expected)) return null;
        return AvatarTextureAccess.copy(mc.getTextureManager().getTexture(path));
    }
}
