package com.ds.warehouse;

import com.ds.warehouse.command.NameArgumentInfo;
import com.ds.warehouse.command.WarehouseCommand;
import com.ds.warehouse.config.AppConfig;
import com.ds.warehouse.config.ContainerTags;
import com.ds.warehouse.config.RegionStore;
import com.ds.warehouse.config.WorldStore;
import com.ds.warehouse.index.AutoScan;
import com.ds.warehouse.index.IndexStore;
import com.ds.warehouse.index.IndexRefresh;
import com.ds.warehouse.index.Scanner;
import com.ds.warehouse.index.WarehouseIndex;
import com.ds.warehouse.net.SnapshotSync;
import com.ds.warehouse.net.ViewQueryService;
import com.ds.warehouse.porter.Body;
import com.ds.warehouse.porter.Bots;
import com.ds.warehouse.porter.Homes;
import com.ds.warehouse.porter.Porter;
import com.ds.warehouse.porter.Tasks;
import com.ds.warehouse.util.Audit;
import com.ds.warehouse.util.PlayerPerms;
import com.ds.warehouse.web.WebSnapshot;
import com.ds.warehouse.web.WebUsers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * warehouse-keeper 主入口。
 *
 * 设计约束（见实现手册）：
 *  - 只读为主，不向存档写入任何自定义内容（无自定义方块/物品/实体）。
 *  - 一切容器一律按 net.minecraft.world.Container 接口处理，不针对任何模组特判。
 *  - 区域定义写在 config/warehouse-keeper/，不写进 world/。
 */
public class WarehouseMod implements ModInitializer {
    public static final String MOD_ID = "warehouse-keeper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 模组版本（来自 fabric.mod.json） */
    public static final String VERSION = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");

    /** 全局索引（运行时在内存里；开档会从 config/warehouse-keeper/index/ 恢复） */
    public static final WarehouseIndex INDEX = new WarehouseIndex();

    /**
     * 上一次服务端 tick 的毫秒时间戳。
     *
     * 用来判断游戏是不是「停着」：单人模式下切出游戏窗口会让游戏自动暂停，
     * 集成服务端随之停止 tick，网页上排队的扫描就一直不会执行。
     * 网页接口靠这个值给出明确提示，而不是让用户对着不动的页面猜。
     */
    public static volatile long lastTickMillis = 0L;

    /** 服务端最近（1.5 秒内）有没有 tick 过。暂停或关服时为 false。 */
    public static boolean serverTicking() {
        long t = lastTickMillis;
        return t > 0L && System.currentTimeMillis() - t < 1500L;
    }

    /** 当前服务端实例（没进存档时为 null）。网页端只拿它读 usercache.json 之类的纯文件路径。 */
    private static volatile MinecraftServer SERVER = null;

    public static MinecraftServer server() {
        return SERVER;
    }

    @Override
    public void onInitialize() {
        // 注意：仓库区域、搬运工名册、物品归位记忆、索引都是**按存档**的，
        // 必须等进了存档（SERVER_STARTED）知道是哪个世界之后才能读，见下面的 WorldStore.enter
        AppConfig.load();

        // 中文名字参数类型（仓库名/假人名）：自定义参数类型要在指令树构建之前注册，
        // 否则服务端把指令树同步给客户端时找不到它的序列化器（见 command/NameArgument.java）
        NameArgumentInfo.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                WarehouseCommand.register(dispatcher));

