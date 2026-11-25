package com.muoniumplayer.configskija.host.avatar;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** 1.20.1 recode: declarations and pixel behavior verified separately against both actual loader classpaths. */
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
        // Original lane also declines HttpTexture: no readable public pixel buffer.
        return null;
    }

    public CompletableFuture<Supplier<AvatarPixels>> resolve(AvatarLoader.Profile expected) {
        client();
        if (!matches(expected)) return CompletableFuture.completedFuture(null);
        return CompletableFuture.completedFuture(null);
    }
}
