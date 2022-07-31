package commoble.stickypets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import commoble.stickypets.client.ClientProxy;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.StartTracking;
import net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(StickyPets.MODID)
public class StickyPets
{
	public static final String MODID = "stickypets";
	
	public static final TagKey<EntityType<?>> STICKY_TAG = TagKey.create(Registry.ENTITY_TYPE_REGISTRY, new ResourceLocation(MODID, "sticky"));
	public static final TagKey<EntityType<?>> NOT_STICKY_TAG = TagKey.create(Registry.ENTITY_TYPE_REGISTRY, new ResourceLocation(MODID, "not_sticky"));
	
	public static final String PROTOCOL_VERSION = "1";
	public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
		new ResourceLocation(MODID, "main"),
		() -> PROTOCOL_VERSION,
		PROTOCOL_VERSION::equals,
		PROTOCOL_VERSION::equals
	);
	
	private static CommonConfig commonConfig;
	public static CommonConfig commonConfig() { return commonConfig; }
	
	private static Set<UUID> stickingPlayers = new HashSet<>();
	private static Object2LongMap<UUID> interactionTimestamps = new Object2LongOpenHashMap<>();
	
	public StickyPets()
	{
		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		IEventBus forgeBus = MinecraftForge.EVENT_BUS;
		
		modBus.addListener(this::onRegisterCapabilities);
		
		// register event handlers
		forgeBus.addListener(this::onServerAboutToStart);
		forgeBus.addGenericListener(Entity.class, this::onAttachEntityCapabilities);
		forgeBus.addListener(this::onStartTracking);
		forgeBus.addListener(this::onEntityInteractSpecific);
		forgeBus.addListener(this::onLivingDrops);
		forgeBus.addListener(this::onServerStopped);
		
		// register packets
		int id=0;
		CHANNEL.registerMessage(id++,
			StickingPacket.class,
			StickingPacket::encode,
			StickingPacket::decode,
			StickingPacket::handle);
		CHANNEL.registerMessage(id++,
			StuckItemsPacket.class,
			StuckItemsPacket::encode,
			StuckItemsPacket::decode,
			StuckItemsPacket::handle);
		
		// register configs
		commonConfig = registerConfig(Type.COMMON, CommonConfig::create);
	}
	
	public static boolean isPlayerSticking(Player player)
	{
		if (player.level.isClientSide)
		{
			return ClientProxy.isSticking();
		}
		else
		{
			return stickingPlayers.contains(player.getUUID());
		}
	}
	
	public static void setPlayerSticking(UUID player, boolean sticking)
	{
		if (sticking)
		{
			stickingPlayers.add(player);
		}
		else
		{
			stickingPlayers.remove(player);
		}
	}
	
	private void onRegisterCapabilities(RegisterCapabilitiesEvent event)
	{
		event.register(StuckItemsCapability.class);
	}
	
	private void onServerAboutToStart(ServerAboutToStartEvent event)
	{
		clearStaticServerData();
	}
	
	private void onAttachEntityCapabilities(AttachCapabilitiesEvent<Entity> event)
	{
		if (isEntitySticky(event.getObject()))
		{
			StuckItemsCapability cap = new StuckItemsCapability();
			event.addCapability(StuckItemsCapability.ID, cap);
			event.addListener(cap::onInvalidate);
		}
	}
	
	private void onStartTracking(StartTracking event)
	{
		Entity ent = event.getTarget();
		if (event.getEntity() instanceof ServerPlayer serverPlayer)
		{
			ent.getCapability(StuckItemsCapability.CAPABILITY).ifPresent(cap ->
			{
				CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new StuckItemsPacket(ent.getId(), cap.items()));
			});
		}
	}
	
	private void onEntityInteractSpecific(EntityInteractSpecific event)
	{
		Player player = event.getEntity();
		Level level = player.level;
		Entity target = event.getTarget();
		
		if (!isPlayerSticking(player)
			|| !(isEntitySticky(target)))
		{
			return; // pass on event
		}
		
		InteractionHand hand = event.getHand();
		ItemStack stack = player.getItemInHand(hand);
		if (stack.isEmpty())
		{
			if (player instanceof ServerPlayer serverPlayer)
			{
				// handle unstucking items
				target.getCapability(StuckItemsCapability.CAPABILITY).ifPresent(cap ->
				{
					// if player already interacted this tick, return
					// TODO kludge to workaround forge bug #8390
					long gameTime = level.getGameTime();
					UUID uuid = serverPlayer.getUUID();
					if (interactionTimestamps.getLong(uuid) == gameTime)
					{
						return;
					}
					interactionTimestamps.put(uuid, gameTime);
					
					// if not enough items, deny attempt
					if (cap.items().isEmpty())
					{
						player.playNotifySound(SoundEvents.WANDERING_TRADER_HURT, SoundSource.BLOCKS, 0.5F, 2F);
						return;
					}
					// normalize rotation relative to entity facing
					// entityInteractSpecific doesn't actually provide hitpos on server
					HitResult hitResult = clipEntityInteraction(player, target);
					if (hitResult == null)
					{
						return;
					}
					Vec3 hitPos = hitResult.getLocation();
					Vec3 localPos = hitPos.subtract(target.position());
					float rotation = ((LivingEntity)target).yBodyRot;
					Vec3 rotatedPos = localPos.yRot(rotation*Mth.DEG_TO_RAD);
					ItemStack retrievedStack = cap.removeNearestItem(rotatedPos);
					if (!player.isCreative()) // player creative inventory overrides mess with inventory syncing
					{
						player.addItem(retrievedStack);
					}

					CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> target), new StuckItemsPacket(target.getId(), new ArrayList<>(cap.items())));
					level.playSound(null, target.blockPosition(), SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, level.random.nextFloat()*0.5F, level.random.nextFloat());
				});
			}
			event.setCancellationResult(InteractionResult.SUCCESS);
		}
		else if (stack.getItem().getMaxStackSize(stack) == 1)
		{
			if (player instanceof ServerPlayer serverPlayer)
			{
				// handle stucking items
				target.getCapability(StuckItemsCapability.CAPABILITY).ifPresent(cap ->
				{
					// if player already interacted this tick, return
					// TODO kludge to workaround forge bug #8390
					long gameTime = level.getGameTime();
					UUID uuid = serverPlayer.getUUID();
					if (interactionTimestamps.getLong(uuid) == gameTime)
					{
						return;
					}
					interactionTimestamps.put(uuid, gameTime);
					
					// if too many items, deny attempt
					if (cap.items().size() >= commonConfig.maxStuckItems().get())
					{
						player.playNotifySound(SoundEvents.WANDERING_TRADER_HURT, SoundSource.BLOCKS, 0.5F, 2F);
						return;
					}
					// normalize rotation relative to entity facing
					// entityInteractSpecific doesn't actually provide hitpos on server
					HitResult hitResult = clipEntityInteraction(player, target);
					if (hitResult == null)
					{
						return;
					}
					Vec3 hitPos = hitResult.getLocation();
					Vec3 localPos = hitPos.subtract(target.position());
					float rotation = ((LivingEntity)target).yBodyRot;
					Vec3 rotatedPos = localPos.yRot(rotation*Mth.DEG_TO_RAD);
					cap.addItem(rotatedPos, stack.copy());
					if (!player.isCreative()) // player creative inventory overrides mess with inventory syncing
					{
						stack.shrink(1);
					}

					CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> target), new StuckItemsPacket(target.getId(), cap.items()));
					level.playSound(null, target.blockPosition(), SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, level.random.nextFloat()*0.5F, level.random.nextFloat());
				});
			}
			event.setCancellationResult(InteractionResult.SUCCESS);
		}
	}
	
	private void onLivingDrops(LivingDropsEvent event)
	{
		LivingEntity entity = event.getEntity();
		event.getEntity().getCapability(StuckItemsCapability.CAPABILITY).ifPresent(cap ->
		{
			cap.items().forEach(pair -> entity.spawnAtLocation(pair.getSecond()));
		});
	}
	
	private void onServerStopped(ServerStoppedEvent event)
	{
		clearStaticServerData();
	}
	
	private static void clearStaticServerData()
	{
		stickingPlayers = new HashSet<>();
		interactionTimestamps = new Object2LongOpenHashMap<>();
	}
	
	public static <T> T registerConfig(
		final ModConfig.Type configType,
		final Function<ForgeConfigSpec.Builder, T> configFactory)
	{
		return registerConfig(configType, configFactory, null);
	}
	
	private static <T> T registerConfig(
		final ModConfig.Type configType,
		final Function<ForgeConfigSpec.Builder, T> configFactory,
		final @Nullable String configName)
	{
		final ModLoadingContext modContext = ModLoadingContext.get();
		final org.apache.commons.lang3.tuple.Pair<T, ForgeConfigSpec> entry = new ForgeConfigSpec.Builder()
			.configure(configFactory);
		final T config = entry.getLeft();
		final ForgeConfigSpec spec = entry.getRight();
		if (configName == null)
		{
			modContext.registerConfig(configType,spec);
		}
		else
		{
			modContext.registerConfig(configType, spec, configName + ".toml");
		}
		
		return config;
	}
	
	public static EntityHitResult clipEntityInteraction(Player player, Entity target)
	{
		Vec3 startPos = player.getEyePosition(0F);
		Vec3 viewVec = player.getViewVector(0F);
		double range = player.getAttackRange();
		Vec3 endPos = startPos.add(viewVec.x * range, viewVec.y * range, viewVec.z * range);
		return ProjectileUtil.getEntityHitResult(player, startPos, endPos, target.getBoundingBox(), e -> e == target, range*range);
	}
	
	/**
	 * Non-living entities are never sticky.
	 * Living entities in the sticky tag are sticky.
	 * Animals are sticky unless in the not_sticky tag.
	 * @param entity Entity
	 * @return is entity sticky
	 */
	public static boolean isEntitySticky(Entity entity)
	{
		if (!(entity instanceof LivingEntity))
			return false;
		
		Holder<EntityType<?>> holder = ForgeRegistries.ENTITY_TYPES.getHolder(entity.getType()).get();
		return holder.is(STICKY_TAG) || (entity instanceof Animal && !holder.is(NOT_STICKY_TAG));
	}
}
