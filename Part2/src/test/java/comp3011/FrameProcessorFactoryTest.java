package comp3011;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.List;

public class FrameProcessorFactoryTest {

    @Test
    public void testChainKeepsOrderAndRepeats() {
        List<FrameProcessor> chain = FrameProcessorFactory.createChain(List.of("n", "s", "s"));
        assertEquals(3, chain.size());
        assertEquals(FrameNumberer.class, chain.get(0).getClass());
        assertEquals(FrameScratcher.class, chain.get(2).getClass());
    }

    @Test
    public void testRepeatedEffectsGetDifferentIndexes() {
        List<FrameProcessor> chain = FrameProcessorFactory.createChain(List.of("s", "f", "s"));
        assertEquals(0, chain.get(0).instanceIndex());
        assertEquals(0, chain.get(1).instanceIndex());
        assertEquals(1, chain.get(2).instanceIndex());
    }

    @Test
    public void testEachCallMakesNewObjects() {
        //each worker thread will get its own chain, so chains must not share objects
        FrameProcessor a = FrameProcessorFactory.createChain(List.of("n")).get(0);
        FrameProcessor b = FrameProcessorFactory.createChain(List.of("n")).get(0);
        assertNotSame(a, b);
    }
}