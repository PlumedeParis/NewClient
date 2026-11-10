package dev.zero.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** A set of blocks, edited through the searchable block picker screen. */
public class BlockListSetting extends Setting<Set<Block>> {
	public BlockListSetting(String name, String description, Collection<Block> defaults) {
		super(name, description, new LinkedHashSet<>(defaults));
	}

	@Override protected Set<Block> copyDefault() { return new LinkedHashSet<>(getDefault()); }

	public boolean contains(Block block) { return value.contains(block); }

	public void toggle(Block block) {
		Set<Block> copy = new LinkedHashSet<>(value);
		if (!copy.remove(block)) copy.add(block);
		set(copy);
	}

	@Override
	public JsonElement toJson() {
		JsonArray array = new JsonArray();
		for (Block b : value) array.add(BuiltInRegistries.BLOCK.getKey(b).toString());
		return array;
	}

	@Override
	public void fromJson(JsonElement element) {
		if (!element.isJsonArray()) return;
		Set<Block> set = new LinkedHashSet<>();
		for (JsonElement e : element.getAsJsonArray()) {
			Identifier id = Identifier.tryParse(e.getAsString());
			if (id != null) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(set::add);
		}
		value = set;
	}
}
