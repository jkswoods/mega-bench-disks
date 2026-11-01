package dev.megabench.disks;

import org.bson.*;

/** Native ItemGrid callback fields. No dynamic UI property reads or client-created ItemStacks. */
record NativeGridEvent(String kind,long revision,int target,int slot,Integer source,int sourceSlot,int quantity,String itemId,int heldQuantity) {
    boolean partialPlacement(){return kind.equals("ClickDrop")&&quantity<heldQuantity;}
    static final String MARKER="MegaBenchDisksGridV1";
    static int integer(BsonDocument data,String key){
        var value=data.get(key);if(value==null||!value.isInt32())throw new IllegalArgumentException("Incomplete native grid event");
        return value.asInt32().getValue();
    }
    static String string(BsonDocument data,String key){
        var value=data.get(key);if(value==null||!value.isString())throw new IllegalArgumentException("Incomplete native grid event");
        return value.asString().getValue();
    }
    static NativeGridEvent read(BsonDocument data){
        if(!MARKER.equals(string(data,"MBDNative")))throw new IllegalArgumentException("Unknown native grid event");
        String kind=string(data,"MBDKind");long revision;
        try{revision=Long.parseLong(string(data,"MBDRevision"));}catch(NumberFormatException ex){throw new IllegalArgumentException("Invalid native grid revision");}
        int target;try{target=Integer.parseInt(string(data,"MBDTarget"));}catch(NumberFormatException ex){throw new IllegalArgumentException("Invalid native grid target");}
        int slot=integer(data,"SlotIndex");if(revision<0||slot<0||slot>Short.MAX_VALUE)throw new IllegalArgumentException("Native grid slot changed");
        if(kind.equals("Select"))return new NativeGridEvent(kind,revision,target,slot,null,-1,0,"",0);
        if(!kind.equals("Drop")&&!kind.equals("ClickDrop"))throw new IllegalArgumentException("Unknown native grid event");
        String prefix=kind.equals("ClickDrop")?"Drag":"";
        // Actual pinned client callbacks use1 for left and3 for right (not the protocol enum).
        int button=kind.equals("ClickDrop")?integer(data,"ClickMouseButton"):1;
        if(button!=1&&button!=3)throw new IllegalArgumentException("Release the dragged stack onto a slot");
        var sourceValue=data.get(prefix+"SourceInventorySectionId");Integer source=sourceValue==null||sourceValue.isNull()?null:integer(data,prefix+"SourceInventorySectionId");
        int sourceSlot=integer(data,prefix+"SourceSlotId"),quantity=integer(data,prefix+"ItemStackQuantity");String id=string(data,prefix+"ItemStackId");
        if(sourceSlot<0||sourceSlot>Short.MAX_VALUE||quantity<1||id.isBlank()||id.length()>256)throw new IllegalArgumentException("Invalid native dragged stack");
        return new NativeGridEvent(kind,revision,target,slot,source,sourceSlot,button==3?1:quantity,id,quantity);
    }
}
