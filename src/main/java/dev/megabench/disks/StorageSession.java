package dev.megabench.disks;

import dev.megabench.CompanionExtension;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import java.util.*;

final class StorageSession implements CompanionExtension.Session {
    static final int COLUMNS=9,PAGE_SIZE=18;
    final CompanionExtension.Context context;final DiskLedger ledger;final UUID token=UUID.randomUUID();
    DiskLedger.Rack rack;DiskLedger.RackGroup network;
    List<RackNetwork.Position> positions=List.of();long topologyRevision;
    String rackKey="",notice="Place a Drive Rack against this Mega Bench, then connect more racks face to face.";
    static final String CONNECTED="Your connected Drive Racks are available.";
    int offset,selectedSlot=-1,selectedBay=0,upgradeBay=-1;String search="",filter="All";
    boolean running,built,manageDisks,backpackView,overLimit;
    final NativeStorage nativeStorage;
    List<StorageItems.Variant> shown=List.of(),cachedItems=List.of();
    final Map<String,String> names=new HashMap<>();long cachedRevision=-1;
    StorageSession(CompanionExtension.Context c){context=c;ledger=DisksPlugin.ledger(c.store());
        refreshNetwork();
        nativeStorage=new NativeStorage(this);
    }
    com.hypixel.hytale.server.core.universe.world.World world(){return context.store().getExternalData().getWorld();}
    UUID owner(){return context.player().getUuid();}
    String name(ItemStack item){return names.computeIfAbsent(item.getItemId(),id->CompanionExtension.itemName(id,context.player().getLanguage()));}
    ItemContainer inventory(){return InventoryComponent.getCombined(context.store(),context.ref(),InventoryComponent.HOTBAR_STORAGE_BACKPACK);}
    RackNetwork.Scan scan(){return RackNetwork.fromBench(new RackNetwork.Position(context.x(),context.y(),context.z()),p->DisksPlugin.block(world(),p.x(),p.y(),p.z()),p->{var saved=ledger.racks.get(p.key());return saved==null||saved.owner.equals(owner());});}
    void refreshNetwork(){
        if(running)return;var scan=scan();var next=scan.racks();
        if(!positions.equals(next)||network==null&&!next.isEmpty()||overLimit!=scan.overflow()){
            overLimit=scan.overflow();
            positions=next;topologyRevision++;cachedRevision=-1;upgradeBay=-1;selectedBay=0;
            network=next.isEmpty()?null:new DiskLedger.RackGroup(next.stream().map(p->ledger.rack(p.key(),owner())).toList());
            int index=0;for(int i=0;i<next.size();i++)if(next.get(i).key().equals(rackKey))index=i;
            selectRack(index);notice=next.isEmpty()?"No owned Drive Rack is connected to this Mega Bench.":CONNECTED;
        }
        if(scan.overflow())notice="More than "+RackNetwork.MAX_RACKS+" racks are connected. Separate the extra racks to use Storage.";
    }
    void selectRack(int index){
        if(network==null){rack=null;rackKey="";return;}
        index=Math.clamp(index,0,positions.size()-1);rack=network.members().get(index);rackKey=positions.get(index).key();selectedBay=0;upgradeBay=-1;
    }
    boolean valid(){
        if(network==null||!context.ref().isValid()||!DisksPlugin.block(world(),context.x(),context.y(),context.z()).equals("MB_MegaBench")||context.player().getTransform().getPosition().distanceSquared(context.x()+.5,context.y()+.5,context.z()+.5)>64||!DiskLedger.available(network,token))return false;
        // Recheck real loaded connectivity before every debit, including an old drag/craft snapshot.
        var live=scan();return !live.overflow()&&live.racks().containsAll(positions)&&java.util.stream.IntStream.range(0,positions.size()).allMatch(i->ledger.racks.get(positions.get(i).key())==network.members().get(i));
    }
    void writable(){if(!valid())throw new IllegalArgumentException("Reconnect your Drive Racks and refresh Storage");ledger.mutable(network,owner());}
    @Override public ItemContainer inputs(CraftingRecipe recipe,int quantity){if(!valid())return EmptyItemContainer.INSTANCE;var snapshot=network;return DiskSource.forCosts(ledger,snapshot,CraftingManager.getInputMaterials(recipe,quantity),()->network==snapshot&&valid());}
    @Override public void started(){if(network==null)return;writable();ledger.acquire(network,owner(),token);running=true;}
    @Override public void finished(){ledger.release(network,token);running=false;}
    @Override public void close(){nativeStorage.close();finished();}
    @Override public String signature(){refreshNetwork();return ledger.revision+":"+topologyRevision+":"+valid()+":"+DiskLedger.available(network,null)+":"+running+":"+nativeStorage.revision;}
    @Override public String inputLabel(){return valid()?"Player inventory + "+positions.size()+" Drive "+(positions.size()==1?"Rack":"Racks"):"Player inventory · racks disconnected";}
    void bind(UIEventBuilder e,String selector,String action,long version){e.addEventBinding(CustomUIEventBindingType.Activating,selector,EventData.of("Action","ext:"+action).append("Version",Long.toString(version)),false);}
    static DropdownEntryInfo option(String label,String value){return new DropdownEntryInfo(LocalizableString.fromString(label),value);}
    void dropdown(UIEventBuilder e,String selector,String action,long version){e.addEventBinding(CustomUIEventBindingType.ValueChanged,selector,EventData.of("Action","ext:"+action).append("Version",Long.toString(version)).append("@Search",selector+".Value"),false);}
    static List<ItemStack> items(ItemContainer inventory){var result=new ArrayList<ItemStack>();for(short i=0;i<inventory.getCapacity();i++)result.add(inventory.getItemStack(i));return result;}
    static org.bson.BsonArray nativeSlots(List<ItemStack> items,int minimum){
        var slots=new org.bson.BsonArray();for(int i=0;i<Math.max(items.size(),minimum);i++){
            var slot=i<items.size()&&!ItemStack.isEmpty(items.get(i))?new ItemGridSlot(items.get(i)):new ItemGridSlot();
            slot.setActivatable(true);
            // The pinned server helper omits this client-native property; without it a grid is display-only.
            var encoded=ItemGridSlot.CODEC.encode(slot).asDocument();
            // Legacy custom-grid display cannot accept arbitrary item Metadata, as the
            // base's working NativePresentation preview also documents. Omit it only
            // from the UI descriptor; real stacks/transfer snapshots retain every field.
            var display=encoded.getDocument("ItemStack",null);
            if(display!=null)display.remove("Metadata");
            encoded.put("InventorySlotIndex",new org.bson.BsonInt32(i));slots.add(encoded);
        }return slots;
    }
    static void grid(UICommandBuilder c,String selector,List<ItemStack> items,int minimum,int section){
        c.set(selector+".Slots",new ItemGridSlot[0]);
        // Public native command payload, using the same "0" envelope as UICommandBuilder's encoder.
        // Preserve the stock slot codec and add only the client property absent from that helper.
        var commands=c.getCommands();commands[commands.length-1].data=new org.bson.BsonDocument("0",nativeSlots(items,minimum)).toJson();
        c.set(selector+".InventorySectionId",section);
    }
    @Override public void render(String view,long version,UICommandBuilder c,UIEventBuilder e){
        if(!view.equals("Storage")){manageDisks=false;upgradeBay=-1;}
        if(!built){
            c.append("#ExtensionCentre","Pages/MegaBenchDisks/Storage.ui");c.append("#CompanionOverlay","Pages/MegaBenchDisks/ManageDisks.ui");
            c.append("#StoredGridHost","Pages/MegaBenchDisks/StoredGrid.ui");
            c.append("#DepositGridHost","Pages/MegaBenchDisks/InventoryGrid.ui");
            c.append("#HotbarGridHost","Pages/MegaBenchDisks/HotbarGrid.ui");
            c.set("#StorageFilter.Entries",List.of(option("All items","All"),option("Blocks","Blocks"),option("Materials","Materials"),option("Equipment","Equipment"),option("Other items","Other")));
            built=true;
        }
        boolean connected=valid(),editable=connected&&!running&&DiskLedger.available(network,null);
        int used=network==null?0:ledger.used(network),capacity=network==null?0:ledger.capacity(network),bays=network==null?0:ledger.mounted(network).size();
        int rackIndex=rack==null?0:network.members().indexOf(rack),rackBays=rack==null?0:ledger.mounted(rack).size();
        c.set("#RackIcon.ItemId","MBD_DriveRack");c.set("#RackConnection.Text",connected?"DRIVE RACKS · CONNECTED":"DRIVE RACK · NOT CONNECTED");
        c.set("#RackStatus.Text",connected?String.format(Locale.ROOT,"%d %s · %d disks · %,d / %,d items",positions.size(),positions.size()==1?"rack":"racks",bays,used,capacity):notice);
        c.set("#Capacity.Text",String.format(Locale.ROOT,"%,d / %,d items",used,capacity));c.set("#ManageDisks.Text","Disks "+bays+"/"+(positions.size()*8));c.set("#ManageDisks.Disabled",!connected);
        c.set("#CompanionOverlay.Visible",manageDisks);c.set("#RackCapacity.Text",rack==null?"No connected rack":String.format(Locale.ROOT,"Rack %d / %d · %s",rackIndex+1,positions.size(),rackKey));
        c.set("#RackPrevious.Disabled",rackIndex==0);c.set("#RackNext.Disabled",rackIndex+1>=positions.size());
        c.set("#RackNavigation.Visible",positions.size()>1);bind(e,"#RackPrevious","RackPrevious",version);bind(e,"#RackNext","RackNext",version);
        boolean showNotice=!connected||!notice.equals(CONNECTED);
        c.set("#StorageNotice.Visible",true);c.set("#StorageNotice.Text",running?"Crafting in progress — transfers paused. Cancel is available below.":showNotice?notice:"Drag items · Shift-click to transfer.");
        c.set("#DiskNotice.Visible",running||showNotice);c.set("#DiskNotice.Text",running?"Finish or cancel the native craft before changing disks.":notice);
        c.set("#StorageFilter.Value",filter);dropdown(e,"#StorageFilter","Filter",version);
        for(String action:List.of("Previous","Next","Mount","Backpack"))bind(e,"#Storage"+action,action,version);
        bind(e,"#ManageDisks","ManageDisks",version);bind(e,"#CloseDisks","CloseDisks",version);bind(e,"#ConfirmUpgrade","ConfirmUpgrade",version);bind(e,"#CancelUpgrade","CancelUpgrade",version);
        e.addEventBinding(CustomUIEventBindingType.Activating,"#StorageFind",EventData.of("Action","ext:Search").append("Version",Long.toString(version)).append("@Search","#StorageSearch.Value"),false);
        // Replace only a retired source grid, retaining search, frame and Crafting context.
        for(int section:nativeStorage.refreshSourceGrids){
            String host=section>=0?"#StoredGridHost":section==NativeStorage.HOTBAR?"#HotbarGridHost":"#DepositGridHost";
            String file=section>=0?"StoredGrid.ui":section==NativeStorage.HOTBAR?"HotbarGrid.ui":"InventoryGrid.ui";
            c.clear(host);c.append(host,"Pages/MegaBenchDisks/"+file);
        }
        nativeStorage.refreshSourceGrids.clear();
        for(String id:List.of("#StoredTiles","#DepositTiles","#HotbarTiles"))c.set(id+".AreItemsDraggable",editable&&!manageDisks&&view.equals("Storage"));
        if(cachedRevision!=ledger.revision){cachedItems=StorageItems.variants(ledger,network);cachedRevision=ledger.revision;}var all=cachedItems;var entries=new ArrayList<StorageItems.Variant>();
        for(var item:all)if((filter.equals("All")||StorageItems.category(item.prototype()).equals(filter))&&(search.isBlank()||(name(item.prototype())+" "+item.prototype().getItemId()).toLowerCase(Locale.ROOT).contains(search)))entries.add(item);
        entries.sort(Comparator.comparing(item->name(item.prototype())));offset=Math.clamp(offset,0,Math.max(0,(entries.size()-1)/PAGE_SIZE*PAGE_SIZE));shown=List.copyOf(entries.subList(offset,Math.min(offset+PAGE_SIZE,entries.size())));
        nativeStorage.sync(view.equals("Storage"),topologyRevision+"|"+filter+"|"+search+"|"+offset,shown);
        grid(c,"#StoredTiles",shown.stream().map(StorageItems.Variant::display).toList(),PAGE_SIZE,nativeStorage.windowId<0?-1000:nativeStorage.windowId);
        c.set("#StorageCount.Text",entries.isEmpty()?"No stored items match":entries.size()+(entries.size()==1?" item variant · ":" item variants · ")+(offset/PAGE_SIZE+1)+" / "+Math.max(1,(entries.size()+PAGE_SIZE-1)/PAGE_SIZE));
        c.set("#StoragePrevious.Disabled",offset==0);c.set("#StorageNext.Disabled",offset+PAGE_SIZE>=entries.size());
        var inv=inventory();var bag=context.store().getComponent(context.ref(),InventoryComponent.Backpack.getComponentType());var backpack=bag==null?EmptyItemContainer.INSTANCE:bag.getInventory();if(backpack.getCapacity()==0)backpackView=false;
        var display=backpackView?backpack:nativeStorage.section(NativeStorage.STORAGE);
        grid(c,"#DepositTiles",items(display),backpackView?0:36,backpackView?NativeStorage.BACKPACK:NativeStorage.STORAGE);
        grid(c,"#HotbarTiles",items(nativeStorage.section(NativeStorage.HOTBAR)),9,NativeStorage.HOTBAR);
        nativeStorage.bindGrid(e,"#StoredTiles",nativeStorage.windowId);
        nativeStorage.bindGrid(e,"#DepositTiles",backpackView?NativeStorage.BACKPACK:NativeStorage.STORAGE);
        nativeStorage.bindGrid(e,"#HotbarTiles",NativeStorage.HOTBAR);
        c.set("#DepositTiles.ShowScrollbar",backpackView&&backpack.getCapacity()>36);
        c.set("#StorageBackpack.Visible",backpack.getCapacity()>0);c.set("#StorageBackpack.Text",backpackView?"Inventory":"Backpack");c.set("#InventoryHeading.Text",backpackView?"BACKPACK":"INVENTORY");
        c.clear("#DiskRows");for(int i=0;i<8;i++){
            String cell="#DiskRows["+i+"]";c.append("#DiskRows","Pages/MegaBenchDisks/BaySlot.ui");boolean occupied=rack!=null&&rack.bays[i]!=null;var disk=occupied?ledger.disk(rack,i):null;
            c.set(cell+" #BayIcon.Visible",occupied);if(occupied)c.set(cell+" #BayIcon.ItemId",DiskLedger.PREFIX+DiskLedger.label(disk.tier));c.set(cell+" #BayNumber.Text",Integer.toString(i+1));c.set(cell+" #Selected.Visible",i==selectedBay);
            c.set(cell+".TooltipText",occupied?String.format(Locale.ROOT,"Bay %d · %s · %,d / %,d items",i+1,DiskLedger.label(disk.tier),disk.used(),disk.capacity()):"Bay "+(i+1)+" · Empty");bind(e,cell,"SelectBay:"+i,version);
        }
        var disk=rack!=null&&selectedBay>=0&&selectedBay<8&&rack.bays[selectedBay]!=null?ledger.disk(rack,selectedBay):null;
        boolean mountSelection=selectedSlot>=0&&selectedSlot<inv.getCapacity()&&DiskLedger.tier(inv.getItemStack((short)selectedSlot))>=0;
        c.set("#SelectedDisk.Text",mountSelection?name(inv.getItemStack((short)selectedSlot))+" selected in inventory":disk==null?"Bay "+(selectedBay+1)+" · Empty":String.format(Locale.ROOT,"%s Disk · %,d / %,d items",DiskLedger.label(disk.tier),disk.used(),disk.capacity()));
        c.set("#DiskEject.Visible",!mountSelection);c.set("#DiskUpgrade.Visible",!mountSelection);c.set("#DiskEject.Disabled",!editable||disk==null);c.set("#DiskUpgrade.Disabled",!connected||disk==null||disk.tier>=3);
        bind(e,"#DiskEject","Eject:"+selectedBay,version);bind(e,"#DiskUpgrade","PreviewUpgrade:"+selectedBay,version);
        c.set("#StorageMount.Visible",mountSelection);c.set("#StorageMount.Disabled",!editable||!mountSelection||rackBays>=8);
        c.set("#MountHint.Visible",mountSelection||disk==null);c.set("#MountHint.Text",mountSelection?"Mount in the first empty bay; contents stay on the disk.":"Return to Storage and drag a cartridge into Stored items to select it.");
        var upgrade=upgradeBay>=0&&rack!=null&&rack.bays[upgradeBay]!=null?ledger.disk(rack,upgradeBay):null;boolean canUpgrade=upgrade!=null&&upgrade.tier<3;
        c.set("#UpgradePanel.Visible",canUpgrade);var panel=new Anchor();panel.setLeft(Value.of(405));panel.setTop(Value.of(canUpgrade?244:353));panel.setWidth(Value.of(800));panel.setHeight(Value.of(canUpgrade?566:348));c.setObject("#DiskPanel.Anchor",panel);
        c.clear("#UpgradeCosts");if(canUpgrade){var cost=upgradeCost(upgrade.tier);for(int i=0;i<cost.size();i++){var material=cost.get(i);String cell="#UpgradeCosts["+i+"]";c.append("#UpgradeCosts","Pages/MegaBenchDisks/UpgradeCost.ui");c.set(cell+" #CostIcon.ItemId",material.getItemId());c.set(cell+" #CostName.Text",name(new ItemStack(material.getItemId(),1)));c.set(cell+" #CostQuantity.Text",material.getQuantity()+" required");}}
        c.set("#UpgradePreview.Text",canUpgrade?DiskLedger.label(upgrade.tier)+" to "+DiskLedger.label(upgrade.tier+1)+" · existing core counts once.\nAll "+upgrade.used()+" stored items remain on this disk.":"");c.set("#ConfirmUpgrade.Disabled",!editable||!canUpgrade||!inv.canRemoveMaterials(upgradeCost(upgrade.tier)));
    }
    @Override public void action(String action,String value){
        try{
            if(action.equals("RackPrevious")||action.equals("RackNext")){if(network!=null)selectRack(network.members().indexOf(rack)+(action.equals("RackNext")?1:-1));return;}
            if(action.equals("ManageDisks")){manageDisks=true;return;}if(action.equals("CloseDisks")){manageDisks=false;upgradeBay=-1;return;}if(action.equals("CancelUpgrade")){upgradeBay=-1;return;}
            if(action.startsWith("SelectBay:")){int bay=Integer.parseInt(action.substring(10));if(bay>=0&&bay<8){selectedBay=bay;selectedSlot=-1;upgradeBay=-1;}return;}
            if(action.equals("Search")){search=value.strip().toLowerCase(Locale.ROOT);if(search.length()>100)search=search.substring(0,100);offset=0;return;}
            if(action.equals("Filter")){var f=value;if(List.of("All","Blocks","Materials","Equipment","Other").contains(f)){filter=f;offset=0;}return;}
            if(action.equals("Backpack")){backpackView=!backpackView;return;}
            if(action.equals("Previous")){offset=Math.max(0,offset-PAGE_SIZE);return;}if(action.equals("Next")){offset+=PAGE_SIZE;return;}
            if(action.startsWith("Inventory:")){int i=Integer.parseInt(action.substring(10));var inv=inventory();if(i<0||i>=inv.getCapacity())return;var item=inv.getItemStack((short)i);if(!ItemStack.isEmpty(item)&&DiskLedger.tier(item)>=0){selectedSlot=i;upgradeBay=-1;manageDisks=true;if(network!=null&&Arrays.stream(rack.bays).allMatch(Objects::nonNull)){for(int n=0;n<network.members().size();n++)if(Arrays.stream(network.members().get(n).bays).anyMatch(Objects::isNull)){selectRack(n);break;}}}return;}
            if(action.startsWith("PreviewUpgrade:")){int bay=Integer.parseInt(action.substring(15));if(rack!=null&&bay>=0&&bay<8&&rack.bays[bay]!=null)upgradeBay=bay;return;}
            writable();
            if(action.equals("Mount"))mount();
            else if(action.startsWith("Eject:")){int bay=Integer.parseInt(action.substring(6));var cartridge=ledger.disk(rack,bay).cartridge();var inv=inventory();if(!inv.canAddItemStack(cartridge,true,true))throw new IllegalArgumentException("Make room for the disk first");var out=ledger.eject(rack,bay,owner());var tx=inv.addItemStack(out,true,true,true);if(!ItemStack.isEmpty(tx.getRemainder())){ledger.admit(rack,bay,owner(),tx.getRemainder());throw new IllegalStateException("Disk returned to bay");}selectedSlot=-1;upgradeBay=-1;notice="Disk ejected with its contents intact.";cue("Handling");}
            else if(action.equals("ConfirmUpgrade")){if(upgradeBay<0)throw new IllegalArgumentException("Select a disk upgrade first");upgrade(upgradeBay);upgradeBay=-1;}
        }catch(IllegalArgumentException|IllegalStateException ex){notice=ex.getMessage();}
    }
    void mount(){var inv=inventory();if(selectedSlot<0||selectedSlot>=inv.getCapacity())throw new IllegalArgumentException("Select a disk in your inventory");short slot=(short)selectedSlot;var item=inv.getItemStack(slot);if(DiskLedger.tier(item)<0)throw new IllegalArgumentException("Selected slot no longer contains a disk");var id=DiskLedger.identity(item);if(id!=null){int copies=0;for(short i=0;i<inv.getCapacity();i++){var other=inv.getItemStack(i);if(DiskLedger.tier(other)>=0&&id.equals(DiskLedger.identity(other)))copies+=other.getQuantity();}if(copies!=1)throw new IllegalArgumentException("Duplicate disk identity in inventory; admission refused");}
        int bay=-1;for(int i=0;i<8;i++)if(rack.bays[i]==null){bay=i;break;}if(bay<0)throw new IllegalArgumentException("This rack is full; select another rack in Disks");
        // Native exact slot removal is checked first; failed admission restores the original cartridge.
        if(!inv.removeItemStackFromSlot(slot,item,1,true,true).succeeded())throw new IllegalArgumentException("Disk inventory slot changed");
        try{ledger.admit(rack,bay,owner(),item.withQuantity(1));}catch(RuntimeException ex){inv.addItemStackToSlot(slot,item.withQuantity(1));throw ex;}selectedSlot=-1;selectedBay=bay;notice="Disk mounted in bay "+(bay+1);cue("Handling");
    }
    static List<MaterialQuantity> upgradeCost(int tier){return List.of(new MaterialQuantity("MBD_Core_"+DiskLedger.label(tier),null,null,3,null),new MaterialQuantity("Ingredient_Bar_Copper",null,null,new int[]{4,8,16}[tier],null),new MaterialQuantity("Ingredient_Bar_Iron",null,null,new int[]{2,4,8}[tier],null));}
    void upgrade(int bay){var disk=ledger.disk(rack,bay);if(disk.tier>=3)throw new IllegalArgumentException("Already 64K");var cost=upgradeCost(disk.tier);var inv=inventory();if(!inv.canRemoveMaterials(cost))throw new IllegalArgumentException("Upgrade needs 3 blank "+DiskLedger.label(disk.tier)+" cores + "+new int[]{4,8,16}[disk.tier]+" copper + "+new int[]{2,4,8}[disk.tier]+" iron");if(!inv.removeMaterials(cost,true,true,true).succeeded())throw new IllegalArgumentException("Native upgrade payment refused");ledger.upgrade(rack,bay,owner());notice="Upgraded to "+DiskLedger.label(disk.tier)+"; existing core counted once, contents retained";cue("Handling");}
    void cue(String family){var sounds=com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent.getAssetMap();String id="MBD_SFX_"+family;if(sounds.getAsset(id)!=null)com.hypixel.hytale.server.core.universe.world.SoundUtil.playSoundEvent2d(context.ref(),sounds.getIndex(id),com.hypixel.hytale.protocol.SoundCategory.UI,context.store());}
}
