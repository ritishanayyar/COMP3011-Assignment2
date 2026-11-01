package comp3011;
import java.util.ArrayList;
import java.util.List;

//builds a list of Frame processors from the effect letters given on the command line.
public class FrameProcessorFactory {

    public static List<FrameProcessor> createChain(List<String> effects) {
        List<FrameProcessor> chain = new ArrayList<>();
        for (String effect : effects) {
            FrameProcessor processor = create(effect);

            //instance index = how many of this effect are already in the chain
            // (first scratcher= 0, second scratcher = 1). It is part of the effect's seed.
            int sameTypeCount = 0;
            for (FrameProcessor existing : chain) {
                if (existing.getClass() == processor.getClass()) {
                    sameTypeCount++;
                }
            }
            processor.setInstanceIndex(sameTypeCount);
            chain.add(processor);
        }
        return chain;
    }

    private static FrameProcessor create(String effect) {
        return switch (effect) {
            case "n" -> new FrameNumberer();
            case "s" -> new FrameScratcher();
            case "f" -> new FrameFlickerer();
            case "w" -> new FrameBlackAndWhiter();
            case "y" -> new FrameYellower();
            case "v" -> new FrameVignetter();
            case "d" -> new FrameDuster();
            case "j" -> new FrameJitterer();
            case "m" -> new FrameMottler();
            case "b" -> new FrameBleeder();
            case "p" -> new FramePepperer();
            default -> throw new IllegalArgumentException("Unknown effect: " + effect);
        };
    }
}