package dev.megabench.disks;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.inventory.transaction.ClearTransaction;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Short native view of real ledger entries. Only native debits mutate it; no copied recipe engine. */
final class DiskSource extends SimpleItemContainer {
    final DiskLedger ledger;final DiskLedger.RackAccess rack;final List<DiskLedger.Entry> slots;final BooleanSupplier valid;final List<ItemStack> originals;
    DiskSource(DiskLedger ledger,DiskLedger.RackAccess rack,List<DiskLedger.Entry> selected,BooleanSupplier valid){
        super((short)selected.size());this.ledger=ledger;this.rack=rack;slots=List.copyOf(selected);originals=selected.stream().map(e->DiskLedger.copy(e.stack)).toList();this.valid=valid;
        for(short i=0;i<getCapacity();i++)super.internal_setSlot(i,DiskLedger.copy(slots.get(i).stack));
    }
    static ItemContainer forCosts(DiskLedger ledger,DiskLedger.RackAccess rack,List<MaterialQuantity> costs,BooleanSupplier valid){
        var slots=new ArrayList<DiskLedger.Entry>();
        for(var disk:ledger.mounted(rack))for(var entry:disk.entries){
            if(ItemStack.isEmpty(entry.stack))continue;
            var single=new SimpleItemContainer((short)1);single.setItemStackForSlot((short)0,DiskLedger.copy(entry.stack));
            if(costs.stream().anyMatch(c->single.countRemovableMaterial(c)>0))slots.add(entry);
        }
        if(slots.size()>32700)throw new IllegalArgumentException("Too many matching item variants for one native craft; withdraw some variants first");
        return slots.isEmpty()?EmptyItemContainer.INSTANCE:new DiskSource(ledger,rack,slots,valid);
    }
    @Override protected boolean cantAddToSlot(short slot,ItemStack item,ItemStack current){return true;}
    @Override protected boolean cantRemoveFromSlot(short slot){return !valid.getAsBoolean();}
    @Override protected boolean cantDropFromSlot(short slot){return true;}
    @Override protected ItemStack internal_setSlot(short slot,ItemStack item){
        var prior=originals.get(slot);
        if(!valid.getAsBoolean()||(!ItemStack.isEmpty(item)&&(ItemStack.isEmpty(prior)||!prior.isStackableWith(item)||item.getQuantity()>prior.getQuantity())))throw new IllegalStateException("Disk source changed or write refused");
        slots.get(slot).stack=DiskLedger.copy(item);ledger.touch(rack);
        // SimpleItemContainer.setSlot(empty) dispatches back to internal_removeSlot.
        // Bypass that virtual round trip when a cross-rack payment exhausts an entry.
        return ItemStack.isEmpty(item)?super.internal_removeSlot(slot):super.internal_setSlot(slot,DiskLedger.copy(item));
    }
    @Override protected ItemStack internal_removeSlot(short slot){return internal_setSlot(slot,null);}
    @Override protected ClearTransaction internal_clear(){throw new UnsupportedOperationException("Use the storage withdrawal controls");}
    @Override public SimpleItemContainer clone(){var result=new SimpleItemContainer(getCapacity());for(short i=0;i<getCapacity();i++)result.setItemStackForSlot(i,DiskLedger.copy(getItemStack(i)));return result;}
}
