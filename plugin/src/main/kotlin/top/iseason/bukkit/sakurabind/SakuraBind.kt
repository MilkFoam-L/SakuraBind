package top.iseason.bukkit.sakurabind

import fr.xephi.authme.events.LoginEvent
import org.bstats.bukkit.Metrics
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.player.PlayerLoginEvent
import top.iseason.bukkit.sakurabind.cache.BlockCacheManager
import top.iseason.bukkit.sakurabind.cache.DelaySender
import top.iseason.bukkit.sakurabind.command.mainCommand
import top.iseason.bukkit.sakurabind.config.*
import top.iseason.bukkit.sakurabind.dto.PlayerItems
import top.iseason.bukkit.sakurabind.hook.AuthMeHook
import top.iseason.bukkit.sakurabind.hook.PlaceHolderExpansion
import top.iseason.bukkit.sakurabind.hook.SakuraMailHook
import top.iseason.bukkit.sakurabind.listener.BindListener
import top.iseason.bukkit.sakurabind.listener.BindListener194
import top.iseason.bukkit.sakurabind.listener.BlockListener
import top.iseason.bukkit.sakurabind.listener.BlockListener1132
import top.iseason.bukkittemplate.KotlinPlugin
import top.iseason.bukkittemplate.config.DatabaseConfig
import top.iseason.bukkittemplate.config.SimpleYAMLConfig
import top.iseason.bukkittemplate.debug.SimpleLogger
import top.iseason.bukkittemplate.debug.info
import top.iseason.bukkittemplate.debug.warn
import top.iseason.bukkittemplate.hook.PlaceHolderHook
import top.iseason.bukkittemplate.utils.bukkit.EventUtils.listen
import top.iseason.bukkittemplate.utils.bukkit.EventUtils.register
import top.iseason.bukkittemplate.utils.bukkit.MessageUtils.toColor

object SakuraBind : KotlinPlugin() {

    override fun onEnable() {
        Metrics(javaPlugin, 16968)
        SimpleLogger.prefix = "&a[&6${javaPlugin.description.name}&a]&r ".toColor()
        SimpleYAMLConfig.notifyMessage = "配置 %s 已重载!"
        SakuraMailHook.checkHooked()
        if (PlaceHolderHook.hasHooked) {
            PlaceHolderExpansion.register()
        }
        try {
            mainCommand()
        } catch (e: Exception) {
            warn("命令注册异常!")
            e.printStackTrace()
        }
        GlobalSettings.load(false)
        Lang.load(false)
        DatabaseConfig.load(false)
        DatabaseConfig.initTables(PlayerItems)
        Config.load(false)
        ItemSettings.load(false)
        DefaultSetting
        BindListener.register()
        BindListener194.register()
        AuthMeHook.checkHooked()
        SakuraBindAPI
        if (AuthMeHook.hasHooked) {
            listen<LoginEvent> {
                BindListener.onLogin(this.player)
            }
        } else {
            listen<PlayerLoginEvent> {
                BindListener.onLogin(this.player)
            }
        }
        if (Config.block_listener) {
            try {
                BlockCacheManager
            } catch (e: Exception) {
                warn("&a缓存初始化异常!")
            }
            BlockListener.register()
            info("&a已启用方块监听")
            try {
                BlockPhysicsEvent::class.java.getMethod("getSourceBlock")
                BlockListener1132.register()
            } catch (_: Exception) {
            }
        }

        info("&a插件已启用")

    }

    override fun onDisable() {
        try {
            BlockCacheManager.save()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            DelaySender.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            ItemSettings.settingCache.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        info("&6插件已卸载")
    }
}