package dev.megabench.disks;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Bounded, loaded-block-only connectivity. Diagonals and foreign racks are never links. */
final class RackNetwork {
    static final int MAX_RACKS=16;
    static final int[][] DIRECTIONS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    record Position(int x,int y,int z) {
        Position offset(int[] d){return new Position(x+d[0],y+d[1],z+d[2]);}
        String key(){return DisksPlugin.key(x,y,z);}
    }
    record Scan(List<Position> racks,boolean overflow) {}
    static Scan fromBench(Position bench,Function<Position,String> block,Predicate<Position> allowed){
        var seeds=new ArrayList<Position>();for(var d:DIRECTIONS)seeds.add(bench.offset(d));
        return scan(seeds,block,allowed);
    }
    static Scan fromRack(Position rack,Function<Position,String> block,Predicate<Position> allowed){return scan(List.of(rack),block,allowed);}
    private static Scan scan(List<Position> seeds,Function<Position,String> block,Predicate<Position> allowed){
        var queue=new ArrayDeque<>(seeds);var visited=new HashSet<Position>();var racks=new ArrayList<Position>();
        while(!queue.isEmpty()){
            var at=queue.removeFirst();if(!visited.add(at)||!block.apply(at).equals("MBD_DriveRack")||!allowed.test(at))continue;
            racks.add(at);if(racks.size()>MAX_RACKS)return new Scan(List.of(),true);
            for(var d:DIRECTIONS)queue.addLast(at.offset(d));
        }
        // Stable addresses across openings and graph cycles, independent of which rack was used.
        racks.sort(Comparator.comparingInt(Position::y).thenComparingInt(Position::x).thenComparingInt(Position::z));
        return new Scan(List.copyOf(racks),false);
    }
    static Position benchFromRack(Position rack,Function<Position,String> block,Predicate<Position> allowed){
        var scan=fromRack(rack,block,allowed);if(scan.overflow())return null;
        for(var at:scan.racks())for(var d:DIRECTIONS){var next=at.offset(d);if(block.apply(next).equals("MB_MegaBench"))return next;}
        return null;
    }
}
