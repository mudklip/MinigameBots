package io.github.mudklip.minigamebots.client.gui;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** A slider for a number between min and max, snapped to step, showing "Label: value". */
public final class ValueSlider extends AbstractSliderButton {
	private final String label;
	private final double min;
	private final double max;
	private final double step;
	private final DoubleFunction<String> format;
	private final DoubleConsumer onChange;

	public ValueSlider(int width, String label, double min, double max, double step, double value,
			DoubleFunction<String> format, DoubleConsumer onChange) {
		super(0, 0, width, 20, Component.empty(), (value - min) / (max - min));
		this.label = label;
		this.min = min;
		this.max = max;
		this.step = step;
		this.format = format;
		this.onChange = onChange;
		updateMessage();
	}

	public double get() {
		double raw = min + value * (max - min);
		return Math.max(min, Math.min(max, Math.round(raw / step) * step));
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal(label + ": " + format.apply(get())));
	}

	@Override
	protected void applyValue() {
		onChange.accept(get());
	}
}
