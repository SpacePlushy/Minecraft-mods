package io.palmisano.aibuilder.core.blocks;

public sealed interface Validation permits Validation.Valid, Validation.Invalid {
	record Valid(ResolvedBlock block) implements Validation {
	}

	record Invalid(Rejection rejection) implements Validation {
	}
}
