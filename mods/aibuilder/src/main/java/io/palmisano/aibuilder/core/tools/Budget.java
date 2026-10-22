package io.palmisano.aibuilder.core.tools;

/** The build's tool-call budget, and the reminder Claude sees as it runs low. */
public final class Budget {
	private final int maxToolCalls;
	private int used;

	public Budget(int maxToolCalls) {
		this.maxToolCalls = maxToolCalls;
	}

	public void count() {
		used++;
	}

	public boolean exhausted() {
		return used > maxToolCalls;
	}

	/** Empty with a quarter or more left; then the calls left; below a tenth, "finish now". */
	public String footer() {
		int left = maxToolCalls - used;
		if (left < 0) {
			return "";
		}
		if (left < maxToolCalls * 0.10) {
			return "Budget nearly spent: finish now.";
		}
		if (left < maxToolCalls * 0.25) {
			return "Budget: " + left + (left == 1 ? " tool call left." : " tool calls left.");
		}
		return "";
	}
}
