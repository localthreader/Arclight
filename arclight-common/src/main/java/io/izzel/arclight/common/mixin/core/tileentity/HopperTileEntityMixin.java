package io.izzel.arclight.common.mixin.core.tileentity;

import io.izzel.arclight.common.bridge.entity.EntityBridge;
import io.izzel.arclight.common.bridge.inventory.IInventoryBridge;
import io.izzel.arclight.common.bridge.world.WorldBridge;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.item.minecart.HopperMinecartEntity;
import net.minecraft.inventory.DoubleSidedInventory;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.HopperTileEntity;
import net.minecraft.tileentity.IHopper;
import net.minecraft.util.Direction;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.entity.CraftHumanEntity;
import org.bukkit.craftbukkit.v.inventory.CraftInventoryDoubleChest;
import org.bukkit.craftbukkit.v.inventory.CraftItemStack;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.spigotmc.SpigotWorldConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Mixin(HopperTileEntity.class)
public abstract class HopperTileEntityMixin extends LockableTileEntityMixin {

    // @formatter:off
    @Shadow private NonNullList<ItemStack> inventory;
    @Shadow public abstract void setTransferCooldown(int ticks);
    @Shadow public abstract void setInventorySlotContents(int index, ItemStack stack);
    @Shadow public abstract boolean updateHopper(Supplier<Boolean> booleanSupplier);
    @Shadow private int transferCooldown;
    // @formatter:on

    public List<HumanEntity> transaction = new ArrayList<>();
    private int maxStack = MAX_STACK;

    private static final int DEFAULT_TRANSFER_COOLDOWN_IN_TICKS = 8;
    private static transient boolean arclight$moveItem;

