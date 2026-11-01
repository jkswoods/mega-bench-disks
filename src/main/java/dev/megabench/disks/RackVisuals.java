package dev.megabench.disks;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;

/** Loaded-only physical bay/fill appearance. Ledger contents remain the sole storage authority. */
final class RackVisuals extends TickingSystem<EntityStore> {
    record Appearance(int mask,int band,String state) {}
    static Appearance appearance(DiskLedger ledger,DiskLedger.Rack rack){
        int mask=0;for(int i=0;i<8;i++)if(rack.bays[i]!=null)mask|=1<<i;
        int used=ledger.used(rack),capacity=ledger.capacity(rack);
        int band=used==0||capacity==0?0:used>=capacity?5:1+(int)((long)used*4/capacity);
        return new Appearance(mask,band,mask==0?"default":String.format(Locale.ROOT,"B%02X_F%d",mask,band));
    }
    @Override public void tick(float delta,int index,Store<EntityStore> store){
        var world=store.getExternalData().getWorld();if(world.getTick()%10!=0)return;
        var ledger=DisksPlugin.ledger(store);var chunks=world.getChunkStore();var base=BlockType.getAssetMap().getAsset("MBD_DriveRack");if(base==null)return;
        for(var entry:ledger.racks.entrySet()){
            var parts=entry.getKey().split(",");if(parts.length!=3)continue;int x,y,z;try{x=Integer.parseInt(parts[0]);y=Integer.parseInt(parts[1]);z=Integer.parseInt(parts[2]);}catch(NumberFormatException ex){continue;}
            var section=chunks.getChunkSectionReferenceAtBlock(x,y,z);if(section==null||!section.isValid())continue;
            var blocks=chunks.getStore().getComponent(section,BlockSection.getComponentType());if(blocks==null)continue;
            int currentId=blocks.get(x&31,y&31,z&31);var current=BlockType.getAssetMap().getAsset(currentId);if(!"MBD_DriveRack".equals(DisksPlugin.baseId(current)))continue;
            var appearance=appearance(ledger,entry.getValue());var target=appearance.mask==0?base:base.getBlockForState(appearance.state);if(target==null||target.getId().equals(current.getId()))continue;
            // Same pinned native state-change path and flags; preserve rotation, publish packets and mark the block for saving.
            BlockOperations.setBlock(chunks,section,x,y,z,BlockType.getAssetMap().getIndex(target.getId()),target,blocks.getRotationIndex(x&31,y&31,z&31),0,198);
        }
    }
}
