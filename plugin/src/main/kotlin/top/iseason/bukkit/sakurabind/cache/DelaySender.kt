package top.iseason.bukkit.sakurabind.cache

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import top.iseason.bukkit.sakurabind.config.Config
import top.iseason.bukkit.sakurabind.dto.PlayerItem
import top.iseason.bukkit.sakurabind.hook.SakuraMailHook
import top.iseason.bukkittemplate.BukkitTemplate
import top.iseason.bukkittemplate.config.DatabaseConfig
import top.iseason.bukkittemplate.config.dbTransaction
import top.iseason.bukkittemplate.debug.warn
import top.iseason.bukkittemplate.utils.bukkit.ItemUtils.toByteArray
import top.iseason.bukkittemplate.utils.other.submit
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class DelaySender private constructor(private val uuid: UUID) : BukkitRunnable() {

    private val inv = Bukkit.createInventory(null, 36)

    //缓冲5秒
    @Volatile
    private var coolDown = 3

    override fun run() {
        if (coolDown < 0) {
            cancel()
            return
        }
        coolDown--
    }

    @Synchronized
    private fun addItem(items: Collection<ItemStack>) {

        coolDown = 3
        val addItem = inv.addItem(*items.toTypedArray())
        //缓存满了
        if (addItem.isNotEmpty()) {
            sendItem()
            addItem(addItem.values)
            return
        }

    }

    override fun cancel() {
        super.cancel()
        sendItem()
        remove(uuid)
    }

    private fun sendItem() {
        val itemStacks = inv.filterNotNull()
        if (Config.sakuraMail_hook && SakuraMailHook.hasHooked) {
            submit(async = true) {
                SakuraMailHook.sendMail(uuid, itemStacks)
            }
        } else if (DatabaseConfig.isConnected) {
            submit(async = true) {
                dbTransaction {
                    PlayerItem.new {
                        this.uuid = this@DelaySender.uuid
                        this.item = ExposedBlob(itemStacks.toByteArray())
                    }
                }
            }
        } else {
            warn("数据库未启用,无法发送暂存箱子!")
        }
        inv.clear()
    }

    companion object {
        private val map = ConcurrentHashMap<UUID, DelaySender>()

        fun sendItem(uuid: UUID, items: Collection<ItemStack>) {
            val delaySender = map.computeIfAbsent(uuid) {
                DelaySender(uuid).apply { runTaskTimerAsynchronously(BukkitTemplate.getPlugin(), 20, 20) }
            }
            delaySender.addItem(items)
        }

        fun remove(uuid: UUID) = map.remove(uuid)
    }
}