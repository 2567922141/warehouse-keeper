package com.ds.warehouse.client;

import com.ds.warehouse.util.Admin;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * 客户端用的「我是不是管理员」判断 —— 只用来决定界面里哪些按钮要藏起来。
 *
 * <p>真正的拦截在服务端（命令节点的 requires），这里藏按钮只是别让普通玩家点了才发现不能用。
 */
public final class ClientAdmin {

    private ClientAdmin() {
    }

    public static boolean isAdmin() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return false;
        }
        Player p = mc.player;
        if (p == null) {
            return false;
        }
        if (Admin.levelOk(p.permissions())) {
            return true;
        }
        // 单人存档里本地玩家就是房主（原版没开作弊时权限等级是 0，但仍然是房主）
        try {
            return mc.hasSingleplayerServer();
        } catch (Throwable t) {
            return false;
        }
    }
}
