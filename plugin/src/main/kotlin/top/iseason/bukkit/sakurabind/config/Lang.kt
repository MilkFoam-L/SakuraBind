package top.iseason.bukkit.sakurabind.config

import org.bukkit.configuration.ConfigurationSection
import top.iseason.bukkittemplate.BukkitTemplate
import top.iseason.bukkittemplate.config.SimpleYAMLConfig
import top.iseason.bukkittemplate.config.annotations.FilePath
import top.iseason.bukkittemplate.config.annotations.Key
import top.iseason.bukkittemplate.utils.bukkit.MessageUtils

@Key
@FilePath("lang.yml")
object Lang : SimpleYAMLConfig() {
    var prefix = "&a[&6${BukkitTemplate.getPlugin().description.name}&a] &f"
    var send_back_all = "&7你的遗失物品已全部放入你的背包"
    var send_back = "&7你的部分遗失物品已放入你的背包, 剩下的已发往邮箱"

    var item__deny_command = "&6你不能拿着此物品输入该命令!"
    var item__deny_drop = "&6该物品不能丢出!"
    var item__deny_pickup = "&6该物品属于 &a{0},&6已归还"
    var item__deny_itemFrame = "&6该物品禁止放入展示框!"
    var item__deny_interact = "&6该物品禁止交互!"
    var item__deny_entity_interact = "&6该物品禁止与实体交互!"
    var item__deny_click = "&6你不能拿走这个物品!"
    var item__deny_inventory = "&6此物品禁止放入这个容器!"
    var item__deny_throw = "&6该物品禁止投掷!"
    var item__deny__consume = "&6该物品禁止消耗!"
    var item__deny__craft = "&6该物品禁止用于合成!"
    var item__deny__anvil = "&6该物品禁止用于铁砧!"
    var item__deny_container_break = "&6该容器含有绑定物品，禁止破坏!"

    var block__deny_break = "&6你不能破坏这个方块,这个方块属于 &b{0}"
    var block__deny_place = "&6你不能放置这个方块，此物物品已绑定"
    var block__deny_interact = "&6你没有这个方块的使用权限,这个方块属于 &b{0}"

    var scanner_item_send_back = "&6检测到你的背包存在别人的物品，已归还物主!"
    var command__bind = "&a已绑定 &b{0} &a手上的物品"
    var command__bindTo = "&a已将手上的物品绑定至 &b{0}"
    var command__unbind = "&a已将手上的物品解绑"
    var command__bindAll = "&a已绑定整个背包的物品"
    var command__unbindAll = "&a已解绑整个背包的物品"
    var command__getLost_item = "&a你领取了 &b{0} &a个遗失物品,请腾出空间领取剩余物品!"
    var command__getLost_empty = "&6没有遗失物品!"
    var command__getLost_full = "&6背包空间不足!"
    var command__getLost_all = "&a你领取了所有的遗失物品! 共 &b{0} &a个物品"
    var command__getLost_coolDown = "&6你输入得太快了!"
    var command__autoBind = "&a已添加 &b{0}"
    var command__debug = "&aDebug模式: &b{0}"

    var has_lost_item = "&a你有遗失的物品,请输入 &6'/sakurabind get' &a领取"

    override fun onLoaded(section: ConfigurationSection) {
        MessageUtils.defaultPrefix = prefix
    }
}