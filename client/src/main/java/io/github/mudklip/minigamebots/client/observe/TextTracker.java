package io.github.mudklip.minigamebots.client.observe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Keeps the last few chat lines (chat and system messages, not the action bar). */
public final class TextTracker {
	private static final int MAX_LINES = 20;
	private final Deque<String> chat = new ArrayDeque<>();

	public synchronized void onChat(String line) {
		chat.addLast(line);
		while (chat.size() > MAX_LINES) {
			chat.pollFirst();
		}
	}

	public synchronized List<String> recent() {
		return new ArrayList<>(chat);
	}

	public synchronized void clear() {
		chat.clear();
	}
}
