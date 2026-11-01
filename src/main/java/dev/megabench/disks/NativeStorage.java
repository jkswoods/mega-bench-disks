package dev.megabench.disks;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.protocol.packets.inventory.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.windows.*;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.inventory.transaction.ClearTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Native inventory sections and packets, with the existing disk ledger retaining all item authority. */
final class NativeStorage {
    static final Map<UUID,NativeStorage> ACTIVE=new ConcurrentHashMap<>();
    static final int HOTBAR=-1,STORAGE=-2,BACKPACK=-9;
    final StorageSession session;
    final com.hypixel.hytale.server.core.universe.world.World world;
    final Set<Integer> retiredAndCurrent=ConcurrentHashMap.newKeySet();
    volatile Map<Integer,List<ItemStack>> playerSnapshot=Map.of();
    volatile boolean opened,closed;
    volatile int windowId=-1;
    CustomUIPage page;
    PoolWindow window;
    com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler routedHandler;
    com.hypixel.hytale.server.core.io.handlers.game.InventoryPacketHandler vanillaInventory;
    String query="";
    List<ItemStack> prototypes=List.of();
    long revision;
    final Set<Integer> refreshSourceGrids=new HashSet<>();
    record DragSource(long revision,int section,int slot,ItemStack expected){}
    DragSource dragSource;

