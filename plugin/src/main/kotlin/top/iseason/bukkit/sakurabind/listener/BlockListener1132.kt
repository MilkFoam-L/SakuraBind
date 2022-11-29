package top.iseason.bukkit.sakurabind.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPhysicsEvent
import top.iseason.bukkit.sakurabind.cache.BlockCacheManager

object BlockListener1132 : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPhysicsEvent(event: BlockPhysicsEvent) {
        val owner = BlockCacheManager.getOwner(event.sourceBlock)?.first ?: return
        BlockCacheManager.removeBlock(event.sourceBlock)
        BlockCacheManager.addTemp(BlockCacheManager.blockToString(event.sourceBlock), owner)
    }
}