package org.dextur.reputation.domain.recovery;
import java.util.*;
/** Main-adapter-owned bounded monotonic tracker. Activity events are coalesced into meaningful windows. */
public final class ActivityTracker {
    public record Sample(UUID session,long connectedSeconds,long activeSeconds) { }
    private static final long SECOND=1_000_000_000L;
    private final long timeout;private final int minimum,maximum;private final Map<UUID,Entry> entries=new HashMap<>();
    private static final class Entry {final UUID session=UUID.randomUUID();long last,lastActivity,connected,active,lastSignal;int signals;double x,z,distance;boolean positioned;Entry(long now){last=now;lastActivity=Long.MIN_VALUE;lastSignal=now-SECOND;}}
    public ActivityTracker(long timeoutSeconds,int minimum,int maximum){if(timeoutSeconds<1||minimum<1||maximum<1)throw new IllegalArgumentException();timeout=Math.multiplyExact(timeoutSeconds,SECOND);this.minimum=minimum;this.maximum=maximum;}
    public boolean join(UUID id,long now){if(entries.containsKey(id))return true;if(entries.size()>=maximum)return false;entries.put(id,new Entry(now));return true;}
    public void meaningful(UUID id,long now){var e=entries.get(id);if(e==null||now<e.lastSignal||now-e.lastSignal<SECOND)return;accrue(e,now);if(now-e.lastSignal>timeout)e.signals=0;e.lastSignal=now;if(++e.signals>=minimum){e.lastActivity=now;e.signals=0;}}
    public void movement(UUID id,long now,double x,double z){var e=entries.get(id);if(e==null||!Double.isFinite(x)||!Double.isFinite(z))return;if(e.positioned){double distance=Math.hypot(x-e.x,z-e.z);if(distance<=8)e.distance+=distance;else e.distance=0;if(e.distance>=3){e.distance=0;meaningful(id,now);}}e.x=x;e.z=z;e.positioned=true;}
    public Sample sample(UUID id,long now){var e=entries.get(id);if(e==null)return null;accrue(e,now);return new Sample(e.session,e.connected/SECOND,e.active/SECOND);}
    private void accrue(Entry e,long now){if(now<e.last)throw new IllegalArgumentException("Monotonic clock regression");long delta=now-e.last;e.connected=Math.addExact(e.connected,delta);if(e.lastActivity!=Long.MIN_VALUE){long end=e.lastActivity>Long.MAX_VALUE-timeout?Long.MAX_VALUE:e.lastActivity+timeout;long eligible=Math.max(0,Math.min(now,end)-e.last);e.active=Math.addExact(e.active,Math.min(delta,eligible));}e.last=now;}
    public void quit(UUID id){entries.remove(id);}public void close(){entries.clear();}public int size(){return entries.size();}
}
