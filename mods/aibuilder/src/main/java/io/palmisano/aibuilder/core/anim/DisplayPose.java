package io.palmisano.aibuilder.core.anim;

/** What one display is told: the translation and the rotation it draws its block's cube with, from its own corner. */
public record DisplayPose(Vec3 translation, Quat rotation) {
}
