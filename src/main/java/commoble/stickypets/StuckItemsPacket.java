package commoble.stickypets;

import java.util.List;
import java.util.function.Supplier;

import com.mojang.datafixers.util.Pair;

import commoble.stickypets.client.ClientProxy;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkEvent.Context;

/**
 * Server-to-client packet syncing the StuckItemsCapability player capability
 */
public record StuckItemsPacket(int entityId, List<Pair<Vec3,ItemStack>> items)
{
	public static StuckItemsPacket decode(FriendlyByteBuf buf)
	{
		return new StuckItemsPacket(buf.readInt(), buf.readWithCodec(StuckItemsCapability.CODEC));
	}
	
	public void encode(FriendlyByteBuf buf)
	{
		buf.writeInt(this.entityId);
		buf.writeWithCodec(StuckItemsCapability.CODEC, this.items);
	}
	
	public void handle(Supplier<NetworkEvent.Context> contextGetter)
	{
		Context context = contextGetter.get();
		context.enqueueWork(() ->
		{
			ClientProxy.handleStuckItemsPacket(this);
		});
		context.setPacketHandled(true);
	}
}
