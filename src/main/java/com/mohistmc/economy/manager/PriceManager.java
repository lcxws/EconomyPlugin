package com.mohistmc.economy.manager;

import com.mohistmc.economy.EconomyPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 价格管理器 — 逐玩家指数动态定价，每物品独立变化率
 *
 * prices.yml 格式:
 *   物品名: [售出价, 买入价, "分类", 售出变化率%, 买入变化率%]
 *   - 位置0 售出价 = 商店卖给玩家的基础价
 *   - 位置1 买入价 = 商店从玩家收购的基础价
 *   - 位置3 售出变化率% = 该玩家每买 1 个，买入价上涨此% (指数计算)
 *   - 位置4 买入变化率% = 该玩家每卖 1 个，卖出价下跌此% (指数计算)
 *
 * 公式:
 *   buyPrice  = baseBuy  × clamp((1 + buyRate%/100)^bought,  min, max)
 *   sellPrice = baseSell × clamp((1 - sellRate%/100)^sold,   min, max)
 *
 * 例: OAK_LOG: [10, 8, "原木", 0.5, 1.0]
 *   该玩家买 100 个 → 1.005^100=1.65x → 买入价 10×1.65=16.5
 *   该玩家卖 100 个 → 0.99^100=0.37x → 卖出价 8×0.37=2.96
 *
 * 衰减: 30min 无交易 → 该物品该玩家的 bought/sold 各 × 0.9
 */
public class PriceManager {

    private final EconomyPlugin plugin;
    private File pricesFile;
    private File dataFile;
    private YamlConfiguration pricesConfig;
    private YamlConfiguration dataConfig;

    private final List<ShopItem> items = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<ShopItem>> byCategory = new ConcurrentHashMap<>();
    private final Map<Material, ShopItem> byMaterial = new ConcurrentHashMap<>();
    private final Map<String, ShopItem> byId = new ConcurrentHashMap<>();
    private final List<String> categories = Collections.synchronizedList(new ArrayList<>());

    /** 商店物品 NBT 标识键 (写入 custom_data / PublicBukkitValues) */
    private static NamespacedKey SHOP_ID_KEY;

    private double decayFactor;
    private long decayIntervalMs;
    private double minMultiplier;
    private double maxMultiplier;

    /** 逐玩家: UUID → (Material → [bought, sold, lastTrade])  — 并发安全 */
    private final Map<UUID, Map<Material, double[]>> playerData = new ConcurrentHashMap<>();

    public PriceManager(EconomyPlugin plugin) {
        this.plugin = plugin;
        if (SHOP_ID_KEY == null) SHOP_ID_KEY = new NamespacedKey(plugin, "shop_id");
        reload();
    }

    /* ============================================================
     *  加载 / 保存
     * ============================================================ */

    public void reload() {
        items.clear();
        byCategory.clear();
        byMaterial.clear();
        byId.clear();
        playerData.clear();

        ConfigurationSection cfg = plugin.getConfig();
        this.decayFactor     = cfg.getDouble("price.decay-factor", 0.9);
        this.decayIntervalMs = cfg.getLong("price.decay-interval-minutes", 30) * 60 * 1000L;
        this.minMultiplier   = cfg.getDouble("price.min-multiplier", 0.1);
        this.maxMultiplier   = cfg.getDouble("price.max-multiplier", 5.0);

        categories.clear();
        List<String> cfgCategories = plugin.getConfig().getStringList("shop.categories");
        if (cfgCategories != null) categories.addAll(cfgCategories);

        loadPlayerData();

        pricesFile = new File(plugin.getDataFolder(), "prices.yml");
        if (!pricesFile.exists()) plugin.saveResource("prices.yml", false);
        pricesConfig = YamlConfiguration.loadConfiguration(pricesFile);

        ConfigurationSection sec = pricesConfig.getConfigurationSection("prices");
        if (sec == null) {
            plugin.getLogger().warning("[Economy] prices.yml 中没有找到 prices 节点");
            return;
        }

        for (String key : sec.getKeys(false)) {
            Material mat = Material.getMaterial(key.toUpperCase());
            if (mat == null) { plugin.getLogger().warning("[Economy] 未知物品: " + key); continue; }

            List<?> list = sec.getList(key);
            if (list == null || list.size() < 2) continue;

            double buy      = toDouble(list.get(0));
            double sell     = toDouble(list.get(1));
            String category = list.size() >= 3 && list.get(2) != null ? list.get(2).toString() : "未分类";
            double buyRate  = list.size() >= 4 ? toDouble(list.get(3)) : 0;
            double sellRate = list.size() >= 5 ? toDouble(list.get(4)) : 0;

            ShopItem si = new ShopItem(key, mat, buy, sell, category, buyRate, sellRate);
            items.add(si);
            byMaterial.put(mat, si);
            byId.put(key, si);
        }

        for (ShopItem item : items)
            byCategory.computeIfAbsent(item.category, k -> new ArrayList<>()).add(item);
        for (String cat : categories)
            byCategory.putIfAbsent(cat, new ArrayList<>());

        // 分类下的商品列表也改为线程安全
        byCategory.replaceAll((k, v) -> Collections.synchronizedList(new ArrayList<>(v)));

        int dyn = (int) items.stream().filter(i -> i.buyRate > 0 || i.sellRate > 0).count();
        plugin.getLogger().info("[Economy] 已加载 " + items.size() + " 种商品, "
                + byCategory.size() + " 分类, " + dyn + " 动态定价"
                + " | 衰减=" + decayFactor + "/" + (decayIntervalMs / 60000) + "min");
    }

