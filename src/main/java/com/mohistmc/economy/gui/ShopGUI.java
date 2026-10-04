package com.mohistmc.economy.gui;

import com.mohistmc.economy.EconomyPlugin;
import com.mohistmc.economy.manager.PriceManager;
import com.mohistmc.economy.manager.PriceManager.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * 商店GUI (6行 — 54格)
 *
 * 行1 (0-8):  [分类x5] [搜索] [全部] [ ] [余额]
 * 行2-5 (9-44): 商品 (36格/页)
 * 行6 (45-53):  [←上一页] [ ] [ ] [ ] [下一页] [ ] [页码] [✕关闭]
 *
 * 确认GUI (2行):
 * 行1 (0-4): 数量按钮 [1/8/16/32/64]
 * 行2: [返回] [ ] [物品展示]
 */
public class ShopGUI implements Listener {

    private final EconomyPlugin plugin;
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int ITEMS_PER_PAGE = 36;

    private static final String SHOP_TITLE_PREFIX   = "§8[商店]";
    private static final String CONFIRM_BUY_PREFIX   = "§a[购买]";
    private static final String CONFIRM_SELL_PREFIX  = "§c[出售]";

    private final Map<UUID, GUIState> states = new HashMap<>();

    public ShopGUI(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    //  打开
    // ============================================================

    public void open(Player player) {
        open(player, null, 1);
    }

    public void open(Player player, String category, int page) {
        GUIState state = new GUIState(category, page);
        states.put(player.getUniqueId(), state);

        Inventory inv = Bukkit.createInventory(null, SIZE, SHOP_TITLE_PREFIX);
        render(inv, player, state);
        player.openInventory(inv);
    }

    // ============================================================
    //  渲染
    // ============================================================

    private void render(Inventory inv, Player player, GUIState state) {
        inv.clear();

        UUID uid = player.getUniqueId();
        PriceManager pm = plugin.getPrices();

        List<ShopItem> displayItems;
        if (state.category == null) {
            displayItems = pm.getAll();
        } else {
            displayItems = pm.getByCategory(state.category);
        }

        int totalItems = displayItems.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / ITEMS_PER_PAGE));
        if (state.page > totalPages) state.page = totalPages;
        if (state.page < 1) state.page = 1;

        // ── 行1: 分类 ──
        List<String> categories = pm.getCategories();
        int catCount = Math.min(categories.size(), 5);
        for (int i = 0; i < catCount; i++) {
            String cat = categories.get(i);
            boolean active = cat.equals(state.category);
            inv.setItem(i, active
                    ? makeItem(Material.OAK_WOOD, "§a▶ " + cat, "§7当前分类")
                    : makeItem(Material.OAK_WOOD, "§f" + cat, "§7点击切换"));
        }
        inv.setItem(5, makeItem(Material.COMPASS, "§b§l搜索", "§7点击后输入关键词"));
        boolean allActive = (state.category == null);
        inv.setItem(6, allActive
                ? makeItem(Material.HOPPER, "§a▶ 全部商品", "§7当前")
                : makeItem(Material.HOPPER, "§f全部商品", "§7点击查看全部"));

        double bal = plugin.getEconomy().getBalance(player);
        inv.setItem(8, makeItem(Material.GOLD_NUGGET,
                "§e§l余额: §6" + String.format("%.0f", bal),
                "§7" + plugin.getEconomy().currencyName()));