    @ModifyArg(method = "updateHopper", index = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/HopperTileEntity;setTransferCooldown(I)V"))
    public int arclight$applyCooldownInUpdate(int ticks) {
    	return this.getSpigotWorldConfig().hopperTransfer;
    }

    @ModifyArg(method = "pullItemFromSlot", index = 1, at = @At(value = "INVOKE", target = "Lnet/minecraft/inventory/IInventory;decrStackSize(II)Lnet/minecraft/item/ItemStack;"))
	private static int arclight$stackSizeWhilePulling(int amount) {
    	return 64;
	}

    @ModifyArg(method = "transferItemsOut", index = 1, at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/HopperTileEntity;decrStackSize(II)Lnet/minecraft/item/ItemStack;"))
    public int arclight$stackSizeWhileTransferingItemsOut(int count) {
    	return 64;
    }

    @Redirect(method = "insertStack", at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/HopperTileEntity;setTransferCooldown(I)V"))
    private static void arclight$applyCooldownInInsertion(HopperTileEntity hopperTileEntity, int ticks) {
    	SpigotWorldConfig worldConfig = ((WorldBridge) hopperTileEntity.world).bridge$spigotConfig();
    	hopperTileEntity.setTransferCooldown(worldConfig.hopperTransfer - (DEFAULT_TRANSFER_COOLDOWN_IN_TICKS - ticks));
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/HopperTileEntity;updateHopper(Ljava/util/function/Supplier;)Z"))
    public boolean arclight$applyCheckCooldownLogic(HopperTileEntity hopperTile, Supplier<Boolean> booleanSupplier) {
    	boolean result = this.updateHopper(() -> {
    		return HopperTileEntity.pullItems(hopperTile);
    	});
    	
        if (!result && this.getSpigotWorldConfig().hopperCheck > 1) {
        	// Applies check cooldown
        	this.setTransferCooldown(this.getSpigotWorldConfig().hopperCheck);
        }
        
        return result;
    }

    @Inject(method = "transferItemsOut", cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD, at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/tileentity/HopperTileEntity;putStackInInventoryAllSlots(Lnet/minecraft/inventory/IInventory;Lnet/minecraft/inventory/IInventory;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Direction;)Lnet/minecraft/item/ItemStack;"))
    public void arclight$returnIfMoveFail(CallbackInfoReturnable<Boolean> cir, IInventory inv, Direction direction, int i, ItemStack itemStack) {
        if (arclight$moveItem) {
            this.setInventorySlotContents(i, itemStack);
            cir.setReturnValue(false);
        }
        arclight$moveItem = false;
    }

    @Redirect(method = "transferItemsOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/HopperTileEntity;putStackInInventoryAllSlots(Lnet/minecraft/inventory/IInventory;Lnet/minecraft/inventory/IInventory;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Direction;)Lnet/minecraft/item/ItemStack;"))
    public ItemStack arclight$moveItem(IInventory source, IInventory destination, ItemStack stack, Direction direction) {
        CraftItemStack original = CraftItemStack.asCraftMirror(stack);

        Inventory destinationInventory;
        // Have to special case large chests as they work oddly
        if (destination instanceof DoubleSidedInventory) {
            destinationInventory = new CraftInventoryDoubleChest(((DoubleSidedInventory) destination));
        } else {
            destinationInventory = ((IInventoryBridge) destination).getOwnerInventory();
        }

        InventoryMoveItemEvent event = new InventoryMoveItemEvent(this.getOwner().getInventory(), original.clone(), destinationInventory, true);
        Bukkit.getPluginManager().callEvent(event);
        if (arclight$moveItem = event.isCancelled()) {
            this.setTransferCooldown(this.getSpigotWorldConfig().hopperTransfer); // Delay hopper transfer
            return null;
        }
        return HopperTileEntity.putStackInInventoryAllSlots(source, destination, CraftItemStack.asNMSCopy(event.getItem()), direction);
    }

    @Inject(method = "pullItemFromSlot", cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD, at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/tileentity/HopperTileEntity;putStackInInventoryAllSlots(Lnet/minecraft/inventory/IInventory;Lnet/minecraft/inventory/IInventory;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Direction;)Lnet/minecraft/item/ItemStack;"))
    private static void arclight$returnIfPullFail(IHopper hopper, IInventory inventoryIn, int index, Direction direction, CallbackInfoReturnable<Boolean> cir, ItemStack item, ItemStack item1) {
        if (arclight$moveItem) {
            inventoryIn.setInventorySlotContents(index, item1);
            cir.setReturnValue(false);
        }
        arclight$moveItem = false;
    }

    @Redirect(method = "pullItemFromSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/HopperTileEntity;putStackInInventoryAllSlots(Lnet/minecraft/inventory/IInventory;Lnet/minecraft/inventory/IInventory;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Direction;)Lnet/minecraft/item/ItemStack;"))
    private static ItemStack arclight$pullItem(IInventory source, IInventory destination, ItemStack stack, Direction direction) {
        CraftItemStack original = CraftItemStack.asCraftMirror(stack);

        Inventory sourceInventory;
        // Have to special case large chests as they work oddly
        if (source instanceof DoubleSidedInventory) {
            sourceInventory = new CraftInventoryDoubleChest(((DoubleSidedInventory) source));
        } else {
            sourceInventory = ((IInventoryBridge) source).getOwnerInventory();
        }

        InventoryMoveItemEvent event = new InventoryMoveItemEvent(sourceInventory, original.clone(), ((IInventoryBridge) destination).getOwnerInventory(), false);
        Bukkit.getPluginManager().callEvent(event);
        if (arclight$moveItem = event.isCancelled()) {
            if (destination instanceof HopperTileEntity) {
            	HopperTileEntity destinationAsTile = (HopperTileEntity) destination;
            	SpigotWorldConfig worldConfig = ((WorldBridge) destinationAsTile.world).bridge$spigotConfig();

                destinationAsTile.setTransferCooldown(worldConfig.hopperTransfer); // Delay hopper transfer
            } else if (destination instanceof HopperMinecartEntity) {
            	HopperMinecartEntity destinationAsEntity = (HopperMinecartEntity) destination;
            	SpigotWorldConfig worldConfig = ((WorldBridge) destinationAsEntity.world).bridge$spigotConfig();

            	destinationAsEntity.setTransferTicker(worldConfig.hopperTransfer / 2); // Delay hopper minecart transfer
            }
            return null;
        }
        return HopperTileEntity.putStackInInventoryAllSlots(source, destination, CraftItemStack.asNMSCopy(event.getItem()), direction);
    }

    @Inject(method = "captureItem", cancellable = true, at = @At("HEAD"))
    private static void arclight$pickupItem(IInventory inventory, ItemEntity itemEntity, CallbackInfoReturnable<Boolean> cir) {
        InventoryPickupItemEvent event = new InventoryPickupItemEvent(((IInventoryBridge) inventory).getOwnerInventory(), (Item) ((EntityBridge) itemEntity).bridge$getBukkitEntity());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            cir.setReturnValue(false);
        }
    }

    @Override
    public List<ItemStack> getContents() {
        return this.inventory;
    }

    @Override
    public void onOpen(CraftHumanEntity who) {
        transaction.add(who);
    }

    @Override
    public void onClose(CraftHumanEntity who) {
        transaction.remove(who);
    }

    @Override
    public List<HumanEntity> getViewers() {
        return transaction;
    }

    @Override
    public void setOwner(InventoryHolder owner) {
    }

    @Override
    public int getInventoryStackLimit() {
        if (maxStack == 0) maxStack = MAX_STACK;
        return maxStack;
    }

    @Override
    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }
    
    private SpigotWorldConfig getSpigotWorldConfig() {
    	return this.getSpigotWorldConfig(this.world);
    }
    
    private SpigotWorldConfig getSpigotWorldConfig(World world) {
    	return ((WorldBridge) world).bridge$spigotConfig();
    }
}
