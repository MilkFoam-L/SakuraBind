package top.iseason.bukkit.sakurabind.utils

import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.entity.HumanEntity
import org.bukkit.inventory.ItemStack
import top.iseason.bukkit.sakurabind.config.BaseSetting
import top.iseason.bukkit.sakurabind.config.Config
import top.iseason.bukkit.sakurabind.event.AutoBindMessageEvent
import top.iseason.bukkit.sakurabind.event.PlayerDenyMessageEvent
import top.iseason.bukkittemplate.utils.bukkit.MessageUtils.sendColorMessage
import top.iseason.bukkittemplate.utils.other.EasyCoolDown

object MessageTool {
    /**
     * 玩家被禁止某种行为时发送消息，具有冷却机制
     */
    fun denyMessageCoolDown(
        player: HumanEntity,
        message: String,
        setting: BaseSetting,
        item: ItemStack? = null,
        block: Block? = null,
    ) {
        val check = EasyCoolDown.check(player.uniqueId, Config.message_coolDown)
        val event = PlayerDenyMessageEvent(player, setting, message, check, item, block)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) return
        if (!event.isCoolDown)
            player.sendColorMessage(event.message)
    }

    /**
     * 玩家物品被绑定时发送消息，具有冷却机制
     */
    fun bindMessageCoolDown(
        player: HumanEntity,
        message: String,
        setting: BaseSetting,
        item: ItemStack,
    ) {
        val check = EasyCoolDown.check(player.uniqueId, Config.message_coolDown)
        val event = AutoBindMessageEvent(player, setting, message, check, item)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) return
        if (!event.isCoolDown)
            player.sendColorMessage(message)
    }

    fun messageCoolDown(
        player: HumanEntity,
        message: String,
    ) {
        if (!EasyCoolDown.check(player.uniqueId, Config.message_coolDown))
            player.sendColorMessage(message)
    }
}