    private void loadPlayerData() {
        playerData.clear();
        dataFile = new File(plugin.getDataFolder(), "price_data.yml");
        if (!dataFile.exists()) { dataConfig = new YamlConfiguration(); return; }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        if (dataConfig == null) { dataConfig = new YamlConfiguration(); return; }

        for (String uuidKey : dataConfig.getKeys(false)) {
            UUID uid;
            try { uid = UUID.fromString(uuidKey); } catch (Exception e) { continue; }
            ConfigurationSection ps = dataConfig.getConfigurationSection(uuidKey);
            if (ps == null) continue;

            Map<Material, double[]> mm = new ConcurrentHashMap<>();
            for (String mk : ps.getKeys(false)) {
                Material mat = Material.getMaterial(mk.toUpperCase());
                if (mat == null) continue;
                double b = ps.getDouble(mk + ".bought", ps.getDouble(mk + ".total_bought", 0));
                double s = ps.getDouble(mk + ".sold",   ps.getDouble(mk + ".total_sold", 0));
                long   t = ps.getLong(mk + ".last_trade", 0);
                mm.put(mat, new double[]{b, s, (double) t});
            }
            if (!mm.isEmpty()) playerData.put(uid, mm);
        }
    }

    public void savePlayerData() {
        if (dataFile == null) dataFile = new File(plugin.getDataFolder(), "price_data.yml");
        dataConfig = new YamlConfiguration();
        for (var pu : playerData.entrySet()) {
            String uk = pu.getKey().toString();
            for (var me : pu.getValue().entrySet()) {
                String mk = me.getKey().name().toLowerCase();
                double[] d = me.getValue();
                dataConfig.set(uk + "." + mk + ".bought",     d[0]);
                dataConfig.set(uk + "." + mk + ".sold",       d[1]);
                dataConfig.set(uk + "." + mk + ".last_trade", (long) d[2]);
            }
        }
        try { dataConfig.save(dataFile); } catch (IOException ex) {
            plugin.getLogger().severe("[Economy] 保存 price_data.yml 失败: " + ex.getMessage());
        }
    }

    /* ============================================================
     *  逐玩家指数价格
     * ============================================================ */

    private double[] getStats(UUID uid, Material mat) {
        Map<Material, double[]> mm = playerData.get(uid);
        return mm != null ? mm.get(mat) : null;
    }

    public double getPlayerBought(UUID uid, Material mat) {
        double[] d = getStats(uid, mat); return d != null ? d[0] : 0;
    }
    public double getPlayerSold(UUID uid, Material mat) {
        double[] d = getStats(uid, mat); return d != null ? d[1] : 0;
    }

    /** 该玩家买入价倍率 = (1 + buyRate%/100)^bought */
    public double getBuyMultiplier(UUID uid, Material mat) {
        ShopItem si = byMaterial.get(mat);
        if (si == null || si.buyRate == 0) return 1.0;
        return Math.pow(1.0 + si.buyRate / 100.0, getPlayerBought(uid, mat));
    }

    /** 该玩家卖出价倍率 = (1 - sellRate%/100)^sold */
    public double getSellMultiplier(UUID uid, Material mat) {
        ShopItem si = byMaterial.get(mat);
        if (si == null || si.sellRate == 0) return 1.0;
        return Math.pow(1.0 - si.sellRate / 100.0, getPlayerSold(uid, mat));
    }

