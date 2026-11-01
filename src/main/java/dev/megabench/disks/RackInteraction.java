package dev.megabench.disks;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.Message;
import dev.megabench.CompanionExtension;
import org.joml.Vector3i;
public final class RackInteraction extends SimpleBlockInteraction {
 public static final BuilderCodec<RackInteraction> CODEC=BuilderCodec.builder(RackInteraction.class,RackInteraction::new,SimpleBlockInteraction.CODEC).build();
 @Override protected void interactWithBlock(World w,CommandBuffer<EntityStore>b,InteractionType t,InteractionContext ctx,ItemStack held,Vector3i at,CooldownHandler cooldown){var ref=ctx.getEntity();var p=b.getComponent(ref,PlayerRef.getComponentType());if(p==null)return;w.execute(()->{if(!ref.isValid())return;var ledger=DisksPlugin.ledger(ref.getStore());var bench=RackNetwork.benchFromRack(new RackNetwork.Position(at.x(),at.y(),at.z()),q->DisksPlugin.block(w,q.x(),q.y(),q.z()),q->{var saved=ledger.racks.get(q.key());return saved==null||saved.owner.equals(p.getUuid());});if(bench!=null){CompanionExtension.openBench(ref.getStore(),ref,p,bench.x(),bench.y(),bench.z());return;}p.sendMessage(Message.raw("Connect up to 16 owned Drive Racks face to face with a Mega Bench, then open Storage or Disks."));});}
 @Override protected void simulateInteractWithBlock(InteractionType t,InteractionContext c,ItemStack held,World w,Vector3i at){}
}
