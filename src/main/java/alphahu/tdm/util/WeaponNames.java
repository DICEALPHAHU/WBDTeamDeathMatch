package alphahu.tdm.util;

import alphahu.tdm.WBDTeamDeathmatch;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 武器显示名对照表。
 *
 * <h2>为什么要有一份自己的</h2>
 *
 * <p>击杀播报里写「twx:twx_kcb」没人看得懂。WBDAddon 有一份
 * {@code weapon-names.yml}，但死斗不该依赖它——没装 WBDAddon 的服务器
 * 也应该能显示中文枪名。
 *
 * <h2>优先级</h2>
 *
 * <ol>
 *   <li>本插件 {@code plugins/WBDTeamDeathmatch/weapon-names.yml}</li>
 *   <li>WBDAddon 的 {@code plugins/WBDAddon/weapon-names.yml}（只补本地没有的键）</li>
 *   <li>物品自己的显示名</li>
 *   <li>GunId 本身（把 {@code twx:twx_kcb} 这种拆成人看的字符串）</li>
 * </ol>
 *
 * <p>两边同时装着时**本插件优先**：本地写了就以本地为准，WBDAddon 那份只兜底，
 * 免得改了这边的名字又被对面的旧表盖回去。
 *
 * @author AlphaHu
 */
public final class WeaponNames {

    /** GunId -> 显示名。键统一小写，写法里带不带命名空间都能命中。 */
    private final Map<String, String> table = new HashMap<>();

    private final WBDTeamDeathmatch plugin;
    private final File file;

    /** TacZ 桥接（反射持有）。 */
    private Object taczApi;
    private Method gunIdMethod;
    private Method gunIdsMethod;
    private boolean probed;

