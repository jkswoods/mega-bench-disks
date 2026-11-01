package dev.megabench.disks;
import java.util.*;
public final class RackConnectivityTest {
 static void check(boolean ok){if(!ok)throw new AssertionError("Rack connectivity contract failed");}
 public static void main(String[] args){
  var bench=new RackNetwork.Position(0,64,0);var blocks=new HashMap<RackNetwork.Position,String>();blocks.put(bench,"MB_MegaBench");
  var low=new RackNetwork.Position(1,64,0);var bridge=new RackNetwork.Position(1,65,0);var high=new RackNetwork.Position(1,66,0);
  for(var at:List.of(low,bridge,high))blocks.put(at,"MBD_DriveRack");
  check(RackNetwork.fromBench(bench,p->blocks.getOrDefault(p,""),p->true).racks().equals(List.of(low,bridge,high)));
  check(RackNetwork.benchFromRack(high,p->blocks.getOrDefault(p,""),p->true).equals(bench));
  check(RackNetwork.fromBench(bench,p->blocks.getOrDefault(p,""),p->!p.equals(bridge)).racks().equals(List.of(low)));
  blocks.remove(bridge);check(RackNetwork.fromBench(bench,p->blocks.getOrDefault(p,""),p->true).racks().equals(List.of(low)));
  blocks.clear();blocks.put(bench,"MB_MegaBench");blocks.put(new RackNetwork.Position(1,65,0),"MBD_DriveRack");
  check(RackNetwork.fromBench(bench,p->blocks.getOrDefault(p,""),p->true).racks().isEmpty());
  for(int y=64;y<80;y++)blocks.put(new RackNetwork.Position(1,y,0),"MBD_DriveRack");
  check(RackNetwork.fromBench(bench,p->blocks.getOrDefault(p,""),p->true).racks().size()==16);
  blocks.put(new RackNetwork.Position(1,80,0),"MBD_DriveRack");var overflow=RackNetwork.fromBench(bench,p->blocks.getOrDefault(p,""),p->true);
  check(overflow.overflow()&&overflow.racks().isEmpty());System.out.println("PASS 7 rack connectivity contracts");
 }
}
