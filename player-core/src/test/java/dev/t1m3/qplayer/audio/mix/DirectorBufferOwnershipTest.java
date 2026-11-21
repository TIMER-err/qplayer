package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.*;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.studio.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

/** Deliberately partial transition writes expose buffers retained across calls. */
public class DirectorBufferOwnershipTest {
    private static final int RATE=12000;
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private Decoder.Opener saved;
    private final List<String> paths=new ArrayList<>();
    @Before public void setup() throws Exception {
        Field f=Decoder.Holder.class.getDeclaredField("opener");f.setAccessible(true);
        saved=(Decoder.Opener)f.get(null);Decoder.install(TestWavFile.opener());
        for(int k=1;k<=3;k++) {
            float[] x=new float[RATE*3];Arrays.fill(x,k*.1f);
            if(k==2)Arrays.fill(x,RATE*2,x.length,.6f);
            paths.add(TestWavFile.write(tmp.getRoot().toPath().resolve(k+".wav"),x,x,RATE).toString());
        }
    }
    @After public void restore(){Decoder.install(saved);}
    private Director director(List<Float> starts) {
        return new Director(paths,RATE,2,()->new Transition(){
            boolean first=true;
            public long windowFrames(int r){return 5000;}
            public String name(){return "partial writer";}
            public boolean rewritesSamples(){return true;}
            public TransitionPlan prepare(String a,String b,int r){return new TransitionPlan(0,PitchPlan.ZERO,0,"fixture",.5);}
            public int render(AudioBuf out,AudioBuf a,AudioBuf b,float p){
                if(first){starts.add(a.backingArray()[0]);first=false;}
                int n=Math.min(777,Math.min(out.capacityFrames(),b.frames()));
                for(int c=0;c<2;c++)System.arraycopy(b.backingArray(),c*b.capacityFrames(),out.backingArray(),c*out.capacityFrames(),n);
                out.setFrames(n);return n;
            }
        });
    }
    private static AudioBuf buffer(){return new AudioBuf(ChannelLayout.STEREO,Deck.RENDER_FRAMES);}
    private static void ready(Director d) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!d.collectPreparation()&&System.nanoTime()<deadline)Thread.sleep(2);
        assertTrue("preparation ready",d.collectPreparation());
    }
    private static void firstHandoff(Director d) throws Exception {
        AudioBuf b=buffer();assertTrue(d.pump(b)>0);ready(d);
        boolean began=false;
        for(int i=0;i<100;i++) {
            assertTrue(d.pump(b)>0);began|=d.inTransition();
            if(began&&!d.inTransition())return;
        }
        fail("handoff never completed");
    }
    @Test public void seekAfterHandoffDoesNotReplayOldIncomingCarry() throws Exception {
        Director d=director(new ArrayList<>());firstHandoff(d);
        d.seek(2.5);AudioBuf b=buffer();assertTrue(d.pump(b)>0);
        assertEquals("seek must immediately deliver the requested region",.6,b.backingArray()[0],4e-5);
        ready(d);
    }
    @Test public void skipAfterHandoffDoesNotLeakPreviousSong() throws Exception {
        Director d=director(new ArrayList<>());firstHandoff(d);
        assertTrue(d.skipNext());AudioBuf b=buffer();assertTrue(d.pump(b)>0);
        assertEquals("first sample belongs to track C",.3,b.backingArray()[0],4e-5);
    }
    @Test public void secondTransitionDoesNotReuseFirstOutgoingBuffer() throws Exception {
        List<Float> starts=new ArrayList<>();Director d=director(starts);firstHandoff(d);
        AudioBuf b=buffer();d.pump(b);ready(d);
        for(int i=0;i<100&&starts.size()<2;i++)assertTrue(d.pump(b)>0);
        assertEquals(2,starts.size());assertEquals(.1,starts.get(0),4e-5);
        assertEquals("second outgoing material is B at its ending, not cached A",.6,starts.get(1),4e-5);
    }
    @Test public void processedCarryIsNotProcessedAgainAfterHandoff() throws Exception {
        Director d=director(new ArrayList<>());
        StudioProcessor s=new StudioProcessor(RATE,2);
        s.setRacks(StudioRacks.EMPTY.withDeckA(StudioRack.EMPTY.withEnabled(true).withPolarityInvert(true)));
        d.setStudio(s);firstHandoff(d);
        AudioBuf b=buffer();assertTrue(d.pump(b)>0);
        assertEquals("B carry already passed its own rack; A rack must not invert it",.2,b.backingArray()[0],4e-5);
        assertTrue(d.pump(b)>0); // carry may occupy an entire first block
        assertTrue("fresh current-deck samples still use A rack",b.backingArray()[b.frames()-1]<0);
        ready(d);
    }
    @Test public void hardHandoffFirstBlockStillPassesTheCurrentRack() {
        Director d=new Director(paths.subList(0,2),RATE,2,()->new Crossfade(0));
        StudioProcessor s=new StudioProcessor(RATE,2);
        s.setRacks(StudioRacks.EMPTY.withDeckA(StudioRack.EMPTY.withEnabled(true).withPolarityInvert(true)));
        d.setStudio(s);AudioBuf b=buffer();boolean switched=false;
        for(int i=0;i<100;i++) {
            assertTrue(d.pump(b)>0);
            if(d.currentPath().equals(paths.get(1))) {
                assertEquals("first block after hard handoff must not bypass DSP",-.2,b.backingArray()[0],4e-5);
                switched=true;break;
            }
        }
        assertTrue(switched);
    }
}
