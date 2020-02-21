package mctmods.immersivetechnology.common.blocks.metal.tileentities;

import blusunrize.immersiveengineering.common.util.Utils;
import mctmods.immersivetechnology.ImmersiveTechnology;
import mctmods.immersivetechnology.api.crafting.DistillerRecipe;
import mctmods.immersivetechnology.common.util.ITSounds;
import mctmods.immersivetechnology.common.util.network.MessageStopSound;
import mctmods.immersivetechnology.common.util.network.MessageTileSync;
import mctmods.immersivetechnology.common.util.sound.ITSoundHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.oredict.OreDictionary;

public class TileEntityDistillerMaster extends TileEntityDistillerSlave {

    public FluidTank[] tanks = new FluidTank[] {
            new FluidTank(24000),
            new FluidTank(24000)
    };

    public NonNullList<ItemStack> inventory = NonNullList.withSize(4, ItemStack.EMPTY);

    private boolean running;
    private boolean previousRenderState;
    private float soundVolume;

    @Override
    public void readCustomNBT(NBTTagCompound nbt, boolean descPacket) {
        super.readCustomNBT(nbt, descPacket);
        tanks[0].readFromNBT(nbt.getCompoundTag("tank0"));
        tanks[1].readFromNBT(nbt.getCompoundTag("tank1"));
        running = nbt.getBoolean("running");
        if(!descPacket) inventory = Utils.readInventory(nbt.getTagList("inventory", 10), 4);
    }

    @Override
    public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket) {
        super.writeCustomNBT(nbt, descPacket);
        nbt.setTag("tank0", tanks[0].writeToNBT(new NBTTagCompound()));
        nbt.setTag("tank1", tanks[1].writeToNBT(new NBTTagCompound()));
        nbt.setBoolean("running", running);
        if(!descPacket) nbt.setTag("inventory", Utils.writeInventory(inventory));
    }

    public void handleSounds() {
        if(running) {
            if(soundVolume < 1) soundVolume += 0.01f;
        } else if(soundVolume > 0) soundVolume -= 0.01f;
        BlockPos center = getPos();
        if(soundVolume == 0) ITSoundHandler.StopSound(center);
        else {
            EntityPlayerSP player = Minecraft.getMinecraft().player;
            float attenuation = Math.max((float) player.getDistanceSq(center.getX(), center.getY(), center.getZ()) / 8, 1);
            ITSoundHandler.PlaySound(center, ITSounds.distiller, SoundCategory.BLOCKS, true, soundVolume / attenuation, 1);
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void onChunkUnload() {
        if(!isDummy()) ITSoundHandler.StopSound(getPos());
        super.onChunkUnload();
    }

    @Override
    public void disassemble() {
        BlockPos center = getPos();
        ImmersiveTechnology.packetHandler.sendToAllTracking(new MessageStopSound(center), new NetworkRegistry.TargetPoint(world.provider.getDimension(), center.getX(), center.getY(), center.getZ(), 0));
        super.disassemble();
    }

    public void notifyNearbyClients() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("running", running);
        BlockPos center = getPos();
        ImmersiveTechnology.packetHandler.sendToAllTracking(new MessageTileSync(this, tag), new NetworkRegistry.TargetPoint(world.provider.getDimension(), center.getX(), center.getY(), center.getZ(), 0));
    }

    public void efficientMarkDirty() { // !!!!!!! only use it within update() function !!!!!!!
        world.getChunkFromBlockCoords(this.getPos()).markDirty();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void update() {
        if(world.isRemote) {
            handleSounds();
            return;
        }
        boolean update = false;
        if(energyStorage.getEnergyStored() > 0 && processQueue.size() < this.getProcessQueueMaxLength()) {
            if(tanks[0].getFluidAmount() > 0) {
                DistillerRecipe recipe = DistillerRecipe.findRecipe(tanks[0].getFluid());
                if(recipe != null) {
                    MultiblockProcessInMachine<DistillerRecipe> process = new MultiblockProcessInMachine<DistillerRecipe>(recipe).setInputTanks(new int[] {0});
                    if(this.addProcessToQueue(process, false)) update = true;
                }
            }
        }
        super.update();
        if(this.tanks[1].getFluidAmount() > 0) {
            ItemStack filledContainer = Utils.fillFluidContainer(tanks[1], inventory.get(2), inventory.get(3), null);
            if(!filledContainer.isEmpty()) {
                if(!inventory.get(3).isEmpty() && OreDictionary.itemMatches(inventory.get(3), filledContainer, true)) inventory.get(3).grow(filledContainer.getCount());
                else if(inventory.get(3).isEmpty()) inventory.set(3, filledContainer.copy());
                inventory.get(2).shrink(1);
                if(inventory.get(2).getCount() <= 0) inventory.set(2, ItemStack.EMPTY);
                update = true;
            }
            EnumFacing fw;
            if(!mirrored) {
                fw = facing.rotateY().getOpposite();
            } else {
                fw = facing.rotateY();
            }
            if(this.tanks[1].getFluidAmount() > 0) {
                FluidStack out = Utils.copyFluidStackWithAmount(this.tanks[1].getFluid(), Math.min(this.tanks[1].getFluidAmount(), 80), false);
                BlockPos outputPos = this.getPos().add(0, -1, 0).offset(fw, 2);
                IFluidHandler output = FluidUtil.getFluidHandler(world, outputPos, facing);
                if(output != null) {
                    int accepted = output.fill(out, false);
                    if(accepted > 0) {
                        int drained = output.fill(Utils.copyFluidStackWithAmount(out, Math.min(out.amount, accepted), false), true);
                        this.tanks[1].drain(drained, true);
                        update = true;
                    }
                }
            }
        }
        ItemStack emptyContainer = Utils.drainFluidContainer(tanks[0], inventory.get(0), inventory.get(1), null);
        if(!emptyContainer.isEmpty() && emptyContainer.getCount() > 0) {
            if(!inventory.get(1).isEmpty() && OreDictionary.itemMatches(inventory.get(1), emptyContainer, true)) inventory.get(1).grow(emptyContainer.getCount());
            else if(inventory.get(1).isEmpty())	inventory.set(1, emptyContainer.copy());
            inventory.get(0).shrink(1);
            if(inventory.get(0).getCount() <= 0) inventory.set(0, ItemStack.EMPTY);
            update = true;
        }
        if(update) {
            efficientMarkDirty();
            this.markContainingBlockForUpdate(null);
        }

        running = shouldRenderAsActive() && !processQueue.isEmpty() && processQueue.get(0).canProcess(this);
        if(previousRenderState != running) notifyNearbyClients();
        previousRenderState = running;
    }

    @Override
    public boolean isDummy() {
        return false;
    }

    @Override
    public TileEntityDistillerMaster master() {
        master = this;
        return this;
    }

    @Override
    public void receiveMessageFromServer(NBTTagCompound message) {
        running = message.getBoolean("running");
    }

}