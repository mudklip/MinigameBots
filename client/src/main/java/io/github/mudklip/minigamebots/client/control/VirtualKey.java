package io.github.mudklip.minigamebots.client.control;

/**
 * Every key the AI can press. Each maps to one of Minecraft's own key bindings, so the AI
 * respects whatever the player has bound and vanilla handles the rest.
 *
 * <p>The set matches OpenAI's VPT action space so its pretrained models can be used.
 */
public enum VirtualKey {
	FORWARD(Kind.HOLD),
	BACK(Kind.HOLD),
	LEFT(Kind.HOLD),
	RIGHT(Kind.HOLD),
	JUMP(Kind.HOLD),
	SNEAK(Kind.HOLD),
	SPRINT(Kind.HOLD),
	/** Left click. In a GUI it clicks at the virtual cursor. */
	ATTACK(Kind.HOLD_AND_CLICK),
	/** Right click. In a GUI it clicks at the virtual cursor. */
	USE(Kind.HOLD_AND_CLICK),
	PICK(Kind.CLICK),
	DROP(Kind.CLICK),
	SWAP_OFFHAND(Kind.CLICK),
	INVENTORY(Kind.CLICK),
	HOTBAR_1(Kind.CLICK),
	HOTBAR_2(Kind.CLICK),
	HOTBAR_3(Kind.CLICK),
	HOTBAR_4(Kind.CLICK),
	HOTBAR_5(Kind.CLICK),
	HOTBAR_6(Kind.CLICK),
	HOTBAR_7(Kind.CLICK),
	HOTBAR_8(Kind.CLICK),
	HOTBAR_9(Kind.CLICK);

	public enum Kind {
		/** Matters while held (movement). */
		HOLD,
		/** Matters while held, and each press is also a click (attack, use). */
		HOLD_AND_CLICK,
		/** Only the press matters (hotbar, inventory). */
		CLICK
	}

	private final Kind kind;

	VirtualKey(Kind kind) {
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	public boolean clicks() {
		return kind != Kind.HOLD;
	}

	public static VirtualKey hotbar(int slot) {
		if (slot < 0 || slot > 8) {
			throw new IllegalArgumentException("hotbar slot " + slot);
		}
		return values()[HOTBAR_1.ordinal() + slot];
	}

	/** 0-8 for hotbar keys, -1 otherwise. */
	public int hotbarSlot() {
		int i = ordinal() - HOTBAR_1.ordinal();
		return i >= 0 && i < 9 ? i : -1;
	}
}
