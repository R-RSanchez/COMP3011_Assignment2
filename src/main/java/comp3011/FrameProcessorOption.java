package comp3011;

import java.util.function.Supplier;

/**
 * Defines the frame processor options supported by the command line.
 *
 * <p>
 * Each option knows its short form, long form and how to create the
 * corresponding {@link FrameProcessor}. Keeping this mapping in one place
 * makes it straightforward to add future frame processors without spreading
 * creation logic throughout the command-line parser.
 * </p>
 */
public enum FrameProcessorOption {

    NUMBER('n', "--number-frames", FrameNumberer::new),
    SCRATCH('s', "--scratch-frames", FrameScratcher::new),
    FLICKER('f', "--flicker-frames", FrameFlickerer::new),
    BLACK_AND_WHITE('w', "--black-and-white", FrameBlackAndWhiter::new),
    YELLOW('y', "--yellow-frames", FrameYellower::new),
    VIGNETTE('v', "--vignette-frames", FrameVignetter::new),
    DUST('d', "--dust-frames", FrameDuster::new),
    JITTER('j', "--jitter-frames", FrameJitterer::new),
    MOTTLE('m', "--mottle-frames", FrameMottler::new),
    BLEED('b', "--bleed-frames", FrameBleeder::new),
    PEPPER('p', "--pepper-frames", FramePepperer::new);

    private final char shortOption;
    private final String longOption;
    private final Supplier<FrameProcessor> processorFactory;

    FrameProcessorOption(
            char shortOption,
            String longOption,
            Supplier<FrameProcessor> processorFactory) {

        this.shortOption = shortOption;
        this.longOption = longOption;
        this.processorFactory = processorFactory;
    }

    public char getShortOption() {
        return shortOption;
    }

    public String getLongOption() {
        return longOption;
    }

    public FrameProcessor createProcessor() {
        return processorFactory.get();
    }

    public static FrameProcessorOption fromShortOption(char option) {
        for (FrameProcessorOption processorOption : values()) {
            if (processorOption.shortOption == option) {
                return processorOption;
            }
        }

        return null;
    }

    public static FrameProcessorOption fromLongOption(String option) {
        for (FrameProcessorOption processorOption : values()) {
            if (processorOption.longOption.equals(option)) {
                return processorOption;
            }
        }

        return null;
    }
}