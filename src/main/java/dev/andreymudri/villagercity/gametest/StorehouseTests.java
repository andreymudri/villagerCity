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
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class StorehouseTests {
    private static final BlockPos STORE = new BlockPos(20, 1, 24);
    /** Menu slot of player hotbar slot 0: after the 27 storehouse slots and the 27 main inventory slots. */
    private static final int HOTBAR_0 = StorehouseBlockEntity.SIZE + 27;

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

        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        int withdrawn = player.getInventory().countItem(Items.OAK_LOG);
        int playerSlot = -1;
        for (int i = StorehouseBlockEntity.SIZE; i < menu.slots.size(); i++) {
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

        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        long debtAfterWithdraw = village.debt(player.getUUID());
        int playerSlot = -1;
        for (int i = StorehouseBlockEntity.SIZE; i < menu.slots.size(); i++) {
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
        storehouse.setItem(26, new ItemStack(Items.COBBLESTONE, 7));
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
        storehouse.setItem(0, plain.copy());
        storehouse.setItem(1, named.copy());

        ItemStack taken = storehouse.extractForCitizen(Items.OAK_LOG, 2);

        helper.assertTrue(taken.getCount() == 1, "extract merged differing stacks into " + taken.getCount() + " x " + taken.getHoverName().getString());
        boolean tookPlain = ItemStack.isSameItemSameComponents(taken, plain);
        boolean tookNamed = ItemStack.isSameItemSameComponents(taken, named);
        helper.assertTrue(tookPlain != tookNamed, "taken stack matches neither or both originals");
        ItemStack other = tookPlain ? named : plain;
        boolean otherStayed = ItemStack.isSameItemSameComponents(storehouse.getItem(0), other)
                || ItemStack.isSameItemSameComponents(storehouse.getItem(1), other);
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
    public static void dropsContentsWhenBroken(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 7));
        helper.getLevel().destroyBlock(helper.absolutePos(STORE), false);
        helper.succeedWhen(() -> helper.assertEntityPresent(EntityType.ITEM, STORE, 2.0));
    }
}
