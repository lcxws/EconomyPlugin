package com.mohistmc.economy.api;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EconomyPlugin 对外暴露的 API 接口。
 *
 * 其他插件通过以下方式获取实例：
 * <pre>
 *   EconomyAPI api = getServer().getServicesManager().load(EconomyAPI.class);
 *   if (api != null) {
 *       double bal = api.getBalance(player);
 *   }
 * </pre>
 *
 * 也可通过软依赖 + 直接引用的方式使用（需要把本接口打包或依赖 EconomyPlugin）。
 */
public interface EconomyAPI {

    /* ============================================================
     *  基础余额操作
     * ============================================================ */

    /**
     * 获取玩家当前余额
     *
     * @param player 玩家（在线或离线）
     * @return 余额，玩家不存在时返回 0
     */
    double getBalance(OfflinePlayer player);

    /**
     * 通过 UUID 获取余额
     *
     * @param uuid 玩家 UUID
     * @return 余额，无记录时返回起始金额
     */
    double getBalance(UUID uuid);

    /**
     * 设置玩家余额
     *
     * @param player 玩家
     * @param amount 新余额（会被限制为 ≥0）
     */
    void setBalance(OfflinePlayer player, double amount);

    /**
     * 增加余额（存款）
     *
     * @param player 玩家
     * @param amount 增加数量（≤0 时忽略）
     */
    void deposit(OfflinePlayer player, double amount);

    /**
     * 减少余额（取款）
     *
     * @param player 玩家
     * @param amount 减少数量
     * @return 是否成功（余额不足时返回 false）
     */
    boolean withdraw(OfflinePlayer player, double amount);

    /**
     * 检查余额是否充足
     *
     * @param player 玩家
     * @param amount 需要数量
     * @return 是否充足
     */
    boolean hasBalance(OfflinePlayer player, double amount);

    /**
     * 转账
     *
     * @param from   转出方
     * @param to     转入方
     * @param amount 数量
     * @return 是否成功（余额不足或参数非法时返回 false）
     */
    boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount);

    /* ============================================================
     *  格式化与查询
     * ============================================================ */

    /**
     * 格式化金额显示（如 "1000 金币"）
     *
     * @param amount 金额
     * @return 格式化字符串
     */
    String format(double amount);

    /**
     * 获取货币名称
     *
     * @return 如 "金币"
     */
    String getCurrencyName();

    /**
     * 获取起始余额
     *
     * @return 新玩家的默认余额
     */
    double getStartingBalance();

    /**
     * 获取每日签到奖励金额
     *
     * @return 奖励数量
     */
    double getDailyReward();

    /* ============================================================
     *  排行榜
     * ============================================================ */

    /**
     * 获取财富排行榜
     *
     * @param limit 前 N 名
     * @return 按余额降序排列的 (UUID, 余额) 列表
     */
    List<Map.Entry<UUID, Double>> getTop(int limit);

    /* ============================================================
     *  商店 / 价格系统
     * ============================================================ */

    /**
     * 获取某物品的当前收购价（玩家卖给商店）
     *
     * @param player 玩家（用于动态定价）
     * @param material 物品类型
     * @return 单价，物品不存在或不收购返回 -1
     */
    double getSellPrice(Player player, Material material);

    /**
     * 获取某物品的当前出售价（商店卖给玩家）
     *
     * @param player 玩家（用于动态定价）
     * @param material 物品类型
     * @return 单价，物品不存在或不出售返回 -1
     */
    double getBuyPrice(Player player, Material material);

    /**
     * 计算批量收购总价
     *
     * @param player 玩家
     * @param material 物品类型
     * @param amount 数量
     * @return 总价
     */
    double calculateSellTotal(Player player, Material material, int amount);

    /**
     * 计算批量购买总价
     *
     * @param player 玩家
     * @param material 物品类型
     * @param amount 数量
     * @return 总价
     */
    double calculateBuyTotal(Player player, Material material, int amount);

    /**
     * 检查物品是否可收购
     *
     * @param material 物品类型
     * @return true 如果商店收购该物品
     */
    boolean canSell(Material material);

    /**
     * 检查物品是否可购买
     *
     * @param material 物品类型
     * @return true 如果商店出售该物品
     */
    boolean canBuy(Material material);

    /**
     * 获取所有可交易物品的分类列表
     *
     * @return 分类名称列表
     */
    List<String> getCategories();

    /* ============================================================
     *  每日签到
     * ============================================================ */

    /**
     * 获取玩家上次签到时间戳
     *
     * @param player 玩家
     * @return 时间戳（毫秒），未签到过返回 0
     */
    long getLastDailyClaimTime(OfflinePlayer player);

    /**
     * 检查玩家今天是否已签到
     *
     * @param player 玩家
     * @return true 如果今天已签到
     */
    boolean hasClaimedDaily(OfflinePlayer player);

    /**
     * 执行签到（发放奖励）
     *
     * @param player 玩家
     * @return 是否成功签到（今天已签到则返回 false）
     */
    boolean claimDaily(Player player);

    /* ============================================================
     *  插件状态
     * ============================================================ */

    /**
     * 获取插件版本
     *
     * @return 版本号字符串
     */
    String getVersion();

    /**
     * 重载 EconomyPlugin 配置
     * （通常仅管理员使用）
     */
    void reload();
}
