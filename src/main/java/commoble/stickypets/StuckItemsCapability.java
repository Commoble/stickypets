package commoble.stickypets;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

public class StuckItemsCapability implements ICapabilitySerializable<Tag>
{
	public static final Capability<StuckItemsCapability> CAPABILITY = CapabilityManager.get(new CapabilityToken<>(){});
	public static final ResourceLocation ID = new ResourceLocation(StickyPets.MODID, "stuck_items");
	
	public static final Codec<List<Pair<Vec3, ItemStack>>> CODEC = Codec.mapPair(
			Vec3.CODEC.fieldOf("pos"),
			ItemStack.CODEC.fieldOf("item"))
		.codec()
		.listOf()
		.fieldOf("items") // FriendlyByteBuf#writeWithCodec only accepts object codecs
		.codec();
	
	private final LazyOptional<StuckItemsCapability> holder = LazyOptional.of(() -> this);
	private List<Pair<Vec3, ItemStack>> items = new ArrayList<>();
	public List<Pair<Vec3, ItemStack>> items() { return this.items; }

	@Override
	public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side)
	{
		return CAPABILITY.orEmpty(cap, this.holder); 
	}

	@Override
	public Tag serializeNBT()
	{
		return CODEC.encodeStart(NbtOps.INSTANCE, this.items).result().orElseGet(CompoundTag::new);
	}

	@Override
	public void deserializeNBT(Tag nbt)
	{
		CODEC.parse(NbtOps.INSTANCE, nbt).result().ifPresent(this::setItems);
	}
	
	public void addItem(Vec3 vec, ItemStack stack)
	{
		this.items.add(Pair.of(vec, stack));
	}
	
	public ItemStack removeNearestItem(Vec3 vec)
	{
		if (this.items.isEmpty())
			return ItemStack.EMPTY;
		
		int size = this.items.size();
		int nearestIndex = -1;
		ItemStack nearestStack = ItemStack.EMPTY;
		double nearestDist = Double.MAX_VALUE;
		for (int i=0; i<size; i++)
		{
			var pair = items.get(i);
			double dist = vec.distanceToSqr(pair.getFirst());
			if (dist < nearestDist)
			{
				nearestIndex = i;
				nearestStack = pair.getSecond();
				nearestDist = dist;
			}
		}
		this.items.remove(nearestIndex);
		return nearestStack.copy();
	}
	
	public void setItems(List<Pair<Vec3,ItemStack>> items)
	{
		this.items = new ArrayList<>(items);
	}
	
	public void onInvalidate()
	{
		this.holder.invalidate();
	}

}
