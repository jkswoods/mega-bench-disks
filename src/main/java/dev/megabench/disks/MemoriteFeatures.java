package dev.megabench.disks;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.LegacyHeightmap;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.events.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.*;

/** Adds a small feature to first-generation home terrain without replacing its native generator. */
final class MemoriteFeatures {
    record Sample(int attemptedVeins,int placedVeins,int placedBlocks) {}
    static boolean enabled(World world){return world.getName().equals("default")&&!(world.getWorldConfig().getWorldGenProvider() instanceof MemoriteWorldProvider);}
    static void column(ChunkPreLoadProcessEvent event){
        if(!event.isNewlyGenerated()||!enabled(event.getWorld()))return;
        var holder=event.getHolder();var column=holder.getComponent(ChunkColumn.getComponentType());var heights=resolvedHeights(holder);
        if(column==null||heights==null||column.getSectionHolders()==null)return;
        for(var section:column.getSectionHolders()){
            if(section==null)continue;var at=section.getComponent(ChunkSection.getComponentType());var blocks=section.getComponent(BlockSection.getComponentType());
            if(at!=null&&blocks!=null)populate(section,heights,(int)event.getWorld().getWorldConfig().getSeed(),true);
        }
    }
    static HeightmapColumn resolvedHeights(Holder<ChunkStore> holder){
        var nativeMap=holder.getComponent(HeightmapColumn.getComponentType());
        if(nativeMap!=null&&nativeMap.getHeight(16,16)!=HeightmapColumn.NO_HEIGHT)return nativeMap;
        // The pinned ordinary Hytale generator supplies all ten section holders before the core heightmap is resolved.
        // Compute an independent native opacity heightmap for this feature; leave the world's own map untouched.
        var column=holder.getComponent(ChunkColumn.getComponentType());if(column==null||column.getSectionHolders()==null)return nativeMap;
        var resolved=new HeightmapColumn(new LegacyHeightmap());
        for(var section:column.getSectionHolders())if(section!=null){var at=section.getComponent(ChunkSection.getComponentType());var blocks=section.getComponent(BlockSection.getComponentType());if(at!=null&&blocks!=null)resolved.onSectionGenerated(at.getY(),blocks);}
        return resolved;
    }
    static void section(ChunkSectionPreLoadProcessEvent event){
        if(!event.isNewlyGenerated()||!enabled(event.getWorld()))return;
        var at=event.getHolder().getComponent(ChunkSection.getComponentType());var blocks=event.getHolder().getComponent(BlockSection.getComponentType());if(at==null||blocks==null)return;
        // During first column preload its resident reference is absent; column() already decorates its holders.
        // Later cubic sections use the loaded native heightmap and their own deterministic seed.
        var store=event.getWorld().getChunkStore();var column=store.getChunkReference(ChunkUtil.indexChunk(at.getX(),at.getZ()));
        var heights=column==null||!column.isValid()?null:store.getStore().getComponentConcurrent(column,HeightmapColumn.getComponentType());
        if(heights!=null)populate(event.getHolder(),heights,(int)event.getWorld().getWorldConfig().getSeed(),true);
    }
    static Sample populate(Holder<ChunkStore> holder,HeightmapColumn heights,int seed,boolean newlyGenerated){
        if(!newlyGenerated||holder.getComponent(DisksPlugin.memoriteStampType)!=null)return new Sample(0,0,0);
        var at=holder.getComponent(ChunkSection.getComponentType());var blocks=holder.getComponent(BlockSection.getComponentType());
        if(at==null||blocks==null||heights.getHeight(16,16)==HeightmapColumn.NO_HEIGHT)return new Sample(0,0,0);
        var result=decorate(blocks,heights,seed,at.getX(),at.getY(),at.getZ());
        holder.putComponent(DisksPlugin.memoriteStampType,new MemoriteStamp());return result;
    }
    static Sample decorate(BlockSection blocks,HeightmapColumn heights,int seed,int cx,int sy,int cz){
        var map=BlockType.getAssetMap();int ore=map.getIndex("MBD_MemoriteOre");if(ore==Integer.MIN_VALUE)throw new IllegalStateException("Memorite asset missing");
        var random=new Random(((long)seed*0x632be59bd9b4e019L)^((long)cx*0x9e3779b9L)^((long)cz*0x85ebca6bL)^((long)sy*0x94d049bb133111ebL)^0x4d454d4f52495445L);
        int surface=heights.getHeight(16,16);if(surface==HeightmapColumn.NO_HEIGHT)return new Sample(0,0,0);
        int depth=surface-(sy*32+16),attempts=MemoriteWorldProvider.poisson(random,depth<60?.25:depth<120?1:2),veins=0,placed=0;
        for(int i=0;i<attempts;i++){
            int x=2+random.nextInt(28),y=2+random.nextInt(28),z=2+random.nextInt(28),target=4+random.nextInt(5);var positions=new ArrayList<int[]>();
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++)if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)<=2)positions.add(new int[]{x+dx,y+dy,z+dz});Collections.shuffle(positions,random);
            var eligible=new ArrayList<int[]>();for(var p:positions){int top=heights.getHeight(p[0],p[2]);if(top==HeightmapColumn.NO_HEIGHT||top-(sy*32+p[1])<25)continue;var host=map.getAsset(blocks.get(p[0],p[1],p[2]));if(host==null||!MemoriteWorldProvider.HOSTS.contains(host.getId()))continue;eligible.add(p);if(eligible.size()==target)break;}
            if(eligible.size()<4)continue;for(var p:eligible)blocks.set(p[0],p[1],p[2],ore,0,0);veins++;placed+=eligible.size();
        }
        return new Sample(attempts,veins,placed);
    }
}
