package dev.megabench.disks;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.*;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.megabench.CompanionExtension;

public final class DisksPlugin extends JavaPlugin {
    static DisksPlugin instance;static ResourceType<EntityStore,DiskLedger> ledgerType;
    static ComponentType<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore,MemoriteStamp> memoriteStampType;
    final CompanionExtension.Provider provider=StorageSession::new;
    public DisksPlugin(JavaPluginInit init){super(init);}
    @Override protected void setup(){instance=this;com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider.CODEC.register("MBD_Memorite",MemoriteWorldProvider.class,MemoriteWorldProvider.CODEC);ledgerType=getEntityStoreRegistry().registerResource(DiskLedger.class,"MegaBenchDisksLedgerV1",DiskLedger.CODEC);getEntityStoreRegistry().registerSystem(new ProtectRack());getEntityStoreRegistry().registerSystem(new RackVisuals());
        memoriteStampType=getChunkStoreRegistry().registerComponent(MemoriteStamp.class,"MBD_MemoriteFeatureV1",MemoriteStamp.CODEC);
        getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent.class,MemoriteFeatures::column);
        getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.universe.world.events.ChunkSectionPreLoadProcessEvent.class,MemoriteFeatures::section);getCodecRegistry(Interaction.CODEC).register("MBD_Open",RackInteraction.class,RackInteraction.CODEC);}
    @Override protected void start(){CompanionExtension.register(1,provider);getLogger().atInfo().log("Mega Bench: Disks companion API 1; player-owned connected racks, native inventory grids and crafting outputs/refunds.");}
    @Override protected void shutdown(){NativeStorage.shutdown();CompanionExtension.unregister(provider);}
    static DiskLedger ledger(Store<EntityStore> s){return s.getResource(ledgerType);}
    static String key(int x,int y,int z){return x+","+y+","+z;}
    static String block(World w,int x,int y,int z){var section=w.getChunkStore().getChunkSectionReferenceAtBlock(x,y,z);if(section==null||!section.isValid())return "";var blocks=w.getChunkStore().getStore().getComponent(section,BlockSection.getComponentType());if(blocks==null)return "";var b=BlockType.getAssetMap().getAsset(blocks.get(x&31,y&31,z&31));return baseId(b);}
    static String baseId(BlockType block){if(block==null)return "";String base=block.getDefaultStateKey();return base==null?block.getId():base;}
    static final class ProtectRack extends EntityEventSystem<EntityStore,BreakBlockEvent> {
        ProtectRack(){super(BreakBlockEvent.class);}
        @Override public Query<EntityStore> getQuery(){return Player.getComponentType();}
        @Override public void handle(int i,ArchetypeChunk<EntityStore> chunk,Store<EntityStore>s,CommandBuffer<EntityStore>b,BreakBlockEvent event){var p=event.getTargetBlock();var rack=ledger(s).racks.get(key(p.x(),p.y(),p.z()));if(rack!=null&&(!rack.empty()||rack.lease!=null)){event.setCancelled(true);var player=chunk.getComponent(i,com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());if(player!=null)player.sendMessage(Message.raw(rack.lease!=null?"Finish or cancel the native craft before breaking a connected Drive Rack.":"Eject every disk before breaking this Drive Rack."));}}
    }
}
