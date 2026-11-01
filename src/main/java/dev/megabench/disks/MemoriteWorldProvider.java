package dev.megabench.disks;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.worldgen.*;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider;
import com.hypixel.hytale.server.worldgen.HytaleWorldGenProvider;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.hypixel.hytale.math.vector.Transform;
import it.unimi.dsi.fastutil.longs.Long2FloatFunction;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;

/** Opt-in normal home-world generator; adds Memorite only during first generation, never on load. */
public final class MemoriteWorldProvider implements IWorldGenProvider {
    public static final BuilderCodec<MemoriteWorldProvider> CODEC=BuilderCodec.builder(MemoriteWorldProvider.class,MemoriteWorldProvider::new).build();
    static final Set<String> HOSTS=Set.of("Rock_Stone","Rock_Shale","Rock_Slate","Rock_Granite","Rock_Basalt","Rock_Sandstone");
    record Sample(int attemptedVeins,int placedVeins,int placedBlocks) {}
    @Override public IWorldGen getGenerator() throws WorldGenLoadException {return new Generator(new HytaleWorldGenProvider().getGenerator());}
    record Generator(IWorldGen base) implements IWorldGen {
        @Override public WorldGenTimingsCollector getTimings(){return base.getTimings();}
        @Override public Transform[] getSpawnPoints(int seed){return base.getSpawnPoints(seed);}
        @Override public ISpawnProvider getDefaultSpawnProvider(int seed){return base.getDefaultSpawnProvider(seed);}
        @Override public void shutdown(){base.shutdown();}
        @Override public CompletableFuture<GeneratedChunk> generate(int seed,long index,int x,int z,LongPredicate needed,Long2FloatFunction distance){return base.generate(seed,index,x,z,needed,distance).thenApply(chunk->{decorate(chunk.getBlockChunk(),seed,x,z);return chunk;});}
    }
    static Sample decorate(GeneratedBlockChunk chunk,int seed,int cx,int cz){
        var map=BlockType.getAssetMap();int ore=map.getIndex("MBD_MemoriteOre");if(ore==Integer.MIN_VALUE)throw new IllegalStateException("Memorite asset missing");
        var random=new Random(((long)seed*0x632be59bd9b4e019L)^((long)cx*0x9e3779b9L)^((long)cz*0x85ebca6bL)^0x4d454d4f52495445L);
        int attempts=0,veins=0,blocks=0;
        for(int band=0;band<10;band++){
            int depth=25+band*32;double mean=depth<60?.25:depth<120?1:2;int count=poisson(random,mean);
            for(int v=0;v<count;v++){
                attempts++;int x=2+random.nextInt(28),z=2+random.nextInt(28);int surface=chunk.getHeight(x,z);int y=surface-depth-random.nextInt(32);if(y<4)continue;int target=4+random.nextInt(5);
                var positions=new ArrayList<int[]>();for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++)if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)<=2)positions.add(new int[]{dx,dy,dz});Collections.shuffle(positions,random);
                var eligible=new ArrayList<int[]>();for(var p:positions){int px=x+p[0],py=y+p[1],pz=z+p[2];var host=map.getAsset(chunk.getBlock(px,py,pz));if(host==null||!HOSTS.contains(host.getId())||chunk.getHeight(px,pz)-py<25)continue;eligible.add(new int[]{px,py,pz});if(eligible.size()==target)break;}
                if(eligible.size()<4)continue;for(var p:eligible)chunk.setBlock(p[0],p[1],p[2],ore,0,0);veins++;blocks+=eligible.size();
            }
        }
        return new Sample(attempts,veins,blocks);
    }
    static int poisson(Random random,double mean){double limit=Math.exp(-mean),value=1;int count=0;do{count++;value*=random.nextDouble();}while(value>limit);return count-1;}
}
