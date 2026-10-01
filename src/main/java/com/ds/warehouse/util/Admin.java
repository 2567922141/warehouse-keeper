package com.ds.warehouse.util;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;

/**
 * 「谁是管理员」的唯一判定入口。
 *
 * <p>三档都算管理员：
 * <ol>
 *   <li>权限等级 ≥ 2（GAMEMASTER）—— 专职服务器上用 {@code /op} 加过的人；</li>
 *   <li>单人存档的房主 —— <b>关键</b>：单人模式「不开作弊」时原版给房主的权限是 0 级，
 *       光看权限等级会把房主自己关在门外，所以这里额外认 {@code isSingleplayerOwner}；</li>
 *   <li>控制台 / 命令方块（根本没有玩家实体）。</li>
 * </ol>
 *
 * <p>局域网上别人的存档里，房主是管理员、访客要房主 {@code /op} 之后才是 —— 这正是需求想要的。
 */
public final class Admin {

    private Admin() {
    }

    /** 命令 / 服务端侧判定 */
    public static boolean isAdmin(CommandSourceStack src) {
        if (src == null) {
            return false;
        }
        if (levelOk(src.permissions())) {
            return true;
        }
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            // 控制台、命令方块、函数：本来就只有管理员能碰
            return true;
        }
        return isAdmin(src.getServer(), p);
    }

    /** 直接拿服务器 + 玩家判定（tick 线程维护 OP 快照时用） */
    public static boolean isAdmin(MinecraftServer srv, ServerPlayer p) {
        if (p == null) {
            return false;
        }
        if (levelOk(p.permissions())) {
            return true;
        }
        if (srv == null) {
            return false;
        }
        try {
            NameAndId nid = new NameAndId(p.getGameProfile());
            if (srv.isSingleplayerOwner(nid)) {
                return true;
            }
            return srv.getPlayerList() != null && srv.getPlayerList().isOp(nid);
        } catch (Throwable ignored) {
            // 拿不到就当不是管理员，宁可少给权限
            return false;
        }
    }

    /** 权限集合里是不是有 2 级以上 */
    public static boolean levelOk(PermissionSet ps) {
        if (ps == null) {
            return false;
        }
        if (ps instanceof LevelBasedPermissionSet lbs) {
            PermissionLevel lv = lbs.level();
            return lv != null && lv.isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);
        }
        return ps.hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** 给玩家看的一句话说明 */
    public static String denyText() {
        return "该操作仅管理员（OP）可用。单人存档中房主即为管理员；"
                + "局域网与服务器中需先对目标玩家执行 /op。";
    }
}
