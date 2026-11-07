package org.dextur.reputation.paper;

import java.util.List;
import java.util.stream.IntStream;

/** Dedicated screens reserve one bottom row for consistent navigation. */
final class MenuGeometry {
    private MenuGeometry() { }
    static int size(int items){return Math.min(54,Math.max(18,((Math.max(1,items)+8)/9+1)*9));}
    static List<Integer> content(int size){return IntStream.range(0,size-9).boxed().toList();}
    static int previous(int size){return size-9;}
    static int back(int size){return size-7;}
    static int refresh(int size){return size-5;}
    static int close(int size){return size-3;}
    static int next(int size){return size-1;}
}