    public WeaponNames(WBDTeamDeathmatch plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "weapon-names.yml");
        reload();
    }

    public void reload() {
        table.clear();
        if (!file.exists()) {
            writeTemplate();
        }
        readTable(file);
        inheritFromWbdAddon();
        plugin.getLogger().info("武器名称表已载入：" + table.size() + " 条。");
    }

    public int size() {
        return table.size();
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 玩家手里那件东西的显示名（击杀播报用）。 */
    public String resolve(Player player) {
        if (player == null) return "未知";
        return resolve(player.getInventory().getItemInMainHand());
    }

    /** 一件物品的显示名。 */
    public String resolve(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return "未知";

        String gunId = gunIdOf(stack);
        if (gunId != null) {
            String name = lookup(gunId);
            if (name != null) return name;
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String display = Text.color(meta.getDisplayName());
            if (!display.isBlank()) return display;
        }
        if (gunId != null) {
            return humanize(gunId);
        }
        return friendlyMaterial(stack.getType());
    }

    private String lookup(String gunId) {
        String key = gunId.toLowerCase(Locale.ROOT);
        String name = table.get(key);
        if (name != null) return name;
        String bare = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        return table.get(bare);
    }

    /**
     * 读 GunId。装了 TacZ 桥接才有；没装返回 null，走显示名兜底。
     */
    public String gunIdOf(ItemStack stack) {
        probeTacz();
        if (taczApi == null || gunIdMethod == null) return null;
        try {
            Object result = gunIdMethod.invoke(taczApi, stack);
            if (result instanceof java.util.Optional<?> optional && optional.isPresent()) {
                return String.valueOf(optional.get());
            }
        } catch (Throwable ignored) {
            // 不是枪 / 版本差异
        }
        return null;
    }

    private void probeTacz() {
        if (probed) return;
        probed = true;
        try {
            Class<?> apiClass = Class.forName("com.tacz.bridge.spigot.api.TacZSpigotApi");
            Object service = plugin.getServer().getServicesManager().load(apiClass);
            if (service == null) return;
            taczApi = service;
            gunIdMethod = service.getClass().getMethod("getItemGunId", ItemStack.class);
            gunIdsMethod = service.getClass().getMethod("getGunIds");
        } catch (Throwable ignored) {
            taczApi = null;
        }
    }

    /** 把 {@code twx:twx_kcb} 变成 {@code twx_kcb}，至少别再显示一长串命名空间。 */
    private String humanize(String gunId) {
        String bare = gunId.contains(":") ? gunId.substring(gunId.indexOf(':') + 1) : gunId;
        return bare.replace('_', ' ').trim();
    }

    /** 没有 GunId 时的兜底：材质名的可读形式。 */
    private String friendlyMaterial(Material material) {
        String raw = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (raw.isEmpty()) return "未知";
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    private void readTable(File source) {
        if (!source.isFile()) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration yaml =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(source);
            var section = yaml.getConfigurationSection("items");
            if (section == null) return;
            for (String key : section.getKeys(false)) {
                String name = section.getString(key);
                if (name == null || name.isBlank()) continue;
                table.put(key.trim().toLowerCase(Locale.ROOT), name.trim());
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("读取武器名称表失败（" + source.getName() + "）：" + t);
        }
    }

    /** WBDAddon 那份只补本地没有的键。 */
    private void inheritFromWbdAddon() {
        if (!plugin.getConfig().getBoolean("weapon-names.inherit-wbdaddon", true)) return;
        File pluginsDir = plugin.getDataFolder().getParentFile();
        if (pluginsDir == null) return;
        File source = new File(new File(pluginsDir, "WBDAddon"), "weapon-names.yml");
        if (!source.isFile()) return;

        int before = table.size();
        Map<String, String> inherited = new HashMap<>();
        try {
            org.bukkit.configuration.file.YamlConfiguration yaml =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(source);
            var section = yaml.getConfigurationSection("items");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    String name = section.getString(key);
                    if (name == null || name.isBlank()) continue;
                    String normalized = key.trim().toLowerCase(Locale.ROOT);
                    if (table.containsKey(normalized)) continue;
                    inherited.put(normalized, name.trim());
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("读取 WBDAddon 的 weapon-names.yml 失败：" + t);
            return;
        }
        table.putAll(inherited);
        int added = table.size() - before;
        if (added > 0) {
            plugin.getLogger().info("已从 WBDAddon 继承 " + added + " 条武器名称。");
        }
    }

    // ------------------------------------------------------------------
    // 模板
    // ------------------------------------------------------------------

    private void writeTemplate() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ==========================================================\n");
        sb.append("# 武器显示名\n");
        sb.append("# ----------------------------------------------------------\n");
        sb.append("# 击杀播报、HUD 里显示的枪名。键是 TacZ 的 GunId（带不带命名空间都行），\n");
        sb.append("# 值是显示名。没写的键会自动去 WBDAddon 那份 weapon-names.yml 找，\n");
        sb.append("# 两边都没有时退回物品自己的显示名。\n");
        sb.append("#\n");
        sb.append("# 本插件的配置优先：这里写了的不会被 WBDAddon 那份覆盖。\n");
        sb.append("# 改完 /tdm reload 生效。\n");
        sb.append("# ==========================================================\n\n");
        sb.append("items:\n");

        java.util.Set<String> ids = knownGunIds();
        if (ids.isEmpty()) {
            sb.append("  # 示例：twx:twx_kcb: 克莱伯狙击枪\n");
            sb.append("  #       tacz:ak47: AK-47\n");
        } else {
            for (String id : ids) {
                sb.append("  # ").append(id).append(": ")
                        .append(humanize(id)).append("\n");
            }
        }

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
            plugin.getLogger().info("已生成武器名称模板：weapon-names.yml");
        } catch (IOException e) {
            plugin.getLogger().warning("生成 weapon-names.yml 失败：" + e.getMessage());
        }
    }

    /** 从 TacZ 桥接拿全部 GunId，写进模板当注释，方便服主照着填。 */
    private java.util.Set<String> knownGunIds() {
        probeTacz();
        if (taczApi == null || gunIdsMethod == null) return java.util.Set.of();
        try {
            Object result = gunIdsMethod.invoke(taczApi);
            if (result instanceof java.util.Collection<?> collection) {
                java.util.List<String> ids = new java.util.ArrayList<>();
                for (Object id : collection) {
                    ids.add(String.valueOf(id));
                }
                ids.sort(String::compareTo);
                return new java.util.LinkedHashSet<>(ids);
            }
        } catch (Throwable ignored) {
            // 读不到就算了，模板里留示例
        }
        return java.util.Set.of();
    }
}
