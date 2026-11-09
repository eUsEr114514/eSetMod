package com.electrondz75.setmod.state;

import java.util.List;

/**
 * A recording that was saved under a name with {@code /setmod name <name>}.
 *
 * @param blocks        the recorded blocks, immutable and in click order
 * @param durationTicks total animation length in ticks, {@link #AUTOMATIC_DURATION} when the user
 *                      never ran {@code /setmod speed <name> <ticks>}
 */
public record Recording(List<RecordedBlock> blocks, int durationTicks) {
	/** Marker for "no explicit duration": fall back to one tick per block. */
	public static final int AUTOMATIC_DURATION = 0;

	public Recording {
		blocks = List.copyOf(blocks);
	}

	public boolean hasCustomDuration() {
		return durationTicks > 0;
	}

	public Recording withDuration(int ticks) {
		return new Recording(blocks, ticks);
	}
}
