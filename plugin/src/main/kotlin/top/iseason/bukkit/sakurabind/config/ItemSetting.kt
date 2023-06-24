package top.iseason.bukkit.sakurabind.config

import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.HumanEntity
import org.bukkit.inventory.ItemStack
import top.iseason.bukkit.sakurabind.config.matcher.BaseMatcher
import top.iseason.bukkit.sakurabind.config.matcher.MatcherManager

open class ItemSetting(override val keyPath: String, protected val section: ConfigurationSection) : BaseSetting {

    var setting: ConfigurationSection
    final override val matchers: List<BaseMatcher>

    init {
        val matcherSection = section.getConfigurationSection("match") ?: section.createSection("match")
        matchers = MatcherManager.parseSection(matcherSection)
        setting = section.getConfigurationSection("settings") ?: section.createSection("settings")
    }

    override fun match(item: ItemStack): Boolean {
        return match(item, null)
    }

    override fun match(item: ItemStack, sender: CommandSender?): Boolean {
        if (sender == null) return matchers.all { it.tryMatch(item) }
        else matchers.forEach { it.onDebug(item, sender) }
        return matchers.all { it.tryMatch(item) }
    }

    override fun getString(key: String): String {
        return setting.getString(key, GlobalSettings.config.getString(key)) ?: ""
    }

    override fun getStringList(key: String): List<String> {
        return if (setting.contains(key))
            setting.getStringList(key)
        else GlobalSettings.config.getStringList(key)
    }

    override fun getInt(key: String): Int = setting.getInt(key, GlobalSettings.config.getInt(key))

    override fun getLong(key: String): Long = setting.getLong(key, GlobalSettings.config.getLong(key))
    override fun getDouble(key: String): Double = setting.getDouble(key, GlobalSettings.config.getDouble(key))

    override fun getBoolean(key: String, owner: String?, player: HumanEntity?): Boolean {
        //权限检查
        if (Config.enable_setting_permission_check && player != null) {
            if (player.hasPermission("sakurabind.setting.$keyPath.$key.true")) {
                return true
            } else if (player.hasPermission("sakurabind.setting.$keyPath.$key.false")) {
                return false
            } else if (player.hasPermission("sakurabind.settings.$key.true")) {
                return true
            } else if (player.hasPermission("sakurabind.settings.$key.false")) {
                return false
            }
        }
        //是物主或者拥有物主的权限
        var isOwner = false
        if (owner != null && player != null) {
            isOwner =
                (owner == player.uniqueId.toString()) || player.hasPermission("sakurabind.bypass.$owner") == true
            if (isOwner && setting.contains("$key@")) {
                return !setting.getBoolean("$key@")
            }
        }
        if (setting.contains(key)) return setting.getBoolean(key)
        if (GlobalSettings.config.contains("$key@")) {
            return if (isOwner)
                !GlobalSettings.config.getBoolean("$key@")
            else
                GlobalSettings.config.getBoolean("$key@")
        }
        return GlobalSettings.config.getBoolean(key)
    }

    override fun clone(): BaseSetting {
        val yamlConfiguration = YamlConfiguration()
        yamlConfiguration.set("clone", section)
        val reader = yamlConfiguration.saveToString().reader()
        val newSection = YamlConfiguration.loadConfiguration(reader).getConfigurationSection("clone")!!
        return ItemSetting(keyPath, newSection)
    }

}