    public double getCurrentBuyPrice(UUID uid, Material mat) {
        ShopItem si = byMaterial.get(mat);
        if (si == null || si.baseBuy < 0) return -1;
        double mult = Math.max(minMultiplier, Math.min(maxMultiplier, getBuyMultiplier(uid, mat)));
        return si.baseBuy * mult;
    }

    public double getCurrentSellPrice(UUID uid, Material mat) {
        ShopItem si = byMaterial.get(mat);
        if (si == null || si.baseSell < 0) return -1;
        double mult = Math.max(minMultiplier, Math.min(maxMultiplier, getSellMultiplier(uid, mat)));
        return si.baseSell * mult;
    }

    /**
     * 批量购买总价 (等比级数求和，每件逐次涨价)
     * total = baseBuy×(1+r)^bought × ((1+r)^amount-1)/r
     */
    public double calculateBuyTotal(UUID uid, Material mat, int amount) {
        ShopItem si = byMaterial.get(mat);
        if (si == null || si.baseBuy < 0) return -1;
        if (si.buyRate == 0 || amount <= 0) return si.baseBuy * amount;
        double r = si.buyRate / 100.0;
        double bought = getPlayerBought(uid, mat);
        double first = si.baseBuy * Math.pow(1.0 + r, bought);
        double total = first * (Math.pow(1.0 + r, amount) - 1.0) / r;
        double floor = si.baseBuy * minMultiplier * amount;
        double ceil  = si.baseBuy * maxMultiplier * amount;
        return Math.max(floor, Math.min(ceil, total));
    }

    /**
     * 批量出售总价 (等比级数求和，每件逐次跌价)
     * total = baseSell×(1-r)^sold × (1-(1-r)^amount)/r
     */
    public double calculateSellTotal(UUID uid, Material mat, int amount) {
        ShopItem si = byMaterial.get(mat);
        if (si == null || si.baseSell < 0) return -1;
        if (si.sellRate == 0 || amount <= 0) return si.baseSell * amount;
        double r = si.sellRate / 100.0;
        double sold = getPlayerSold(uid, mat);
        double first = si.baseSell * Math.pow(1.0 - r, sold);
        double total = first * (1.0 - Math.pow(1.0 - r, amount)) / r;
        double floor = si.baseSell * minMultiplier * amount;
        double ceil  = si.baseSell * maxMultiplier * amount;
        return Math.max(floor, Math.min(ceil, total));
    }

    public void recordTransaction(UUID uid, Material mat, int amount, boolean isSell) {
        Map<Material, double[]> mm = playerData.computeIfAbsent(uid, k -> new ConcurrentHashMap<>());
        // 原子读改写：避免并发下 bought/sold 计数丢失（价格计算错误）
        mm.compute(mat, (k, d) -> {
            double[] cur = d != null ? d : new double[]{0, 0, 0};
            if (isSell) cur[1] += amount; // player sells → sold++
            else        cur[0] += amount; // player buys  → bought++
            cur[2] = System.currentTimeMillis();
            return cur;
        });
    }

