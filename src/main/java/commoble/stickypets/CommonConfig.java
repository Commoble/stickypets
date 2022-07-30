package commoble.stickypets;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;

public record CommonConfig(IntValue maxStuckItems)
{
	public static CommonConfig create(ForgeConfigSpec.Builder builder)
	{
		return new CommonConfig(
			builder.comment("Maximum number of items that can be stuck to pets. 0 => can't stick items to pets")
				.defineInRange("max_stuck_items", 3, 0, Integer.MAX_VALUE));
	}
}
