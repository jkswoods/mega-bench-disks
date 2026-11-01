package dev.megabench.disks;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/** Persisted once per newly generated section, so column and section callbacks cannot place twice. */
public final class MemoriteStamp implements Component<ChunkStore> {
    public static final BuilderCodec<MemoriteStamp> CODEC=BuilderCodec.builder(MemoriteStamp.class,MemoriteStamp::new).build();
    @Override public MemoriteStamp clone(){return new MemoriteStamp();}
}
