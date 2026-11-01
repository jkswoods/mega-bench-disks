package dev.megabench.disks;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.*;
import java.util.*;

/** One browser row per native-stackable variant, even when its contents span disks. */
final class StorageItems {
    record Variant(ItemStack prototype,List<DiskLedger.Entry> entries) {
        int quantity(){return entries.stream().filter(e->!ItemStack.isEmpty(e.stack)).mapToInt(e->e.stack.getQuantity()).sum();}
        ItemStack display(){return prototype.withQuantity(quantity());}
    }
    static List<Variant> variants(DiskLedger ledger,DiskLedger.RackAccess rack){
        var result=new LinkedHashMap<ItemStack,Variant>();if(rack==null)return List.of();
        for(var disk:ledger.mounted(rack))for(var entry:disk.entries){
            if(ItemStack.isEmpty(entry.stack))continue;
            // Pinned native equals/hashCode use the same variant fields as isStackableWith, plus quantity.
            var key=entry.stack.withQuantity(1);var target=result.get(key);
            if(target==null){target=new Variant(DiskLedger.copy(key),new ArrayList<>());result.put(target.prototype,target);}
            target.entries.add(entry);
        }
        return new ArrayList<>(result.values());
    }
    static Variant find(List<Variant> variants,ItemStack prototype){if(!ItemStack.isEmpty(prototype))for(var v:variants)if(v.prototype.isStackableWith(prototype))return v;return null;}
    static String category(ItemStack stack){
        var item=stack.getItem();
        if(item.getTool()!=null||item.getWeapon()!=null||item.getArmor()!=null||item.getGlider()!=null)return "Equipment";
        if(item.hasBlockType())return "Blocks";
        String categories=item.getCategories()==null?"":String.join(" ",item.getCategories()).toLowerCase(Locale.ROOT);
        if(item.getResourceTypes()!=null&&item.getResourceTypes().length>0||categories.contains("ingredient")||categories.contains("material"))return "Materials";
        return "Other";
    }
    static int deposit(DiskLedger ledger,DiskLedger.RackAccess rack,UUID owner,ItemContainer inventory,short slot,ItemStack expected,int requested){
        ledger.mutable(rack,owner);var original=inventory.getItemStack(slot);
        if(ItemStack.isEmpty(original)||!original.isStackableWith(expected))throw new IllegalArgumentException("Inventory selection changed; select the item again");
        DiskLedger.storable(original);int n=Math.min(Math.min(requested,original.getQuantity()),ledger.capacity(rack)-ledger.used(rack));
        if(n<=0)throw new IllegalArgumentException("The rack is full; nothing deposited");
        var part=DiskLedger.copy(original.withQuantity(n));
        if(!inventory.removeItemStackFromSlot(slot,original,n,true,true).succeeded())throw new IllegalArgumentException("Inventory changed; nothing deposited");
        ledger.deposit(rack,owner,part);return n;
    }
    static int withdraw(DiskLedger ledger,DiskLedger.RackAccess rack,UUID owner,ItemContainer inventory,ItemStack prototype,int requested){
        ledger.mutable(rack,owner);var variant=find(variants(ledger,rack),prototype);
        if(variant==null)throw new IllegalArgumentException("This item is no longer stored");
        int n=Math.min(requested,variant.quantity());var out=DiskLedger.copy(variant.prototype.withQuantity(n));
        if(!inventory.canAddItemStack(out,true,true))throw new IllegalArgumentException("Make inventory space or choose a smaller quantity; nothing withdrawn");
        int left=n;for(var entry:variant.entries){int count=Math.min(left,entry.stack.getQuantity());if(count>0)ledger.withdraw(rack,owner,entry,count);left-=count;if(left==0)break;}
        var tx=inventory.addItemStack(out,true,true,true);var remainder=tx.getRemainder();
        // A native destination refusal must restore only the unaccepted remainder, never duplicate accepted items.
        if(!ItemStack.isEmpty(remainder)){ledger.deposit(rack,owner,remainder);n-=remainder.getQuantity();}
        if(n==0)throw new IllegalArgumentException("Native inventory refused the transfer; items remain stored");
        return n;
    }
    /** Native drag targets a real slot; quick move fills only the space that is actually available. */
    static int withdrawFitting(DiskLedger ledger,DiskLedger.RackAccess rack,UUID owner,ItemContainer inventory,short slot,ItemStack prototype,int requested){
        ledger.mutable(rack,owner);if(requested<1)throw new IllegalArgumentException("Invalid transfer quantity");
        var variant=find(variants(ledger,rack),prototype);if(variant==null)throw new IllegalArgumentException("This item is no longer stored");
        if(slot<-1||slot>=inventory.getCapacity())throw new IllegalArgumentException("Inventory slot changed");
        int limit=Math.min(requested,variant.quantity());if(slot>=0)limit=Math.min(limit,variant.prototype.getItem().getMaxStack());
        // The pinned exact-slot canAdd check rejects positive queries on occupied slots. Simulate native insertion on isolated real-slot copies instead.
        var isolated=copy(inventory,new IdentityHashMap<>());var query=DiskLedger.copy(variant.prototype.withQuantity(limit));
        var unused=slot<0?isolated.addItemStack(query,false,false,true).getRemainder():isolated.addItemStackToSlot(slot,query,false,true).getRemainder();
        int n=limit-(ItemStack.isEmpty(unused)?0:unused.getQuantity());if(n<=0)throw new IllegalArgumentException("Choose an empty or matching inventory slot with room");
        var out=DiskLedger.copy(variant.prototype.withQuantity(n));int left=n;
        for(var entry:variant.entries){int count=Math.min(left,entry.stack.getQuantity());if(count>0)ledger.withdraw(rack,owner,entry,count);left-=count;if(left==0)break;}
        var remainder=slot<0?inventory.addItemStack(out,false,false,true).getRemainder():inventory.addItemStackToSlot(slot,out,false,true).getRemainder();
        if(!ItemStack.isEmpty(remainder)){ledger.deposit(rack,owner,remainder);n-=remainder.getQuantity();}
        if(n==0)throw new IllegalArgumentException("Native inventory refused the transfer; items remain stored");
        return n;
    }
    private static ItemContainer copy(ItemContainer original,IdentityHashMap<ItemContainer,ItemContainer> copies){
        var existing=copies.get(original);if(existing!=null)return existing;ItemContainer result;
        if(original.getCapacity()==0)result=EmptyItemContainer.INSTANCE;
        else if(original instanceof CombinedItemContainer combined){var parts=new ItemContainer[combined.getContainersSize()];for(int i=0;i<parts.length;i++)parts[i]=copy(combined.getContainer(i),copies);result=new CombinedItemContainer(parts);}
        else if(original.getClass()==SimpleItemContainer.class){var isolated=new SimpleItemContainer((SimpleItemContainer)original);for(short i=0;i<original.getCapacity();i++)isolated.setItemStackForSlot(i,DiskLedger.copy(original.getItemStack(i)),false);result=isolated;}
        else if(original==EmptyItemContainer.INSTANCE)result=EmptyItemContainer.INSTANCE;
        else throw new IllegalArgumentException("This inventory layout does not support native transfers");
        copies.put(original,result);return result;
    }
}
