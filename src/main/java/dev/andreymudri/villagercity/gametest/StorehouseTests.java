package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.storehouse.StorehouseMenu;
import dev.andreymudri.villagercity.village.ContributionCategory;
import dev.andreymudri.villagercity.village.VillageCodecs;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.minecraft.world.ContainerHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class StorehouseTests {
    private static final BlockPos STORE = new BlockPos(20, 1, 24);
    /** Menu slot of player hotbar slot 0: the menu holds only the player's inventory, main inventory first. */
    private static final int HOTBAR_0 = StorehouseMenu.HOTBAR_START;

    private static StorehouseBlockEntity place(GameTestHelper helper) {
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        return helper.getBlockEntity(STORE);
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void citizenInsertAndExtract(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        helper.assertTrue(storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 64)).isEmpty(), "first stack rejected");
        helper.assertTrue(storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 10)).isEmpty(), "second stack rejected");
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 74, "count " + storehouse.count(Items.OAK_LOG));
        helper.assertTrue(storehouse.hasAll(Map.of(Items.OAK_LOG, 74)), "hasAll false");
        helper.assertFalse(storehouse.hasAll(Map.of(Items.OAK_LOG, 75)), "hasAll true for too many");
        ItemStack taken = storehouse.extractForCitizen(Items.OAK_LOG, 70);
        helper.assertTrue(taken.getCount() == 64, "extract is capped at one stack, got " + taken.getCount());
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 10, "left " + storehouse.count(Items.OAK_LOG));
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_quick_move")
    public static void quickMoveIntoStorehouseCreditsTheClicker(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 12, false);
        StorehouseBlockEntity storehouse = place(helper);
        village.setStorehousePos(helper.absolutePos(STORE));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 10));
        StorehouseMenu menu = new StorehouseMenu(1, player.getInventory(), storehouse);

        menu.clicked(HOTBAR_0, 0, ClickType.QUICK_MOVE, player);

        long credited = village.ledger().total(player.getUUID(), ContributionCategory.DEPOSIT);
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 10, "logs did not move, storehouse has " + storehouse.count(Items.OAK_LOG));
        helper.assertTrue(credited == 10, "credited " + credited);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_redeposit")
    public static void withdrawThenRedepositEarnsNothing(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 12, false);
        StorehouseBlockEntity storehouse = place(helper);
        village.setStorehousePos(helper.absolutePos(STORE));
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 20));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        StorehouseMenu menu = new StorehouseMenu(1, player.getInventory(), storehouse);

        menu.act(player, new ItemStack(Items.OAK_LOG), StorehouseMenu.Button.SHIFT);
        int withdrawn = player.getInventory().countItem(Items.OAK_LOG);
        int playerSlot = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().is(Items.OAK_LOG)) {
                playerSlot = i;
            }
        }
        if (playerSlot >= 0) {
            menu.clicked(playerSlot, 0, ClickType.QUICK_MOVE, player);
        }

        long credited = village.ledger().total(player.getUUID(), ContributionCategory.DEPOSIT);
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(withdrawn == 20 && playerSlot >= 0, "withdraw moved " + withdrawn + " logs");
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 20, "redeposit left " + storehouse.count(Items.OAK_LOG));
        helper.assertTrue(credited == 0, "credited " + credited + " for returning withdrawn logs");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_excess")
    public static void depositBeyondDebtCreditsOnlyTheExcess(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 12, false);
        StorehouseBlockEntity storehouse = place(helper);
        village.setStorehousePos(helper.absolutePos(STORE));
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 5));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        StorehouseMenu menu = new StorehouseMenu(1, player.getInventory(), storehouse);

        menu.act(player, new ItemStack(Items.OAK_LOG), StorehouseMenu.Button.SHIFT);
        long debtAfterWithdraw = village.debt(player.getUUID());
        int playerSlot = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().is(Items.OAK_LOG)) {
                playerSlot = i;
            }
        }
        if (playerSlot >= 0) {
            menu.getSlot(playerSlot).set(new ItemStack(Items.OAK_LOG, 10));
            menu.clicked(playerSlot, 0, ClickType.QUICK_MOVE, player);
        }

        long credited = village.ledger().total(player.getUUID(), ContributionCategory.DEPOSIT);
        long debt = village.debt(player.getUUID());
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(debtAfterWithdraw == 5 && playerSlot >= 0, "withdraw left debt " + debtAfterWithdraw);
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 10, "deposit left " + storehouse.count(Items.OAK_LOG));
        helper.assertTrue(credited == 5, "credited " + credited + " for depositing 10 against a debt of 5");
        helper.assertTrue(debt == 0, "debt left " + debt);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void storehouseDebtSurvivesCodecRoundTrip(GameTestHelper helper) {
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(new BlockPos(24, 1, 24)), 12);
        UUID player = UUID.randomUUID();
        village.setDebt(player, 20);

        Tag encoded = VillageCodecs.VILLAGE.encodeStart(NbtOps.INSTANCE, village).getOrThrow();
        VillageData decoded = VillageCodecs.VILLAGE.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        helper.assertTrue(decoded.debt(player) == 20, "debt after round trip " + decoded.debt(player));
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_openers")
    public static void otherOpenersAndHoppersAreNotCredited(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 12, false);
        StorehouseBlockEntity storehouse = place(helper);
        village.setStorehousePos(helper.absolutePos(STORE));
        Player a = helper.makeMockPlayer(GameType.SURVIVAL);
        Player b = helper.makeMockPlayer(GameType.SURVIVAL);
        a.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 5));
        StorehouseMenu menuA = new StorehouseMenu(1, a.getInventory(), storehouse);
        StorehouseMenu menuB = new StorehouseMenu(2, b.getInventory(), storehouse);

        menuA.clicked(HOTBAR_0, 0, ClickType.QUICK_MOVE, a);
        storehouse.insertFromCitizen(new ItemStack(Items.COBBLESTONE, 7));
        menuB.removed(b);
        menuA.removed(a);

        long creditA = village.ledger().total(a.getUUID(), ContributionCategory.DEPOSIT);
        long creditB = village.ledger().total(b.getUUID(), ContributionCategory.DEPOSIT);
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(creditA == 5, "A credited " + creditA);
        helper.assertTrue(creditB == 0, "B credited " + creditB);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void extractKeepsComponentsApart(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        ItemStack plain = new ItemStack(Items.OAK_LOG);
        ItemStack named = new ItemStack(Items.OAK_LOG);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Heirloom"));
        storehouse.insertFromCitizen(plain.copy());
        storehouse.insertFromCitizen(named.copy());

        ItemStack taken = storehouse.extractForCitizen(Items.OAK_LOG, 2);

        helper.assertTrue(taken.getCount() == 1, "extract merged differing stacks into " + taken.getCount() + " x " + taken.getHoverName().getString());
        boolean tookPlain = ItemStack.isSameItemSameComponents(taken, plain);
        boolean tookNamed = ItemStack.isSameItemSameComponents(taken, named);
        helper.assertTrue(tookPlain != tookNamed, "taken stack matches neither or both originals");
        ItemStack other = tookPlain ? named : plain;
        boolean otherStayed = storehouse.entries().stream().anyMatch(entry -> ItemStack.isSameItemSameComponents(entry.prototype(), other));
        helper.assertTrue(otherStayed && storehouse.count(Items.OAK_LOG) == 1, "the other log did not stay in the storehouse");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_hopper_out")
    public static void hoppersCannotPullFromStorehouse(GameTestHelper helper) {
        BlockPos store = new BlockPos(10, 2, 10);
        BlockPos hopperPos = new BlockPos(10, 1, 10);
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        helper.setBlock(hopperPos, Blocks.HOPPER);
        StorehouseBlockEntity storehouse = helper.getBlockEntity(store);
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 5));

        helper.runAfterDelay(60, () -> {
            HopperBlockEntity hopper = helper.getBlockEntity(hopperPos);
            helper.assertTrue(storehouse.count(Items.OAK_LOG) == 5, "storehouse left with " + storehouse.count(Items.OAK_LOG) + " logs");
            helper.assertTrue(hopper.isEmpty(), "hopper pulled items out of the storehouse");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_hopper_in")
    public static void hoppersCanStillInsertWithoutCredit(GameTestHelper helper) {
        BlockPos store = new BlockPos(10, 1, 10);
        BlockPos hopperPos = new BlockPos(10, 2, 10);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 12, false);
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(store));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(store);
        helper.setBlock(hopperPos, Blocks.HOPPER);
        HopperBlockEntity hopper = helper.getBlockEntity(hopperPos);
        hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 3));

        helper.succeedWhen(() -> {
            helper.assertTrue(storehouse.count(Items.COBBLESTONE) == 3, "storehouse holds " + storehouse.count(Items.COBBLESTONE) + " cobblestone");
            boolean credited = village.ledger().snapshot().values().stream()
                    .anyMatch(byCategory -> byCategory.getOrDefault(ContributionCategory.DEPOSIT, 0L) > 0);
            VillageTestSupport.remove(helper, village);
            helper.assertFalse(credited, "a hopper insert was credited");
        });
    }

    @SuppressWarnings("removal")
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_break")
    public static void breakingChargesTheBreakerDebt(GameTestHelper helper) {
        BlockPos store = new BlockPos(10, 1, 10);
        BlockPos abs = helper.absolutePos(store);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 12, false);
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        village.setStorehousePos(abs);
        StorehouseBlockEntity broken = helper.getBlockEntity(store);
        broken.insertFromCitizen(new ItemStack(Items.OAK_LOG, 12));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);

        boolean destroyed = player.gameMode.destroyBlock(abs);
        long debtAfterBreak = village.debt(player.getUUID());

        helper.setBlock(store, StorehouseContent.BLOCK.get());
        StorehouseBlockEntity storehouse = helper.getBlockEntity(store);
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 12));
        StorehouseMenu menu = new StorehouseMenu(1, player.getInventory(), storehouse);
        menu.clicked(HOTBAR_0, 0, ClickType.QUICK_MOVE, player);

        long credited = village.ledger().total(player.getUUID(), ContributionCategory.DEPOSIT);
        long debt = village.debt(player.getUUID());
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(4.0)).forEach(Entity::discard);
        helper.getLevel().getServer().getPlayerList().remove(player);
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(destroyed, "the player could not break the storehouse");
        helper.assertTrue(debtAfterBreak == 12, "breaking a storehouse holding 12 logs left debt " + debtAfterBreak);
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 12, "redeposit left " + storehouse.count(Items.OAK_LOG));
        helper.assertTrue(credited == 0, "credited " + credited + " for returning the dropped logs");
        helper.assertTrue(debt == 0, "debt left " + debt);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_explosion")
    public static void explosionsDoNotOpenStorehouse(GameTestHelper helper) {
        BlockPos store = new BlockPos(10, 1, 10);
        helper.setBlock(store, StorehouseContent.BLOCK.get());
        BlockPos abs = helper.absolutePos(store);

        helper.getLevel().explode(null, abs.getX() + 2.5, abs.getY() + 0.5, abs.getZ() + 0.5, 4.0f, Level.ExplosionInteraction.TNT);

        helper.assertBlockPresent(StorehouseContent.BLOCK.get(), store);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void bossesCannotBreakStorehouse(GameTestHelper helper) {
        place(helper);
        BlockState state = StorehouseContent.BLOCK.get().defaultBlockState();
        WitherBoss wither = EntityType.WITHER.create(helper.getLevel());

        helper.assertTrue(state.is(BlockTags.WITHER_IMMUNE), "storehouse is not wither immune");
        helper.assertTrue(state.is(BlockTags.DRAGON_IMMUNE), "storehouse is not dragon immune");
        helper.assertFalse(CommonHooks.canEntityDestroy(helper.getLevel(), helper.absolutePos(STORE), wither), "a wither may destroy the storehouse");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void dropsContentsWhenBroken(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 7));
        helper.getLevel().destroyBlock(helper.absolutePos(STORE), false);
        helper.succeedWhen(() -> helper.assertEntityPresent(EntityType.ITEM, STORE, 2.0));
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void storesFarMoreThanAChestAndKeepsItThroughASave(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        ItemStack named = new ItemStack(Items.OAK_LOG);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Heirloom"));
        storehouse.insert(new ItemStack(Items.OAK_LOG), 100_000L);
        storehouse.insert(new ItemStack(Items.COBBLESTONE), 5_000_000_000L);
        storehouse.insertFromCitizen(named.copyWithCount(3));
        HolderLookup.Provider registries = helper.getLevel().registryAccess();

        CompoundTag saved = storehouse.saveWithoutMetadata(registries);
        StorehouseBlockEntity loaded = new StorehouseBlockEntity(storehouse.getBlockPos(), storehouse.getBlockState());
        loaded.loadWithComponents(saved, registries);

        helper.assertTrue(loaded.entries().size() == 3, "entries " + loaded.entries());
        helper.assertTrue(loaded.count(Items.OAK_LOG) == 100_003L, "logs " + loaded.count(Items.OAK_LOG));
        helper.assertTrue(loaded.count(Items.COBBLESTONE) == 5_000_000_000L, "cobblestone " + loaded.count(Items.COBBLESTONE));
        helper.assertTrue(loaded.entries().stream().anyMatch(entry -> ItemStack.isSameItemSameComponents(entry.prototype(), named) && entry.count() == 3),
                "the named logs lost their name or count");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void anOldSlotStorehouseLoadsIntoEntries(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        NonNullList<ItemStack> slots = NonNullList.withSize(StorehouseBlockEntity.LEGACY_SIZE, ItemStack.EMPTY);
        slots.set(0, new ItemStack(Items.OAK_LOG, 64));
        slots.set(5, new ItemStack(Items.OAK_LOG, 10));
        slots.set(26, new ItemStack(Items.GLASS, 2));
        CompoundTag old = new CompoundTag();
        ContainerHelper.saveAllItems(old, slots, registries);

        storehouse.loadWithComponents(old, registries);

        helper.assertTrue(storehouse.entries().size() == 2, "entries " + storehouse.entries());
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 74 && storehouse.count(Items.GLASS) == 2, "counts " + storehouse.counts());
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_actions")
    public static void gridClicksTakeAndStoreAndSettleDebt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 12, false);
        StorehouseBlockEntity storehouse = place(helper);
        village.setStorehousePos(helper.absolutePos(STORE));
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 200));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        StorehouseMenu menu = new StorehouseMenu(1, player.getInventory(), storehouse);
        ItemStack log = new ItemStack(Items.OAK_LOG);
        UUID id = player.getUUID();

        menu.act(player, log, StorehouseMenu.Button.LEFT);
        boolean tookStack = menu.getCarried().getCount() == 64 && storehouse.count(Items.OAK_LOG) == 136 && village.debt(id) == 64;
        menu.act(player, ItemStack.EMPTY, StorehouseMenu.Button.RIGHT);
        boolean storedOne = menu.getCarried().getCount() == 63 && storehouse.count(Items.OAK_LOG) == 137 && village.debt(id) == 63;
        menu.act(player, ItemStack.EMPTY, StorehouseMenu.Button.LEFT);
        boolean storedAll = menu.getCarried().isEmpty() && storehouse.count(Items.OAK_LOG) == 200 && village.debt(id) == 0;
        menu.act(player, log, StorehouseMenu.Button.RIGHT);
        boolean tookHalf = menu.getCarried().getCount() == 32 && storehouse.count(Items.OAK_LOG) == 168;
        menu.getCarried().setCount(0);
        menu.setCarried(ItemStack.EMPTY);
        menu.act(player, log, StorehouseMenu.Button.SHIFT);
        boolean tookAll = player.getInventory().countItem(Items.OAK_LOG) == 168 && storehouse.count(Items.OAK_LOG) == 0 && village.debt(id) == 200;
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 50));
        menu.clicked(HOTBAR_0, 0, ClickType.QUICK_MOVE, player);
        boolean quickStored = storehouse.count(Items.OAK_LOG) == 50 && village.debt(id) == 150;

        long credited = village.ledger().total(id, ContributionCategory.DEPOSIT);
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(tookStack, "left click did not take a stack: carried " + menu.getCarried());
        helper.assertTrue(storedOne, "right click with a stack did not store one");
        helper.assertTrue(storedAll, "left click with a stack did not store it");
        helper.assertTrue(tookHalf, "right click did not take half a stack");
        helper.assertTrue(tookAll, "shift click did not fill the inventory: " + player.getInventory().countItem(Items.OAK_LOG));
        helper.assertTrue(quickStored, "shift click on an inventory slot did not store the stack");
        helper.assertTrue(credited == 0, "credited " + credited + " for moving the storehouse's own logs around");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_full_inventory")
    public static void shiftTakingIntoAFullInventoryKeepsTheRest(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 100));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        for (int i = 0; i < 36; i++) {
            player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        }
        player.getInventory().setItem(3, new ItemStack(Items.OAK_LOG, 60));
        StorehouseMenu menu = new StorehouseMenu(1, player.getInventory(), storehouse);

        menu.act(player, new ItemStack(Items.OAK_LOG), StorehouseMenu.Button.SHIFT);

        helper.assertTrue(player.getInventory().countItem(Items.OAK_LOG) == 64, "inventory logs " + player.getInventory().countItem(Items.OAK_LOG));
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 96, "storehouse logs " + storehouse.count(Items.OAK_LOG));
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void itemPipesInsertAnyAmountButExtractNothing(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        IItemHandler handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(STORE), Direction.UP);
        helper.assertTrue(handler != null, "no item handler");
        for (int i = 0; i < 40; i++) {
            ItemStack rest = ItemHandlerHelper.insertItemStacked(handler, new ItemStack(i % 2 == 0 ? Items.OAK_LOG : Items.COBBLESTONE, 64), false);
            helper.assertTrue(rest.isEmpty(), "pipe insert " + i + " rejected " + rest);
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            helper.assertTrue(handler.extractItem(slot, 64, false).isEmpty(), "pipe extracted from slot " + slot);
        }
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 20 * 64 && storehouse.count(Items.COBBLESTONE) == 20 * 64, "counts " + storehouse.counts());
        helper.succeed();
    }
}