        // 联机同步：把「有哪些仓库、每个仓库里有什么」推给装了本模组的客户端
        SnapshotSync.register();
        // 玩家断线：忘掉他的选区（否则下次进服旧选区还在，region save 会误建上次会话的范围），
        // 同时清掉同步模块里按玩家记的状态
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            WarehouseCommand.forgetPlayer(handler.getPlayer().getUUID());
            SnapshotSync.forget(handler.getPlayer().getUUID());
        });
        // 按需查询（面板的总览/物品/容器几页），只读内存快照
        ViewQueryService.register();
        // 索引按需刷新：监听拆箱/放箱，定时比对箱内内容，脏了就在扫描空闲时局部重扫
        IndexRefresh.register();

        // 顺序不能反：先分时扫描，再根据扫描结果刷新网页快照，最后才是自动扫描调度
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            lastTickMillis = System.currentTimeMillis();
            Scanner.tick(server);
            // 索引按需刷新：扫描空闲时把「脏了」的仓库局部重扫掉（放箱/拆箱/玩家自己翻箱子都会置脏）
            IndexRefresh.tick(server);
            WebSnapshot.serverTick();
            // 快照有变化就推给客户端（内容没变时只做一次便宜比对）
            SnapshotSync.tick(server);
            // 面板查询：每 tick 最多答一条（只读内存快照，不扫世界）
            ViewQueryService.tick(server);
            AutoScan.tick(server);
            // 搬运工：接单、取货、送货。同样是 tick 线程上的状态机
            Porter.tick(server);
        });

        // 进入存档（单人游戏同样触发，不需要开局域网联机）：
        //   1) 先从硬盘恢复上次的索引 —— 立刻有数据
        //   2) 延迟几秒自动重扫一遍，把数据刷新成最新
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SERVER = server;
            AppConfig.load();

            // 先确定「这是哪个存档」，之后所有按存档隔离的数据都从这里取路径
            WorldStore.enter(server);
            // 仓库区域定义（每个存档一份）
            RegionStore.load();

            String note = IndexStore.load(INDEX, server);
            if (note != null) {
                LOGGER.info("[warehouse-keeper] 未恢复硬盘索引: {}", note);
            }
            // 「哪个物品该放哪个箱子」的长期记忆：全取光之后也能把东西放回去
            Homes.load();
            ContainerTags.load();
            // 搬运工名册（假人名字 + 各自值守的仓库）；第一次进存档会自动建一个
            Bots.load();
            Bots.ensureDefault();
            // 旧网页账号库（加密文件，只读）：供 PlayerPerms 一次性迁移老账号权限
            WebUsers.load();
            // 游戏内玩家权限表（批次 5 阶段 2）：取货 / 指挥假人 / 整理仓库这三项权限的权威来源。
            // 必须排在 WebUsers 之后：第一次启动时要把旧网页账号里的权限导入一次。
            PlayerPerms.load();
            if (!PlayerPerms.migratedFromWeb()) {
                LOGGER.info("[warehouse-keeper] {}", PlayerPerms.migrateFromWeb());
            }
            // 操作日志（谁什么时候取走了什么），读回来最近 1000 条
            Audit.load();
            WebSnapshot.markDirty();

            // 把名册里的假人放出来（没装 Carpet 时这是空操作）
            Porter.ensureBodies(server);
            AutoScan.onServerStarted();
        });

        // 退出存档：先把搬运工手上没送出去的东西放回仓库，再存一次索引
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            AutoScan.cancel();
            // 手上的活先停下，再处理东西，最后才让假人退场
            Tasks.cancelAll();
            // 关键：取出来的东西必须放回去，绝不能因为关服而凭空消失
            Porter.returnCarried(server);
            // 假人身上捡来的东西同理：先塞回仓库（塞不下的丢它脚下），再让它退场
            Porter.flushBody(server);
            Body.removeAll(server);
            Bots.save();
            String err = IndexStore.save(INDEX, server);
            if (err != null) {
                LOGGER.info("[warehouse-keeper] 退出时索引未保存: {}", err);
            }
            Homes.save();
            ContainerTags.save();
            PlayerPerms.save();
            // 所有按存档的数据都存完了，才允许「忘掉这是哪个存档」
            WorldStore.leave();
            SERVER = null;
        });

        LOGGER.info("[warehouse-keeper] initialized（仓库区域等数据在进存档时按存档读取）");
    }
}
