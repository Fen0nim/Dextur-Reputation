package org.dextur.reputation.domain;
import java.util.*;
import org.dextur.reputation.domain.recovery.ActivityTracker;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class RecoveryActivityTrackerTest {
 static final long S=1000000000L;
 @Test void connectedAndEligibleActivityAreSeparateAndBounded(){var t=new ActivityTracker(10,3,1);UUID id=UUID.randomUUID();assertTrue(t.join(id,0));assertFalse(t.join(UUID.randomUUID(),0));assertEquals(0,t.sample(id,5*S).activeSeconds());t.meaningful(id,5*S);t.meaningful(id,6*S);t.meaningful(id,7*S);var sample=t.sample(id,30*S);assertEquals(30,sample.connectedSeconds());assertEquals(10,sample.activeSeconds());assertEquals(10,t.sample(id,40*S).activeSeconds());}
 @Test void spamCannotSatisfyMinimumAndIdleWindowsReset(){var t=new ActivityTracker(10,3,5);UUID id=UUID.randomUUID();t.join(id,0);for(int n=0;n<1000;n++)t.meaningful(id,n);assertEquals(0,t.sample(id,2*S).activeSeconds());t.meaningful(id,20*S);t.meaningful(id,21*S);assertEquals(0,t.sample(id,22*S).activeSeconds());t.meaningful(id,22*S);assertEquals(1,t.sample(id,23*S).activeSeconds());}
 @Test void meaningfulDistanceAndTeleportFiltering(){var t=new ActivityTracker(10,1,5);UUID id=UUID.randomUUID();t.join(id,0);for(int n=0;n<10;n++)t.movement(id,n*S,0.1,0);assertEquals(0,t.sample(id,10*S).activeSeconds());t.movement(id,11*S,100,0);assertEquals(0,t.sample(id,12*S).activeSeconds());for(int n=0;n<20;n++)t.movement(id,(13+n)*S,100+n*0.2,0);assertTrue(t.sample(id,34*S).activeSeconds()>0);}
 @Test void reconnectUsesNewSessionAndNoHistoricalActivity(){var t=new ActivityTracker(10,1,5);UUID id=UUID.randomUUID();t.join(id,0);t.meaningful(id,S);var first=t.sample(id,5*S);t.quit(id);assertNull(t.sample(id,6*S));t.join(id,100*S);var next=t.sample(id,101*S);assertNotEquals(first.session(),next.session());assertEquals(1,next.connectedSeconds());assertEquals(0,next.activeSeconds());t.close();assertEquals(0,t.size());}
 @Test void monotonicRegressionIsRejected(){var t=new ActivityTracker(10,1,5);UUID id=UUID.randomUUID();t.join(id,5*S);assertThrows(IllegalArgumentException.class,()->t.sample(id,S));}
}