        // ── 行2-5: 商品 ──
        int startIdx = (state.page - 1) * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, totalItems);
        int slot = 9;
        for (int i = startIdx; i < endIdx; i++) {
            ShopItem si = displayItems.get(i);
            Material m = si.material;
            inv.setItem(slot++, si.createIcon(
                    pm.getCurrentBuyPrice(uid, m),
                    pm.getCurrentSellPrice(uid, m),
                    pm.getPlayerBought(uid, m),
                    pm.getPlayerSold(uid, m)));
        }

        // ── 行6: 导航 ──
        if (state.page > 1) {
            inv.setItem(45, makeItem(Material.ARROW, "§a◀ 上一页", "§7第 " + (state.page - 1) + " 页"));
        } else {
            inv.setItem(45, makeItem(Material.GRAY_DYE, "§8◀ 已是首页"));
        }
        if (state.page < totalPages) {
            inv.setItem(49, makeItem(Material.ARROW, "§a下一页 ▶", "§7第 " + (state.page + 1) + " 页"));
        } else {
            inv.setItem(49, makeItem(Material.GRAY_DYE, "§8▶ 已是末页"));
        }
        inv.setItem(52, makeItem(Material.PAPER,
                "§f第 §e" + state.page + "§f/§e" + totalPages + " §f页",
                "§7共 " + totalItems + " 件商品"));
        inv.setItem(53, makeItem(Material.BARRIER, "§c§l关闭商店"));
    }

    // ============================================================
    //  事件处理
    // ============================================================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player player = (Player) e.getWhoClicked();
        UUID uid = player.getUniqueId();

        String title = e.getView().getTitle();

        if (title.startsWith(CONFIRM_BUY_PREFIX) || title.startsWith(CONFIRM_SELL_PREFIX)) {
            e.setCancelled(true);
            if (e.getRawSlot() >= 18) return;
            handleConfirmClick(player, e, e.getInventory());
            return;
        }

        if (!title.startsWith(SHOP_TITLE_PREFIX)) return;

        e.setCancelled(true);
        if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;

        int slot = e.getRawSlot();
        if (slot >= SIZE) return;

        ItemStack clicked = e.getCurrentItem();
        Material mat = clicked.getType();
        GUIState state = states.get(uid);
        if (state == null) return;

        if (slot == 5 && mat == Material.COMPASS) {
            player.sendMessage("§c商店搜索功能已移除");
            return;
        }
        if (slot == 6 && mat == Material.HOPPER) {
            open(player, null, 1);
            return;
        }
        if (slot >= 0 && slot < 5 && mat == Material.OAK_WOOD) {
            List<String> cats = plugin.getPrices().getCategories();
            if (slot < cats.size()) {
                String clickedCat = cats.get(slot);
                open(player, clickedCat.equals(state.category) ? null : clickedCat, 1);
            }
            return;
        }
        if (slot == 45 && mat == Material.ARROW) {
            open(player, state.category, state.page - 1);
            return;
        }
        if (slot == 49 && mat == Material.ARROW) {
            open(player, state.category, state.page + 1);
            return;
        }
        if (slot == 53 && mat == Material.BARRIER) {
            player.closeInventory();
            return;
        }

        if (slot >= 9 && slot <= 44) {
            ShopItem shopItem = plugin.getPrices().find(clicked);
            if (shopItem == null) return;

            if (e.isShiftClick() && e.isLeftClick()) {
                doBuy(player, shopItem, 64);
            } else if (e.isShiftClick() && e.isRightClick()) {
                doSellAll(player, shopItem);
            } else if (e.isLeftClick()) {
                openConfirmGUI(player, shopItem, false);
            } else if (e.isRightClick()) {
                openConfirmGUI(player, shopItem, true);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        String title = e.getView().getTitle();
        if (title.startsWith(SHOP_TITLE_PREFIX)
                || title.startsWith(CONFIRM_BUY_PREFIX) || title.startsWith(CONFIRM_SELL_PREFIX)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
    }

    // ============================================================
    //  确认GUI
    // ============================================================

    @SuppressWarnings("unchecked")
    private void openConfirmGUI(Player player, ShopItem item, boolean isSell) {
        UUID uid = player.getUniqueId();
        PriceManager pm = plugin.getPrices();
        String title = (isSell ? CONFIRM_SELL_PREFIX : CONFIRM_BUY_PREFIX) + item.getChineseName();
        Inventory inv = Bukkit.createInventory(null, 18, title);

        double currentBuy  = pm.getCurrentBuyPrice(uid, item.material);
        double currentSell = pm.getCurrentSellPrice(uid, item.material);
        double bought      = pm.getPlayerBought(uid, item.material);
        double sold        = pm.getPlayerSold(uid, item.material);

        int[] amounts = {1, 8, 16, 32, 64};
        for (int i = 0; i < 5; i++) {
            int amount = amounts[i];
            if (isSell) {
                double batchTotal = pm.calculateSellTotal(uid, item.material, amount);
                String price = item.canSell() && batchTotal >= 0
                        ? "§a出售价: §6" + formatTotal(batchTotal) + " 金币"
                        : "§c暂不出售";
                inv.setItem(i, makeItem(Material.RED_WOOL,
                        "§c§l出售 x" + amount, price, "§7点击出售 " + amount + " 个"));
            } else {
                double batchTotal = pm.calculateBuyTotal(uid, item.material, amount);
                String price = item.canBuy() && batchTotal >= 0
                        ? "§a需花费: §6" + formatTotal(batchTotal) + " 金币"
                        : "§c暂不收购";
                inv.setItem(i, makeItem(Material.LIME_WOOL,
                        "§a§l购买 x" + amount, price, "§7点击购买 " + amount + " 个"));
            }
        }
        inv.setItem(13, makeItem(Material.ARROW, "§e§l返回"));

        inv.setItem(15, item.createIcon(currentBuy, currentSell, bought, sold));

        GUIState oldState = states.get(uid);
        String lastCat = oldState != null ? oldState.category : null;
        int lastPage = oldState != null ? oldState.page : 1;

        Map<String, Object> meta = new HashMap<>();
        meta.put("item", item);
        meta.put("isSell", isSell);
        meta.put("category", lastCat);
        meta.put("page", lastPage);
        GUIState gs = new GUIState(lastCat, lastPage);
        gs.confirmData = meta;
        states.put(uid, gs);

        player.openInventory(inv);
    }

    @SuppressWarnings("unchecked")
    private void handleConfirmClick(Player player, InventoryClickEvent e, Inventory inv) {
        if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;

        GUIState gs = states.get(player.getUniqueId());
        if (gs == null || gs.confirmData == null) return;

        Map<String, Object> meta = (Map<String, Object>) gs.confirmData;
        ShopItem item = (ShopItem) meta.get("item");
        boolean isSell = (boolean) meta.get("isSell");
        String category = (String) meta.get("category");
        int page = (int) meta.get("page");
        int slot = e.getRawSlot();

        if (slot == 13 && e.getCurrentItem().getType() == Material.ARROW) {
            open(player, category, page);
            return;
        }

        if (slot >= 0 && slot <= 4) {
            int[] amounts = {1, 8, 16, 32, 64};
            int amount = amounts[slot];

            if (isSell) {
                if (item.canSell()) doSell(player, item, amount);
                else player.sendMessage("§c该物品暂不出售！");
            } else {
                if (item.canBuy()) doBuy(player, item, amount);
                else player.sendMessage("§c该物品暂不收购！");
            }
            open(player, category, page);
        }
    }

    // ============================================================
    //  交易
    // ============================================================

    private void doBuy(Player player, ShopItem item, int amount) {
        UUID uid = player.getUniqueId();
        PriceManager pm = plugin.getPrices();
        if (!item.canBuy()) { player.sendMessage("§c该物品不出售！"); return; }
        double total = pm.calculateBuyTotal(uid, item.material, amount);
        if (!plugin.getEconomy().hasBalance(player, total)) {
            player.sendMessage("§c余额不足！需要 " + plugin.getEconomy().format(total));
            return;
        }
        Material mat = item.material;
        int maxStack = mat.getMaxStackSize();
        int stacks = (amount + maxStack - 1) / maxStack;
        if (countEmptySlots(player) < stacks) {
            player.sendMessage("§c背包空间不足！需要 " + stacks + " 格空位");
            return;
        }
        plugin.getEconomy().withdraw(player, total);
        int remaining = amount;
        while (remaining > 0) {
            int take = Math.min(remaining, maxStack);
            player.getInventory().addItem(item.createItem(take));
            remaining -= take;
        }
        // ★ 逐玩家记录 → 该玩家的 bought++
        pm.recordTransaction(uid, mat, amount, false);
        player.sendMessage("§a✓ 购买 §f" + item.getChineseName() + " §7x" + amount
                + " §a花费 §e" + plugin.getEconomy().format(total));
    }

    private void doSell(Player player, ShopItem item, int amount) {
        UUID uid = player.getUniqueId();
        PriceManager pm = plugin.getPrices();
        if (!item.canSell()) { player.sendMessage("§c商店暂不收购！"); return; }
        double total = pm.calculateSellTotal(uid, item.material, amount);
        if (total < 0) { player.sendMessage("§c商店暂不收购！"); return; }
        int has = countItem(player, item);
        if (has < amount) {
            player.sendMessage("§c你没有足够的 " + item.getChineseName()
                    + " §c(需 " + amount + "，有 " + has + ")");
            return;
        }
        removeItem(player, item, amount);
        plugin.getEconomy().deposit(player, total);
        // ★ 逐玩家记录 → 该玩家的 sold++
        pm.recordTransaction(uid, item.material, amount, true);
        player.sendMessage("§a✓ 出售 §f" + item.getChineseName() + " §7x" + amount
                + " §a获得 §e" + plugin.getEconomy().format(total));
    }

    private void doSellAll(Player player, ShopItem item) {
        UUID uid = player.getUniqueId();
        PriceManager pm = plugin.getPrices();
        if (!item.canSell()) { player.sendMessage("§c商店暂不收购！"); return; }
        int totalInInv = countItem(player, item);
        if (totalInInv <= 0) {
            player.sendMessage("§c你身上没有 " + item.getChineseName());
            return;
        }
        double totalEarn = pm.calculateSellTotal(uid, item.material, totalInInv);
        removeItem(player, item, totalInInv);
        plugin.getEconomy().deposit(player, totalEarn);
        pm.recordTransaction(uid, item.material, totalInInv, true);
        player.sendMessage("§a✓ 出售 §f" + item.getChineseName() + " §7x" + totalInInv
                + " §a获得 §e" + plugin.getEconomy().format(totalEarn));
    }

    // ============================================================
    //  工具
    // ============================================================

    private int countItem(Player player, ShopItem shopItem) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && shopItem.matches(item)) count += item.getAmount();
        }
        return count;
    }

    private void removeItem(Player player, ShopItem shopItem, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !shopItem.matches(item)) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
        }
    }

    private int countEmptySlots(Player player) {
        int empty = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) empty++;
        }
        return empty;
    }

    private String formatTotal(double amount) {
        if (amount == (long) amount) return String.format("%,d", (long) amount);
        return String.format("%,.1f", amount);
    }

    private ItemStack makeItem(Material mat, String name, String... loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (loreLines.length > 0 && loreLines[0] != null && !loreLines[0].isEmpty()) {
                List<String> lore = new ArrayList<>();
                for (String line : loreLines) {
                    if (line != null && !line.isEmpty()) lore.add(line);
                }
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    // ============================================================
    //  状态
    // ============================================================

    public static class GUIState {
        public String category;
        public int page;
        public Object confirmData = null;
        public GUIState(String category, int page) {
            this.category = category;
            this.page = page;
        }
    }
}
