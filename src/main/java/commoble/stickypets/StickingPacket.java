package commoble.stickypets;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkEvent.Context;

/**
 * Packet sent from client to server to inform the server of whether a client is
 * holding their sticking keybind or not
 */
public record StickingPacket(boolean sticking)
{
	public static StickingPacket decode(FriendlyByteBuf buf)
	{
		return new StickingPacket(buf.readBoolean());
	}
	
	public void encode(FriendlyByteBuf buf)
	{
		buf.writeBoolean(this.sticking);
	}
	
	public void handle(Supplier<NetworkEvent.Context> contextGetter)
	{
		Context context = contextGetter.get();
		context.enqueueWork(() ->
		{
			StickyPets.setPlayerSticking(context.getSender().getUUID(), this.sticking);
		});
		context.setPacketHandled(true);
	}
}
