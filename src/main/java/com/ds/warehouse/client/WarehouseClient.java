package com.ds.warehouse.client;

import com.ds.warehouse.net.ItemNamesPayload;
import com.ds.warehouse.net.SnapshotPayload;
import com.ds.warehouse.net.ViewResultPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端入口：注册按键、客户端指令、tick 钩子。
 *
 * <p>整个游戏内界面没有一行网络代码 —— 界面按一下就等于替玩家敲了一条
 * {@code /warehouse ...} 指令，服务端完全不知道界面存在。
 */
public class WarehouseClient implements ClientModInitializer {

    public static final String MOD_ID = "warehouse-keeper";

    private static KeyMapping openKey;

    /** 有人按了 /warehousegui：登记下来，等聊天界面关掉后的下一个 tick 再开（见 requestOpen） */
    private static volatile boolean guiPending;

    @Override
    public void onInitializeClient() {
        // 服务端推来的仓库快照（区域 + 每个仓库里有什么）。必须在客户端入口注册，
        // 因为专用服务端上没有这个类。
        ClientPlayNetworking.registerGlobalReceiver(SnapshotPayload.TYPE,
                (payload, context) -> {
                    ClientSnapshot.apply(payload.json());
                    // 服务端说内容变了 ⇒ 把查询缓存作废，玩家接着切页签时不会再看到变化之前的数据
                    QueryClient.invalidateAll();
                    pushItemNames();
                });
        // 服务端回的查询结果（面板的总览/物品/容器几页）
        ClientPlayNetworking.registerGlobalReceiver(ViewResultPayload.TYPE,
                (payload, context) -> QueryClient.accept(payload.kind(), payload.json()));
        // 断开连接就把快照丢掉，免得下一个世界/服务器显示上一个的数据
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientSnapshot.clear();
            QueryClient.clear();
        });

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.warehouse-keeper.open",
                // 26.3：窗口/输入从 GLFW 换成 SDL3 —— KEYSYM 改名 KEYBOARD，键码改用原版自带的常量
                // （InputConstants.KEY_A…KEY_Z 等）；键位含义与默认键 B 都没变。
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_B,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                // 0.23.0 · BUG3：没判空就直接 setScreenAndShow 会把别人开着的容器界面「顶掉」，
                // 而 26.2 的 Gui.setScreen 只调 removed()、不调 onClose()，于是 player.containerMenu
                // 卡在旧箱子菜单上（BUG3 的根因之一）。现在只在自己没开界面 / 已经开着本模组界面时开。
                if (client.gui == null || client.gui.screen() == null
                        || client.gui.screen() instanceof WarehouseScreen) {
                    open(client);
                }
            }
            RegionCache.refresh(false);
            RegionBorder.validate();
            RegionBorder.clientTick();
            QueryClient.tick();
            // 指令请求开的界面：这一 tick 聊天界面已经自己关掉了，现在开才不会被它顶掉
            if (guiPending) {
                guiPending = false;
                if (client.gui == null || client.gui.screen() == null
                        || client.gui.screen() instanceof WarehouseScreen) {
                    open(client);
                }
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("warehousegui")
                    .executes(ctx -> {
                        requestOpen();
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("whg")
                    .executes(ctx -> {
                        requestOpen();
                        return 1;
                    }));
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                ClientFeedback.onGameMessage(message.getString()));

        // 箱子标签栏（批次 3）：开着箱子时在旁边贴一列按钮，点一下＝替玩家敲一条 /warehouse tag …
        ContainerTagBar.register();
    }

    /**
     * 把「客户端语言下」的物品 / 方块名字推给服务端（批次 5 阶段 1）。
     *
     * <p>专用服务端的语言是 en_us，服务端自己解析出来的是英文名，于是「按第一个字的拼音排序」
     * 会退化成英文字母序。这里把玩家真正看到的名字推上去，服务端排序键就和面板显示一致了。
     * 一条包几百个 id，只在收到快照时推（不是每 tick），老服务端没有这个包就直接跳过。
     */
    private static void pushItemNames() {
        if (!ClientPlayNetworking.canSend(ItemNamesPayload.TYPE)) {
            return;
        }
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (ClientSnapshot.Region r : ClientSnapshot.regions()) {
            for (ClientSnapshot.Item it : r.items) {
                addName(m, it.id);
            }
            for (ClientSnapshot.Box b : r.boxes) {
                addName(m, b.block);
            }
        }
        if (m.isEmpty()) {
            return;
        }
        ClientPlayNetworking.send(new ItemNamesPayload(new ArrayList<>(m.keySet()),
                new ArrayList<>(m.values())));
    }

    private static void addName(Map<String, String> out, String id) {
        if (id == null || id.isBlank() || out.containsKey(id)) {
            return;
        }
        String name = ClientNames.item(id);
        if (name != null && !name.isBlank()) {
            out.put(id, name);
        }
    }

    /**
     * 请求打开界面：只登记，真正打开推迟到下一个客户端 tick。
     *
     * <p><b>不能在客户端指令回调里直接 setScreenAndShow</b>：客户端指令是在聊天界面的
     * 回车处理里同步执行的，原版紧接着就会自己 {@code Gui.setScreen(null)}
     * （{@code ChatScreen.closeOnSubmit}），刚开的界面立刻被顶掉 —— 表现就是
     * 「敲了 /warehousegui，界面闪一下又回到游戏」。按键那条路本来就在 tick 里，不受影响。
     */
    public static void requestOpen() {
        guiPending = true;
    }

    /** 打开界面。 */
    public static void open(Minecraft client) {
        if (client.player == null) {
            return;
        }
        RegionCache.refresh(true);
        RegionBorder.validate();
        client.setScreenAndShow(new WarehouseScreen());
    }
}
