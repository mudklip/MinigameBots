package io.github.mudklip.minigamebots.client.record;

import io.github.mudklip.minigamebots.client.control.GameInput;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import io.github.mudklip.minigamebots.client.observe.Observation;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One tick of a recording: what was seen before acting, and what was done.
 *
 * @param tick         tick number within the recording, from 0
 * @param input        the input during this tick (keys, clicks, camera)
 * @param observation  what was known at the end of the previous tick
 * @param events       events detected during the previous tick
 * @param frameJpeg    the frame as JPEG, or null when the frame didn't change since the last record
 * @param frameAge     how many ticks old the frame is (0 = fresh)
 * @param cameraJump   true when a teleport/respawn turn was removed from the camera input
 * @param marks        marks placed during this tick
 */
public record TickRecord(int tick, GameInput input, @Nullable Observation observation, List<GameEvent> events,
		byte @Nullable [] frameJpeg, int frameAge, boolean cameraJump, List<String> marks) {
}
