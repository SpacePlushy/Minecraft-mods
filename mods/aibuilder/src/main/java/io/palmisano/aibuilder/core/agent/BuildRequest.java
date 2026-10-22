package io.palmisano.aibuilder.core.agent;

import io.palmisano.aibuilder.core.request.SizeTier;

public record BuildRequest(String prompt, SizeTier size) {
}