    NativeStorage(StorageSession session){this.session=session;world=session.world();ACTIVE.put(session.owner(),this);}
    Player player(){return session.context.store().getComponent(session.context.ref(),Player.getComponentType());}
    boolean current(){var p=player();return !closed&&opened&&ACTIVE.get(session.owner())==this&&p!=null&&p.getPageManager().getCustomPage()==page&&session.valid();}
    void writable(){if(!current()||session.manageDisks||session.running)throw new IllegalArgumentException("Storage transfers are paused");session.writable();}
    ItemContainer section(int id){
        if(id!=HOTBAR&&id!=STORAGE&&id!=BACKPACK)throw new IllegalArgumentException("Choose a player inventory slot");
        var type=InventoryComponent.getComponentTypeById(id);var component=session.context.store().getComponent(session.context.ref(),type);
        if(component==null)throw new IllegalArgumentException("Inventory is no longer available");return component.getInventory();
    }
    ItemContainer transferInventory(){
        var parts=new ArrayList<ItemContainer>();for(int id:new int[]{HOTBAR,STORAGE,BACKPACK}){
            var component=session.context.store().getComponent(session.context.ref(),InventoryComponent.getComponentTypeById(id));
            if(component!=null&&component.getInventory().getCapacity()>0)parts.add(component.getInventory());
        }return new CombinedItemContainer(parts.toArray(ItemContainer[]::new));
    }
    static void slot(ItemContainer inventory,int index){if(index<0||index>=inventory.getCapacity())throw new IllegalArgumentException("Inventory slot changed");}
    ItemStack expected(int section,int slot){var items=playerSnapshot.get(section);return items!=null&&slot>=0&&slot<items.size()?items.get(slot):null;}
    void snapshot(){
        var map=new HashMap<Integer,List<ItemStack>>();
        for(int id:new int[]{HOTBAR,STORAGE,BACKPACK}){
            var component=session.context.store().getComponent(session.context.ref(),InventoryComponent.getComponentTypeById(id));if(component==null)continue;
            var inventory=component.getInventory();var items=new ArrayList<ItemStack>();for(short i=0;i<inventory.getCapacity();i++)items.add(DiskLedger.copy(inventory.getItemStack(i)));
            map.put(id,Collections.unmodifiableList(items));
        }
        playerSnapshot=Map.copyOf(map);
    }
    void sync(boolean show,String key,List<StorageItems.Variant> shown){
        if(!show){deactivate();return;}
        if(closed)return;opened=true;page=player().getPageManager().getCustomPage();snapshot();attachRouter();
        var keys=shown.stream().map(v->DiskLedger.copy(v.prototype())).toList();
        if(window==null||player().getWindowManager().getWindow(windowId)!=window||!key.equals(query)||!keys.equals(prototypes)){
            revision++;dragSource=null;
            var old=window;var next=new PoolWindow(this);next.projection.refresh(shown);var packet=player().getWindowManager().openWindow(session.context.ref(),next,session.context.store());
            if(packet==null)throw new IllegalStateException("Native storage window could not open");
            window=next;windowId=next.getId();retiredAndCurrent.add(windowId);query=key;prototypes=keys;
            DisksPlugin.instance.getLogger().atInfo().log("MBD_NATIVE_VIEW id=%d variants=%d writable=%s",windowId,keys.size(),session.valid()&&!session.running&&!session.manageDisks&&DiskLedger.available(session.network,null));
            session.context.player().getPacketHandler().write(packet);
            // Keep the replacement registered before retiring an old filtered/sorted view.
            if(old!=null&&player().getWindowManager().getWindow(old.getId())==old)player().getWindowManager().closeWindow(session.context.ref(),old.getId(),session.context.store());
        }else window.projection.refresh(shown);
        player().getWindowManager().markWindowChanged(windowId);
    }
    void deactivate(){
        if(opened){revision++;dragSource=null;}
        opened=false;var old=window;window=null;windowId=-1;prototypes=List.of();
        if(old!=null&&session.context.ref().isValid()){var p=player();if(p!=null&&p.getWindowManager().getWindow(old.getId())==old)p.getWindowManager().closeWindow(session.context.ref(),old.getId(),session.context.store());}
        detachRouter();
    }
    void close(){deactivate();closed=true;ACTIVE.remove(session.owner(),this);}
    static void shutdown(){for(var n:ACTIVE.values()){n.closed=true;n.opened=false;n.detachRouter();}ACTIVE.clear();}
    static void dispatch(ToServerPacket packet,java.util.function.Predicate<Packet> capture,java.util.function.Consumer<ToServerPacket> vanilla){if(!capture.test(packet))vanilla.accept(packet);}
    static void route(com.hypixel.hytale.server.core.io.handlers.GenericPacketHandler handler,int id,java.util.function.Predicate<Packet> capture,java.util.function.Consumer<ToServerPacket> vanilla){handler.registerHandler(id,p->dispatch(p,capture,vanilla));}
    void bindGrid(com.hypixel.hytale.server.core.ui.builder.UIEventBuilder events,String selector,int section){
        var types=com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.values();
        for(var type:types){String kind=switch(type){case SlotClicking->"Select";case Dropped->"Drop";case SlotClickPressWhileDragging->"ClickDrop";default->null;};if(kind==null)continue;
            // Native callbacks append their typed SlotIndex/source/quantity fields automatically.
            events.addEventBinding(type,selector,com.hypixel.hytale.server.core.ui.builder.EventData.of("MBDNative",NativeGridEvent.MARKER)
                .append("MBDKind",kind).append("MBDRevision",Long.toString(revision)).append("MBDTarget",Integer.toString(section)),false);
        }
    }
    boolean captureGrid(String raw){
        if(raw==null||raw.length()>8192||!raw.contains("\"MBDNative\""))return false;
        final org.bson.BsonDocument data;
        try{data=org.bson.BsonDocument.parse(raw);if(!NativeGridEvent.MARKER.equals(NativeGridEvent.string(data,"MBDNative")))return false;}
        catch(RuntimeException malformed){return false;}
        DisksPlugin.instance.getLogger().atInfo().log("MBD_NATIVE_UI %s",data.toJson());
        try{var event=NativeGridEvent.read(data);world.execute(()->gridEvent(event));}
        catch(IllegalArgumentException malformed){DisksPlugin.instance.getLogger().atInfo().log("MBD_NATIVE_UI_REFUSED %s",malformed.getMessage());}
        catch(java.util.concurrent.RejectedExecutionException ignored){/* Normal world teardown. */}
        return true;
    }
    void gridEvent(NativeGridEvent event){
        // One revision can commit once. A second callback for the same physical gesture is stale.
        if(event.revision()!=revision||!current())return;
        try{
            writable();int target=event.target();
            if(target!=windowId&&target!=HOTBAR&&target!=STORAGE&&target!=BACKPACK)throw new IllegalArgumentException("Storage view changed; try again");
            if(event.kind().equals("Select")){
                ItemStack item=target==windowId?stored(target,event.slot()):expected(target,event.slot());
                if(!ItemStack.isEmpty(item))dragSource=new DragSource(revision,target,event.slot(),DiskLedger.copy(item));
                else dragSource=null;
                return;
            }
            int from;ItemStack expected;
            if(event.source()!=null){from=event.source();expected=from==windowId?stored(from,event.sourceSlot()):expected(from,event.sourceSlot());}
            else{
                var drag=dragSource;if(drag==null||drag.revision()!=revision||drag.slot()!=event.sourceSlot())throw new IllegalArgumentException("Pick up the stack again");
                from=drag.section();expected=drag.expected();
            }
            if(ItemStack.isEmpty(expected)||!expected.getItemId().equals(event.itemId()))throw new IllegalArgumentException("Dragged item changed; try again");
            int quantity=Math.min(event.quantity(),expected.getItem().getMaxStack());
            if(from==target&&event.sourceSlot()==event.slot())return;
            // The native custom-grid cursor owns a detached copy. After single placement,
            // retire its source node so the remaining stack stays at its authoritative source
            // instead of displaying an obsolete held count. No cursor items are server-owned.
            if(event.partialPlacement())refreshSourceGrids.add(from);
            if(from==windowId||target==windowId)move(from,event.sourceSlot(),quantity,target,event.slot(),expected);
            else{
                var source=section(from);var destination=section(target);source(source,event.sourceSlot(),expected);slot(destination,event.slot());
                source.moveItemStackFromSlotToSlot((short)event.sourceSlot(),quantity,destination,(short)event.slot());
            }
        }catch(IllegalArgumentException|IllegalStateException ex){session.notice=ex.getMessage();}
        finally{if(!event.kind().equals("Select")){dragSource=null;resync();}}
    }
    void attachRouter(){
        if(routedHandler!=null)return;
        if(!(session.context.player().getPacketHandler() instanceof com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler handler))throw new IllegalStateException("This connection cannot open native Storage");
        var inventory=handler.getSubPacketHandler(com.hypixel.hytale.server.core.io.handlers.game.InventoryPacketHandler.class);
        if(inventory==null)throw new IllegalStateException("Native inventory handler is unavailable");
        // The SDK's PacketAdapters hook is Netty-specific. Register with this player's native
        // dispatcher so local and other supported transports follow the same inventory path.
        // Unrelated packets delegate to the exact native subhandler; close restores these four handlers.
        vanillaInventory=inventory;routedHandler=handler;
        route(handler,MoveItemStack.PACKET_ID,this::capture,q->inventory.handle((MoveItemStack)q));
        route(handler,SmartMoveItemStack.PACKET_ID,this::capture,q->inventory.handle((SmartMoveItemStack)q));
        route(handler,InventoryAction.PACKET_ID,this::capture,q->inventory.handle((InventoryAction)q));
        route(handler,DropItemStack.PACKET_ID,this::capture,q->inventory.handle((DropItemStack)q));
        handler.registerHandler(com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent.PACKET_ID,p->{
            var event=(com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent)p;
            if(captureGrid(event.data))return;
            handler.handle(event);
        });
    }
    void detachRouter(){
        var handler=routedHandler;var inventory=vanillaInventory;if(handler==null)return;routedHandler=null;vanillaInventory=null;
        handler.registerHandler(MoveItemStack.PACKET_ID,p->inventory.handle((MoveItemStack)p));
        handler.registerHandler(SmartMoveItemStack.PACKET_ID,p->inventory.handle((SmartMoveItemStack)p));
        handler.registerHandler(InventoryAction.PACKET_ID,p->inventory.handle((InventoryAction)p));
        handler.registerHandler(DropItemStack.PACKET_ID,p->inventory.handle((DropItemStack)p));
        handler.registerHandler(com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent.PACKET_ID,p->handler.handle((com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent)p));
    }
    void resync(){
        revision++;if(!session.context.ref().isValid())return;
        if(player()==null)return;
        for(int id:new int[]{HOTBAR,STORAGE,BACKPACK}){var c=session.context.store().getComponent(session.context.ref(),InventoryComponent.getComponentTypeById(id));if(c!=null)c.markDirty();}
        snapshot();if(window!=null){
            var variants=StorageItems.variants(session.ledger,session.network);var current=new ArrayList<StorageItems.Variant>();
            for(var prototype:prototypes){var found=StorageItems.find(variants,prototype);current.add(found);}
            window.projection.refresh(current);player().getWindowManager().markWindowChanged(windowId);
        }
    }
    void run(Runnable action){
        try{if(closed)return;writable();action.run();}
        catch(IllegalArgumentException|IllegalStateException ex){session.notice=ex.getMessage();}
        finally{resync();}
    }
    void enqueue(Runnable action){try{world.execute(()->run(action));}catch(IllegalStateException|java.util.concurrent.RejectedExecutionException ignored){/* Normal world teardown; projection never owns items. */}}
    static ItemStack storedFromView(int current,List<ItemStack> prototypes,int id,int index){if(current<0||id!=current||index<0||index>=prototypes.size())throw new IllegalArgumentException("Storage view changed; try again");return prototypes.get(index);}
    ItemStack stored(int id,int index){return storedFromView(windowId,prototypes,id,index);}
    void source(ItemContainer inventory,int index,ItemStack expected){slot(inventory,index);var now=inventory.getItemStack((short)index);if(ItemStack.isEmpty(expected)||ItemStack.isEmpty(now)||!expected.isStackableWith(now))throw new IllegalArgumentException("Inventory changed; try again");}
    void moved(String verb,int count,ItemStack item){session.notice=verb+" "+count+" "+session.name(item)+".";if(count>0)session.cue("Handling");}
    int combinedSlot(int section,int slot){int start=section==HOTBAR?0:section(HOTBAR).getCapacity();if(section==BACKPACK)start+=section(STORAGE).getCapacity();return start+slot;}
    void move(int from,int index,int quantity,int to,int target,ItemStack expected){
        if(quantity<1)throw new IllegalArgumentException("Invalid transfer quantity");
        if(to==windowId){
            if(from>=0||target<0||target>=StorageSession.PAGE_SIZE)throw new IllegalArgumentException("Choose a stored item or a player inventory slot");
            var inventory=section(from);source(inventory,index,expected);var item=inventory.getItemStack((short)index);
            if(DiskLedger.tier(item)>=0){session.action("Inventory:"+combinedSlot(from,index),"");return;}
            int n=StorageItems.deposit(session.ledger,session.network,session.owner(),inventory,(short)index,expected,quantity);moved("Deposited",n,item);
        }else{
            var prototype=stored(from,index);var inventory=section(to);slot(inventory,target);
            int n=StorageItems.withdrawFitting(session.ledger,session.network,session.owner(),inventory,(short)target,prototype,Math.min(quantity,prototype.getItem().getMaxStack()));moved("Withdrew",n,prototype);
        }
    }
    void quick(int from,int index,int quantity,SmartMoveType type,ItemStack expected){
        if(quantity<1)throw new IllegalArgumentException("Invalid transfer quantity");
        if(type==SmartMoveType.EquipOrMergeStack)throw new IllegalArgumentException("Drag or Shift-click to transfer items");
        if(from==windowId){var prototype=stored(from,index);int n=StorageItems.withdrawFitting(session.ledger,session.network,session.owner(),transferInventory(),(short)-1,prototype,quantity);moved("Withdrew",n,prototype);}
        else{if(retiredAndCurrent.contains(from))throw new IllegalArgumentException("Storage view changed; try again");move(from,index,quantity,windowId,0,expected);}
    }
    void bulk(int id,InventoryActionType action){
        if(id!=windowId)throw new IllegalArgumentException("Storage view changed; try again");int count=0;
        if(action==InventoryActionType.TakeAll){for(var prototype:List.copyOf(prototypes)){try{count+=StorageItems.withdrawFitting(session.ledger,session.network,session.owner(),transferInventory(),(short)-1,prototype,Integer.MAX_VALUE);}catch(IllegalArgumentException full){break;}}}
        else if(action==InventoryActionType.PutAll||action==InventoryActionType.QuickStack){
            var inventory=session.inventory();for(short i=0;i<inventory.getCapacity()&&session.ledger.used(session.network)<session.ledger.capacity(session.network);i++){
                var item=inventory.getItemStack(i);if(ItemStack.isEmpty(item)||DiskLedger.tier(item)>=0)continue;
                if(action==InventoryActionType.QuickStack&&StorageItems.find(StorageItems.variants(session.ledger,session.network),item)==null)continue;
                count+=StorageItems.deposit(session.ledger,session.network,session.owner(),inventory,i,item,Integer.MAX_VALUE);
            }
        }else if(action==InventoryActionType.Sort){session.notice="Stored items are sorted by name.";return;}
        else throw new IllegalArgumentException("Unsupported inventory action");
        session.notice="Transferred "+count+" items.";if(count>0)session.cue("Handling");
    }
    boolean capture(Packet packet){
        var n=this;
        if(packet instanceof MoveItemStack p)DisksPlugin.instance.getLogger().atInfo().log("MBD_NATIVE_MOVE %d:%d -> %d:%d qty=%d",p.fromSectionId,p.fromSlotId,p.toSectionId,p.toSlotId,p.quantity);
        if(packet instanceof SmartMoveItemStack p)DisksPlugin.instance.getLogger().atInfo().log("MBD_NATIVE_QUICK %d:%d qty=%d type=%s",p.fromSectionId,p.fromSlotId,p.quantity,p.moveType);
        if(packet instanceof MoveItemStack p&&(n.retiredAndCurrent.contains(p.fromSectionId)||n.retiredAndCurrent.contains(p.toSectionId))){
            int from=p.fromSectionId,index=p.fromSlotId,quantity=p.quantity,to=p.toSectionId,target=p.toSlotId;var expected=n.expected(from,index);n.enqueue(()->n.move(from,index,quantity,to,target,expected));return true;
        }
        if(packet instanceof SmartMoveItemStack p&&(n.retiredAndCurrent.contains(p.fromSectionId)||n.opened&&(p.fromSectionId==HOTBAR||p.fromSectionId==STORAGE||p.fromSectionId==BACKPACK)&&p.moveType==SmartMoveType.PutInHotbarOrWindow)){
            int from=p.fromSectionId,index=p.fromSlotId,quantity=p.quantity;var type=p.moveType;var expected=n.expected(from,index);n.enqueue(()->n.quick(from,index,quantity,type,expected));return true;
        }
        if(packet instanceof InventoryAction p&&n.retiredAndCurrent.contains(p.inventorySectionId)){int id=p.inventorySectionId;var action=p.inventoryActionType;n.enqueue(()->n.bulk(id,action));return true;}
        if(packet instanceof DropItemStack p&&n.retiredAndCurrent.contains(p.inventorySectionId)){n.enqueue(()->{throw new IllegalArgumentException("Move stored items into your inventory first");});return true;}
        return false;
    }
    /** This is a display projection, never a second inventory. Unhandled native operations cannot mutate it. */
    static final class Projection extends SimpleItemContainer {
        private boolean refreshing;
        Projection(){super((short)StorageSession.PAGE_SIZE);}
        void refresh(List<StorageItems.Variant> shown){refreshing=true;try{for(short i=0;i<getCapacity();i++){var v=i<shown.size()?shown.get(i):null;super.internal_setSlot(i,v==null||v.quantity()==0?null:DiskLedger.copy(v.display()));}}finally{refreshing=false;}}
        @Override protected boolean cantAddToSlot(short slot,ItemStack item,ItemStack current){return true;}
        @Override protected boolean cantRemoveFromSlot(short slot){return true;}
        @Override protected boolean cantDropFromSlot(short slot){return true;}
        @Override protected boolean cantMoveToSlot(ItemContainer from,short slot){return true;}
        @Override protected ItemStack internal_setSlot(short slot,ItemStack item){throw new UnsupportedOperationException("Read-only storage projection");}
        @Override protected ItemStack internal_removeSlot(short slot){if(refreshing)return super.internal_removeSlot(slot);throw new UnsupportedOperationException("Read-only storage projection");}
        @Override protected ClearTransaction internal_clear(){throw new UnsupportedOperationException("Read-only storage projection");}
    }
    static final class PoolWindow extends ContainerWindow implements ValidatedWindow {
        final NativeStorage owner;final Projection projection;
        PoolWindow(NativeStorage owner){this(owner,new Projection());}
        private PoolWindow(NativeStorage owner,Projection projection){super(projection);this.owner=owner;this.projection=projection;}
        @Override public boolean validate(Ref<EntityStore> ref,ComponentAccessor<EntityStore> accessor){return owner.current();}
    }
}
