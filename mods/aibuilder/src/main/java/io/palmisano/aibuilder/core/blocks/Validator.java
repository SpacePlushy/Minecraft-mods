package io.palmisano.aibuilder.core.blocks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.palmisano.aibuilder.core.frame.Directions;

/**
 * Checks a block against the catalog and turns its relative-word state into vanilla names and values. Only a
 * property whose vanilla values are compass directions is translated: a door's {@code hinge=left} or a stair's
 * {@code shape=inner_left} is handedness, not a direction.
 */
public final class Validator {
	public static final Set<String> DEFAULT_BANNED = Set.of("minecraft:tnt", "minecraft:spawner", "minecraft:trial_spawner",
			"minecraft:fire", "minecraft:soul_fire", "minecraft:piston_head");

	private final BlockCatalog catalog;
	private final Set<String> bannedIds;

	public Validator(BlockCatalog catalog, Set<String> bannedIds) {
		this.catalog = catalog;
		this.bannedIds = bannedIds;
	}

	public Validation validate(BlockSpec spec) {
		Optional<BlockInfo> found = catalog.find(BlockNames.normalize(spec.id()));
		if (found.isEmpty()) {
			return invalid(RejectReason.UNKNOWN_BLOCK, "\"" + spec.id() + "\" isn't a block; did you mean "
					+ String.join(", ", BlockNames.suggest(catalog.ids(), spec.id(), 5)) + "?");
		}
		BlockInfo info = found.get();
		if (info.unbreakable() || bannedIds.contains(info.id())) {
			return invalid(RejectReason.BANNED_BLOCK, info.id() + " can't be used in builds");
		}
		for (Map.Entry<String, String> entry : spec.state().entrySet()) {
			if (Directions.mentionsCompass(entry.getKey()) || Directions.mentionsCompass(entry.getValue())) {
				return invalid(RejectReason.COMPASS_DIRECTION, entry.getKey() + "=" + entry.getValue()
						+ ": write directions as forward, back, left, right, up, down (relative to the player), not compass names");
			}
		}
		Map<String, String> state = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : spec.state().entrySet()) {
			String name = Directions.toTemplateToken(entry.getKey());
			List<String> allowed = info.properties().get(name);
			if (allowed == null) {
				return invalid(RejectReason.BAD_PROPERTY, info.id() + " has no property \"" + entry.getKey() + "\"; it has "
						+ info.properties().keySet().stream().map(Directions::toLocalToken).collect(Collectors.joining(", ")));
			}
			boolean directional = allowed.stream().anyMatch(Directions::mentionsCompass);
			String value = directional ? Directions.toTemplateValue(entry.getValue()) : entry.getValue();
			if (!allowed.contains(value)) {
				return invalid(RejectReason.BAD_VALUE, info.id() + " " + entry.getKey() + " must be one of "
						+ allowed.stream().map(v -> directional ? Directions.toLocalValue(v) : v).collect(Collectors.joining(", "))
						+ " (got " + entry.getValue() + ")");
			}
			state.put(name, value);
		}
		return new Validation.Valid(new ResolvedBlock(info, state));
	}

	private static Validation invalid(RejectReason reason, String detail) {
		return new Validation.Invalid(new Rejection(reason, detail));
	}
}
