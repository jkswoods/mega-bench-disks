package dev.megabench.disks;

import com.google.gson.*;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.*;
import java.util.*;

/** World-owned contents; cartridge metadata identifies a record, never contains a second copy of items. */
public final class DiskLedger implements Resource<EntityStore> {
    static final Gson JSON=new Gson();
    public static final BuilderCodec<DiskLedger> CODEC=BuilderCodec.builder(DiskLedger.class,DiskLedger::new)
        .append(new KeyedCodec<>("DiskLedgerV1",Codec.STRING),(l,v)->l.load(v),DiskLedger::save).add().build();
    static final int[] CAPACITIES={1024,4096,16384,65536};
    static final String PREFIX="MBD_Disk_",ID="MBD_DiskId",SEAL="MBD_Seal";
    static final class Entry {ItemStack stack;Entry(ItemStack s){stack=copy(s);}}
    static final class Disk {
        final UUID id,owner,seal;int tier;final List<Entry> entries=new ArrayList<>();
        Disk(UUID i,UUID o,UUID s,int t){id=i;owner=o;seal=s;tier=t;}
        int used(){int count=0;for(var e:entries)if(!ItemStack.isEmpty(e.stack))count=Math.addExact(count,e.stack.getQuantity());return count;}
        int capacity(){return CAPACITIES[tier];}
        ItemStack cartridge(){return new ItemStack(PREFIX+label(tier),1).withMetadata(ID,new BsonString(id.toString())).withMetadata(SEAL,new BsonString(seal.toString()));}
    }
    interface RackAccess {List<Rack> members();}
    static final class Rack implements RackAccess {
        final UUID owner;final UUID[] bays=new UUID[8];transient UUID lease;long revision;
        Rack(UUID o){owner=o;}
        public List<Rack> members(){return List.of(this);}
        boolean empty(){return Arrays.stream(bays).allMatch(Objects::isNull);}
    }
    record RackGroup(List<Rack> members) implements RackAccess {
        RackGroup {
            members=List.copyOf(members);
            if(members.isEmpty()||members.size()>RackNetwork.MAX_RACKS||new HashSet<>(members).size()!=members.size())throw new IllegalArgumentException("Invalid connected rack group");
            var owner=members.getFirst().owner;if(members.stream().anyMatch(r->!r.owner.equals(owner)))throw new IllegalArgumentException("Connected racks must have the same owner");
        }
    }
    final Map<UUID,Disk> disks=new LinkedHashMap<>();final Map<String,Rack> racks=new LinkedHashMap<>();long revision;
    static String label(int tier){return new String[]{"1K","4K","16K","64K"}[tier];}
    static int tier(ItemStack item){if(ItemStack.isEmpty(item))return -1;for(int i=0;i<4;i++)if(item.getItemId().equals(PREFIX+label(i)))return i;return -1;}
    static ItemStack copy(ItemStack item){return ItemStack.isEmpty(item)?null:ItemStack.CODEC.decode(ItemStack.CODEC.encode(item),EmptyExtraInfo.EMPTY);}
    static UUID identity(ItemStack item){var m=item.getMetadata();if(m==null||!m.containsKey(ID))return null;try{return UUID.fromString(m.getString(ID).getValue());}catch(RuntimeException ex){throw new IllegalArgumentException("Invalid disk identity");}}
    Rack rack(String key,UUID owner){var rack=racks.computeIfAbsent(key,k->new Rack(owner));if(!rack.owner.equals(owner))throw new IllegalArgumentException("This rack belongs to another player");return rack;}
    void mutable(RackAccess access,UUID owner){for(var rack:access.members()){if(!rack.owner.equals(owner))throw new IllegalArgumentException("This rack belongs to another player");if(rack.lease!=null)throw new IllegalArgumentException("Finish or cancel the native craft first");}}
    static boolean available(RackAccess access,UUID token){return access!=null&&access.members().stream().allMatch(r->r.lease==null||token!=null&&token.equals(r.lease));}
    void acquire(RackAccess access,UUID owner,UUID token){Objects.requireNonNull(token);mutable(access,owner);for(var rack:access.members())rack.lease=token;}
    void release(RackAccess access,UUID token){if(access!=null)for(var rack:access.members())if(token.equals(rack.lease))rack.lease=null;}
    Disk admit(Rack rack,int bay,UUID owner,ItemStack cartridge){
        mutable(rack,owner);if(bay<0||bay>=8||rack.bays[bay]!=null||cartridge.getQuantity()!=1)throw new IllegalArgumentException("Choose an empty bay and one disk");
        int t=tier(cartridge);if(t<0)throw new IllegalArgumentException("Choose a storage disk");UUID id=identity(cartridge);Disk disk;
        if(id==null){disk=new Disk(UUID.randomUUID(),owner,UUID.randomUUID(),t);disks.put(disk.id,disk);}else{
            disk=disks.get(id);var m=cartridge.getMetadata();
            if(disk==null||!disk.owner.equals(owner)||t!=disk.tier||m==null||!m.containsKey(SEAL)||!m.getString(SEAL).getValue().equals(disk.seal.toString()))throw new IllegalArgumentException("Disk identity, owner or capacity does not match this world");
        }
        for(var other:racks.values())for(var mounted:other.bays)if(disk.id.equals(mounted))throw new IllegalArgumentException("That disk is already mounted; duplicate refused");
        rack.bays[bay]=disk.id;touch(rack);return disk;
    }
    ItemStack eject(Rack rack,int bay,UUID owner){mutable(rack,owner);var d=disk(rack,bay);var item=d.cartridge();rack.bays[bay]=null;touch(rack);return item;}
    Disk disk(Rack rack,int bay){if(bay<0||bay>=8||rack.bays[bay]==null)throw new IllegalArgumentException("Choose an occupied bay");var d=disks.get(rack.bays[bay]);if(d==null)throw new IllegalStateException("Disk record missing; leave this rack intact");return d;}
    List<Disk> mounted(RackAccess access){var result=new ArrayList<Disk>();for(var rack:access.members())for(int i=0;i<8;i++)if(rack.bays[i]!=null)result.add(disk(rack,i));return result;}
    int used(RackAccess rack){return mounted(rack).stream().mapToInt(Disk::used).sum();}
    int capacity(RackAccess rack){return mounted(rack).stream().mapToInt(Disk::capacity).sum();}
    static void storable(ItemStack item){if(ItemStack.isEmpty(item)||item.getQuantity()<1||tier(item)>=0)throw new IllegalArgumentException("Storage disks cannot be stored inside disks");}
    boolean room(RackAccess rack,ItemStack item){storable(item);return item.getQuantity()<=capacity(rack)-used(rack);}
    void deposit(RackAccess rack,UUID owner,ItemStack item){mutable(rack,owner);storable(item);if(!room(rack,item))throw new IllegalArgumentException("Not enough disk capacity; nothing deposited");
        int left=item.getQuantity();for(var d:mounted(rack)){int n=Math.min(left,d.capacity()-d.used());if(n==0)continue;Entry target=null;for(var e:d.entries)if(!ItemStack.isEmpty(e.stack)&&e.stack.isStackableWith(item)){target=e;break;}
            if(target==null)d.entries.add(new Entry(item.withQuantity(n)));else target.stack=copy(target.stack.withQuantity(target.stack.getQuantity()+n));left-=n;if(left==0)break;}
        touch(rack);
    }
    ItemStack withdraw(RackAccess rack,UUID owner,Entry entry,int quantity){mutable(rack,owner);if(quantity<1||ItemStack.isEmpty(entry.stack)||quantity>entry.stack.getQuantity()||mounted(rack).stream().noneMatch(d->d.entries.contains(entry)))throw new IllegalArgumentException("Stored item changed; refresh");
        var result=copy(entry.stack.withQuantity(quantity));entry.stack=quantity==entry.stack.getQuantity()?null:copy(entry.stack.withQuantity(entry.stack.getQuantity()-quantity));touch(rack);return result;
    }
    void upgrade(Rack rack,int bay,UUID owner){mutable(rack,owner);var d=disk(rack,bay);if(d.tier>=3)throw new IllegalArgumentException("This disk is already 64K");d.tier++;touch(rack);}
    void touch(RackAccess access){for(var rack:access.members())rack.revision++;revision++;}
    String save(){var root=new JsonObject();root.addProperty("schema",1);var ds=new JsonArray();for(var d:disks.values()){var o=new JsonObject();o.addProperty("id",d.id.toString());o.addProperty("owner",d.owner.toString());o.addProperty("seal",d.seal.toString());o.addProperty("tier",d.tier);var items=new JsonArray();for(var e:d.entries)if(!ItemStack.isEmpty(e.stack))items.add(ItemStack.CODEC.encode(e.stack).asDocument().toJson());o.add("items",items);ds.add(o);}root.add("disks",ds);var rs=new JsonObject();for(var e:racks.entrySet()){var o=new JsonObject();o.addProperty("owner",e.getValue().owner.toString());var bays=new JsonArray();for(var id:e.getValue().bays)bays.add(id==null?null:id.toString());o.add("bays",bays);rs.add(e.getKey(),o);}root.add("racks",rs);return JSON.toJson(root);}
    void load(String text){var root=JsonParser.parseString(text).getAsJsonObject();if(root.get("schema").getAsInt()!=1)throw new IllegalStateException("Unsupported disk ledger");var next=new DiskLedger();for(var value:root.getAsJsonArray("disks")){var o=value.getAsJsonObject();var d=new Disk(UUID.fromString(o.get("id").getAsString()),UUID.fromString(o.get("owner").getAsString()),UUID.fromString(o.get("seal").getAsString()),o.get("tier").getAsInt());if(d.tier<0||d.tier>3||next.disks.putIfAbsent(d.id,d)!=null)throw new IllegalStateException("Invalid disk record");for(var item:o.getAsJsonArray("items")){var stack=ItemStack.CODEC.decode(BsonDocument.parse(item.getAsString()),EmptyExtraInfo.EMPTY);storable(stack);d.entries.add(new Entry(stack));}if(d.used()>d.capacity())throw new IllegalStateException("Overfull disk record");}
        var admitted=new HashSet<UUID>();for(var e:root.getAsJsonObject("racks").entrySet()){var o=e.getValue().getAsJsonObject();var rack=new Rack(UUID.fromString(o.get("owner").getAsString()));var bays=o.getAsJsonArray("bays");if(bays.size()!=8)throw new IllegalStateException("Invalid rack bays");for(int i=0;i<8;i++)if(!bays.get(i).isJsonNull()){var id=UUID.fromString(bays.get(i).getAsString());var d=next.disks.get(id);if(d==null||!d.owner.equals(rack.owner)||!admitted.add(id))throw new IllegalStateException("Duplicate or invalid mounted disk");rack.bays[i]=id;}next.racks.put(e.getKey(),rack);}
        disks.clear();disks.putAll(next.disks);racks.clear();racks.putAll(next.racks);revision++;
    }
    @Override public DiskLedger clone(){var result=new DiskLedger();result.load(save());return result;}
}
