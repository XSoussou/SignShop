package org.wargamer2010.signshop.specialops;

import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.wargamer2010.signshop.Seller;
import org.wargamer2010.signshop.SignShop;
import org.wargamer2010.signshop.configuration.SignShopConfig;
import org.wargamer2010.signshop.configuration.Storage;
import org.wargamer2010.signshop.events.SSCreatedEvent;
import org.wargamer2010.signshop.events.SSEventFactory;
import org.wargamer2010.signshop.events.SSLinkEvent;
import org.wargamer2010.signshop.operations.SignShopArguments;
import org.wargamer2010.signshop.operations.SignShopArgumentsType;
import org.wargamer2010.signshop.operations.SignShopOperationListItem;
import org.wargamer2010.signshop.player.PlayerCache;
import org.wargamer2010.signshop.player.SignShopPlayer;
import org.wargamer2010.signshop.util.clicks;
import org.wargamer2010.signshop.util.economyUtil;
import org.wargamer2010.signshop.util.itemUtil;
import org.wargamer2010.signshop.util.signshopUtil;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class LinkAdditionalBlocks implements SignShopSpecialOp {

    private List<Block> updateList(final List<Block> masterBlocks, final List<Block> blocksToUpdate, final SignShopPlayer ssPlayer, final Seller pSeller) {
        for (Block masterBlock : masterBlocks) {
            if (blocksToUpdate.contains(masterBlock)) {
                ssPlayer.sendMessage("Attempting to unlink " + itemUtil.formatMaterialName(masterBlock) + " from shop.");
                blocksToUpdate.remove(masterBlock);
            } else {
                blocksToUpdate.add(masterBlock);
            }
        }
        for (Block newBlock : blocksToUpdate) {
            if (!masterBlocks.contains(newBlock)) {
                SSLinkEvent event = SSEventFactory.generateLinkEvent(newBlock, ssPlayer, pSeller);
                SignShop.scheduleEvent(event);
                if(event.isCancelled()) {
                    ssPlayer.sendMessage("You are not allowed to link this " + itemUtil.formatMaterialName(newBlock) + " to the shop.");
                    blocksToUpdate.remove(newBlock);
                } else {
                    ssPlayer.sendMessage("Attempting to link " + itemUtil.formatMaterialName(newBlock) + " to the shop.");
                }

            }
        }
        return blocksToUpdate;
    }

    @Override
    public Boolean runOperation(List<Block> clickedBlocks, PlayerInteractEvent event, Boolean ranSomething) {
        if(ranSomething)
            return false;
        SignShopConfig signShopConfig = SignShop.getInstance().getSignShopConfig();
        Player player = event.getPlayer();
        SignShopPlayer ssPlayer = PlayerCache.getPlayer(player);
        Block bClicked = event.getClickedBlock();
        Seller seller = Storage.get().getSeller(bClicked.getLocation());
        String sOperation = signshopUtil.getOperation(((Sign) bClicked.getState()).getSide(Side.FRONT).getLine(0));
        if (seller == null)
            return false;
        if (ssPlayer.getItemInHand() == null || ssPlayer.getItemInHand().getType() != signShopConfig.getLinkMaterial())
            return false;
        SignShopPlayer ssOwner = seller.getOwner();
        List<String> operation = signShopConfig.getBlocks(sOperation);
        String[] sLines = ((Sign) bClicked.getState()).getSide(Side.FRONT).getLines();

        if (!seller.isOwner(ssPlayer) && !ssPlayer.isOp()) {
            ssPlayer.sendMessage(signShopConfig.getError("no_permission", null));
            return true;
        }

        List<Block> containables = new LinkedList<>();
        List<Block> activatables = new LinkedList<>();
        boolean wentOK = signshopUtil.getSignshopBlocksFromList(ssPlayer, containables, activatables, event.getClickedBlock());
        if (!wentOK)
            return false;
        if(containables.isEmpty() && activatables.isEmpty())
            return false;

        int totalContainables = containables.size();
        List<Block> existingContainables = seller.getContainables();
        for(Block block : existingContainables) {
            if(containables.contains(block))
                totalContainables--;
            else
                totalContainables++;
        }

        boolean exceeded = signShopConfig.ExceedsMaxChestsPerShop(totalContainables);
        if(exceeded) {
            Map<String, String> parts = new LinkedHashMap<>();
            parts.put("!maxAmountOfChests", Integer.toString(signShopConfig.getMaxChestsPerShop()));
            ssPlayer.sendMessage(signShopConfig.getError("exceeded_max_amount_of_chests_per_shop", parts));
            return false;
        }

        List<SignShopOperationListItem> SignShopOperations = signshopUtil.getSignShopOps(operation);
        if (SignShopOperations == null) {
            ssPlayer.sendMessage(signShopConfig.getError("invalid_operation", null));
            return false;
        }

        containables = this.updateList(seller.getContainables(), containables, ssPlayer, seller);
        activatables = this.updateList(seller.getActivatables(), activatables, ssPlayer, seller);

        SignShopArguments ssArgs = new SignShopArguments(economyUtil.parsePrice(sLines[3]), seller.getItems(), containables, activatables,
                ssPlayer, ssOwner, bClicked, sOperation, event.getBlockFace(), event.getAction(), SignShopArgumentsType.Setup);

        Boolean bSetupOK = false;
        for (SignShopOperationListItem ssOperation : SignShopOperations) {
            List<String> params = ssOperation.getParameters();
            params.addAll(ssOperation.getParameters());
            params.add("allowemptychest");
            params.add("allowNoChests");
            ssArgs.setOperationParameters(params);

            bSetupOK = ssOperation.getOperation().setupOperation(ssArgs);
            if (!bSetupOK) {
                ssPlayer.sendMessage(signShopConfig.getError("failed_to_update_shop", ssArgs.getMessageParts()));
                return true;
            }
        }
        if (!bSetupOK) {
            ssPlayer.sendMessage(signShopConfig.getError("failed_to_update_shop", ssArgs.getMessageParts()));
            return true;
        }

        if (signshopUtil.cantGetPriceFromMoneyEvent(ssArgs)) {
            ssPlayer.sendMessage(signShopConfig.getError("failed_to_update_shop", ssArgs.getMessageParts()));
            return true;
        }

        SSCreatedEvent createdevent = SSEventFactory.generateCreatedEvent(ssArgs);
        SignShop.scheduleEvent(createdevent);
        if(createdevent.isCancelled()) {
            ssPlayer.sendMessage(signShopConfig.getError("failed_to_update_shop", ssArgs.getMessageParts()));
            return true;
        }

        Storage.get().updateSeller(bClicked, containables, activatables, ssArgs.getItems().getRoot());

        if (!ssArgs.bDoNotClearClickmap) {
            clicks.removePlayerFromClickmap(player);
        }

        ssPlayer.sendMessage(signShopConfig.getError("updated_shop", ssArgs.getMessageParts()));

        return true;
    }
}