    /** 对所有玩家的所有物品衰减 */
    public void decayAll() {
        // 防御: interval ≤ 0 时跳过，避免 elapsed/interval 除零崩溃（例如配置了 0 分钟）
        if (decayIntervalMs <= 0) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map<Material, double[]> mm : playerData.values()) {
            for (double[] d : mm.values()) {
                long last = (long) d[2];
                if (last <= 0 || (d[0] == 0 && d[1] == 0)) continue;
                long elapsed = now - last;
                if (elapsed < decayIntervalMs) continue;
                int cycles = (int) (elapsed / decayIntervalMs);
                for (int i = 0; i < cycles; i++) {
                    d[0] *= decayFactor;
                    d[1] *= decayFactor;
                }
                d[2] = last + cycles * decayIntervalMs;
                if (Math.abs(d[0]) < 0.01) d[0] = 0;
                if (Math.abs(d[1]) < 0.01) d[1] = 0;
                changed = true;
            }
        }
        if (changed) savePlayerData();
    }

    /* ============================================================
     *  查询
     * ============================================================ */

    public double getDecayFactor()  { return decayFactor; }
    public long getDecayIntervalMs(){ return decayIntervalMs; }

    public List<ShopItem> getAll() {
        // 快照拷贝：避免异步线程遍历时与 reload 产生并发修改异常
        synchronized (items) {
            return Collections.unmodifiableList(new ArrayList<>(items));
        }
    }
    public List<ShopItem> getByCategory(String cat) {
        List<ShopItem> list = byCategory.get(cat);
        if (list == null) return Collections.emptyList();
        synchronized (list) {
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
    }
    public List<String> getCategories() {
        synchronized (categories) {
            return Collections.unmodifiableList(new ArrayList<>(categories));
        }
    }
    public int getCategoryCount() { return categories.size(); }
    public ShopItem find(Material mat) { return byMaterial.get(mat); }

    /** 按物品识别: NBT 标识优先，无标签时退回材质匹配 */
    public ShopItem find(ItemStack item) {
        if (item == null) return null;
        String id = getShopId(item);
        if (id != null) {
            ShopItem si = byId.get(id);
            if (si != null) return si;
        }
        return byMaterial.get(item.getType());
    }
    public int getItemCount() { return items.size(); }

    public List<ShopItem> search(String kw) {
        String k = kw.toLowerCase();
        List<ShopItem> r = new ArrayList<>();
        synchronized (items) {
            for (ShopItem si : items)
                if (si.getChineseName().toLowerCase().contains(k) || si.category.toLowerCase().contains(k))
                    r.add(si);
        }
        return r;
    }

    /* ============================================================
     *  NBT 标识读写 (PersistentDataContainer → custom_data)
     * ============================================================ */

    /** 读取物品上的商店 NBT 标识，无标签返回 null */
    public static String getShopId(ItemStack item) {
        if (item == null || SHOP_ID_KEY == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(SHOP_ID_KEY, PersistentDataType.STRING);
    }

    /** 写入/清除物品上的商店 NBT 标识 */
    public static void setShopId(ItemStack item, String id) {
        if (item == null || SHOP_ID_KEY == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (id == null) meta.getPersistentDataContainer().remove(SHOP_ID_KEY);
        else meta.getPersistentDataContainer().set(SHOP_ID_KEY, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
    }

    private double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return -1; }
    }

    /* ============================================================
     *  ShopItem
     * ============================================================ */

    public static class ShopItem {
        /** NBT 标识 (prices.yml 键名) */
        public final String id;
        public final Material material;
        public final double baseBuy, baseSell;
        public final String category;
        public final double buyRate, sellRate; // % per item

        public ShopItem(String id, Material m, double b, double s, String cat, double br, double sr) {
            this.id = id; this.material = m; this.baseBuy = b; this.baseSell = s;
            this.category = cat; this.buyRate = br; this.sellRate = sr;
        }
        public boolean canBuy()  { return baseBuy >= 0; }
        public boolean canSell() { return baseSell >= 0; }
        public boolean hasDynBuy()  { return buyRate > 0; }
        public boolean hasDynSell() { return sellRate > 0; }

        /** 物品识别: NBT 标识优先，无标签时退回材质匹配 */
        public boolean matches(ItemStack item) {
            if (item == null) return false;
            String itemId = getShopId(item);
            if (itemId != null) return itemId.equals(id);
            return item.getType() == material;
        }

        /** 生成可交易的真实物品（纯原版、无 NBT 标签，可与常规物品堆叠；
         *  识别时无标签自动退回材质匹配） */
        public ItemStack createItem(int amount) {
            return new ItemStack(material, amount);
        }

        public String getChineseName() {
            String cn = CN_MAP.get(material);
            if (cn != null) return cn;
            StringBuilder sb = new StringBuilder();
            for (String p : material.name().toLowerCase().split("_")) {
                if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(" ");
            }
            return sb.toString().trim();
        }

        public ItemStack createIcon(double curBuy, double curSell,
                                     double bought, double sold) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return item;

            meta.setDisplayName("§f" + getChineseName());
            List<String> lore = new ArrayList<>();
            lore.add("§7分类: §b" + category);

            // 动态定价信息
            if (hasDynBuy() || hasDynSell()) {
                lore.add("§d§l⚡ 动态定价 (个人)");
                if (hasDynBuy())  lore.add(" §7购买浮动: §e" + String.format("%.1f", buyRate) + "%/个");
                if (hasDynSell()) lore.add(" §7出售浮动: §c" + String.format("%.1f", sellRate) + "%/个");
            }

            lore.add("");
            if (canBuy()) {
                // 倍率由 当前价/基础价 反推，确保与展示价格一致（价格本身已应用 min/max 钳制）
                double buyMult = baseBuy > 0 ? curBuy / baseBuy : 1.0;
                String extra = hasDynBuy() && Math.abs(buyMult - 1.0) > 0.01
                        ? " §7(×" + String.format("%.2f", buyMult) + ")" : "";
                lore.add("§7商店出售: §6" + fmt(curBuy) + " 金币/个" + extra);
            } else {
                lore.add("§7商店出售: §c不出售");
            }
            if (canSell()) {
                double sellMult = baseSell > 0 ? curSell / baseSell : 1.0;
                String extra = hasDynSell() && Math.abs(sellMult - 1.0) > 0.01
                        ? " §7(×" + String.format("%.2f", sellMult) + ")" : "";
                lore.add("§7商店收购: §a" + fmt(curSell) + " 金币/个" + extra);
            } else {
                lore.add("§7商店收购: §c不接受");
            }

            if (bought > 0 || sold > 0) {
                lore.add("");
                if (bought > 0) lore.add(" §7你已购买: §f" + nfmt(bought) + " 个");
                if (sold > 0)   lore.add(" §7你已出售: §f" + nfmt(sold) + " 个");
            }

            lore.add("");
            lore.add("§e左键 §7购买  §e右键 §7出售");
            lore.add("§eShift+左键 §7买一组  §eShift+右键 §7卖全部");
            meta.setLore(lore);
            item.setItemMeta(meta);
            return item;
        }
    }

    private static String fmt(double v) {
        return (v == (long) v) ? String.format("%,d", (long) v) : String.format("%,.1f", v);
    }
    private static String nfmt(double v) { return String.format("%,d", (long) v); }

    private static final Map<Material, String> CN_MAP = new HashMap<>();
    static {
        CN_MAP.put(Material.OAK_LOG, "橡木原木"); CN_MAP.put(Material.SPRUCE_LOG, "云杉原木");
        CN_MAP.put(Material.BIRCH_LOG, "桦木原木"); CN_MAP.put(Material.JUNGLE_LOG, "丛林原木");
        CN_MAP.put(Material.ACACIA_LOG, "金合欢原木"); CN_MAP.put(Material.DARK_OAK_LOG, "深色橡木原木");
        CN_MAP.put(Material.MANGROVE_LOG, "红树原木"); CN_MAP.put(Material.CHERRY_LOG, "樱花原木");
        CN_MAP.put(Material.CRIMSON_STEM, "绯红菌柄"); CN_MAP.put(Material.WARPED_STEM, "诡异菌柄");
        CN_MAP.put(Material.COAL, "煤炭"); CN_MAP.put(Material.REDSTONE, "红石粉");
        CN_MAP.put(Material.LAPIS_LAZULI, "青金石"); CN_MAP.put(Material.QUARTZ, "下界石英");
        CN_MAP.put(Material.AMETHYST_SHARD, "紫水晶碎片"); CN_MAP.put(Material.EMERALD, "绿宝石");
        CN_MAP.put(Material.DIAMOND, "钻石"); CN_MAP.put(Material.COPPER_INGOT, "铜锭");
        CN_MAP.put(Material.IRON_INGOT, "铁锭"); CN_MAP.put(Material.GOLD_INGOT, "金锭");
        CN_MAP.put(Material.NETHERITE_SCRAP, "下界合金碎片"); CN_MAP.put(Material.NETHERITE_INGOT, "下界合金锭");
        CN_MAP.put(Material.GOLDEN_APPLE, "金苹果"); CN_MAP.put(Material.ENCHANTED_GOLDEN_APPLE, "附魔金苹果");
        CN_MAP.put(Material.ENDER_PEARL, "末影珍珠"); CN_MAP.put(Material.ENDER_EYE, "末影之眼");
        CN_MAP.put(Material.SHULKER_SHELL, "潜影壳"); CN_MAP.put(Material.ELYTRA, "鞘翅");
        CN_MAP.put(Material.TOTEM_OF_UNDYING, "不死图腾"); CN_MAP.put(Material.SLIME_BALL, "粘液球");
        CN_MAP.put(Material.BLAZE_ROD, "烈焰棒"); CN_MAP.put(Material.GHAST_TEAR, "恶魂之泪");
        CN_MAP.put(Material.MAGMA_CREAM, "岩浆膏"); CN_MAP.put(Material.EXPERIENCE_BOTTLE, "附魔之瓶");
    }
}
