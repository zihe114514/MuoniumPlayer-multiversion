package com.muoniumplayer.configskija.host.avatar;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Version-local client access. No NativeImage, texture or game profile may escape into a worker. */
public interface AvatarAccess {
    boolean isClientThread();

    <T> CompletableFuture<T> submit(Supplier<T> task);

    /** Called only on the client thread. Contains the UUID, never credentials or the authlib profile. */
    AvatarLoader.Profile profile();

    /** Copies only the face and hat pixels while the game's borrowed image is still live. */
    AvatarPixels local(AvatarLoader.Profile expected);

    /** Start on the client thread, await at most two seconds on a worker, then submit the returned read task. */
    CompletableFuture<Supplier<AvatarPixels>> resolve(AvatarLoader.Profile expected);
}
