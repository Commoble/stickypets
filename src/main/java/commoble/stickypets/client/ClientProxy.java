package commoble.stickypets.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;

import commoble.stickypets.StickingPacket;
import commoble.stickypets.StickyPets;
import commoble.stickypets.StuckItemsCapability;
import commoble.stickypets.StuckItemsPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@EventBusSubscriber(modid=StickyPets.MODID, value=Dist.CLIENT, bus=Bus.MOD)
public class ClientProxy
{
	public static final KeyMapping STICKING = new KeyMapping("stickypets.key.stick", InputConstants.Type.KEYSYM, InputConstants.KEY_LCONTROL, "key.categories.gameplay");
	
	private static boolean isSticking = false;
	
	@SubscribeEvent
	public static void onModConstructed(FMLConstructModEvent event)
	{
		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		IEventBus forgeBus = MinecraftForge.EVENT_BUS;
		
		modBus.addListener(ClientProxy::onRegisterKeyMappings);
		
		forgeBus.addListener(ClientProxy::onClientTick);
		forgeBus.addListener(ClientProxy::onRenderLivingPost);
	}
	
	private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)
	{
		event.register(STICKING);
	}
	
	private static void onClientTick(ClientTickEvent event)
	{
		if (event.phase == Phase.END)
		{
			boolean isNowSticking = STICKING.isDown();
			if (isSticking != isNowSticking)
			{
				StickyPets.CHANNEL.sendToServer(new StickingPacket(isNowSticking));
				isSticking = isNowSticking;
			}
		}
	}
	
	private static void onRenderLivingPost(RenderLivingEvent.Post<?,?> event)
	{
		LivingEntity living = event.getEntity();
		if (living.isDeadOrDying())
			return;
		
		living.getCapability(StuckItemsCapability.CAPABILITY).ifPresent(cap ->
		{
			PoseStack poseStack = event.getPoseStack();
			MultiBufferSource buffers = event.getMultiBufferSource();
			for (var pair : cap.items())
			{
				Vec3 storedPos = pair.getFirst();
				ItemStack itemStack = pair.getSecond();
				
				poseStack.pushPose();

				float partialTicks = event.getPartialTick();
				float bodyRotation = Mth.lerp(partialTicks, living.yBodyRotO, living.yBodyRot);
				poseStack.mulPose(Vector3f.YN.rotationDegrees(bodyRotation));
				poseStack.translate(storedPos.x, storedPos.y, storedPos.z);
				Minecraft.getInstance().getItemRenderer().renderStatic(itemStack, ItemTransforms.TransformType.FIXED, event.getPackedLight(), OverlayTexture.NO_OVERLAY, poseStack, buffers, living.getId());
				
				poseStack.popPose();
			}
		});
	}
	
	public static boolean isSticking()
	{
		return isSticking;
	}
	
	public static void handleStuckItemsPacket(StuckItemsPacket packet)
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc == null)
			return;
		
		ClientLevel level = mc.level;
		if (level == null)
			return;

		Entity entity = level.getEntity(packet.entityId());
		if (entity == null)
			return;
		
		entity.getCapability(StuckItemsCapability.CAPABILITY)
			.ifPresent(cap -> cap.setItems(packet.items()));
	}
}
