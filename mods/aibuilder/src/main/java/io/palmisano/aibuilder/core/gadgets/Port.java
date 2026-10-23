package io.palmisano.aibuilder.core.gadgets;

import io.palmisano.aibuilder.core.frame.Pos;

/**
 * A gadget's port (spec v2 §5.3): {@code at} is the spot a wire's end dust goes, just outside the gadget, with a buffer
 * repeater one step in, an input's carrying the signal in and an output's carrying it out, so a port always takes or
 * gives a full-strength signal and nothing inside the gadget touches a wire. {@code what} says what it carries.
 */
public record Port(String name, boolean input, Pos at, String what) {
}
