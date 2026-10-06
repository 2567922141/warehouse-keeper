package com.ds.warehouse.mixin;

import com.ds.warehouse.porter.Bots;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * 让本模组的搬运工**不占玩家名额**（issue #4 · 优化1）。
 *
 * <p>搬运工不是"假实体"：它们是 Carpet {@code /player <名字> spawn} 出来的**真正 {@link ServerPlayer}**
 * （见 {@code com.ds.warehouse.porter.Body}），所以会进 {@code PlayerList.players}，占 {@code max-players}。
 * 一个仓库配一个搬运工、名册最多 8 个（{@link Bots#MAX}），默认 20 人的服务器很容易被挤到
 * 「服务器已满」，真实玩家反而进不来。
 *
 * <p>26.2 原版判定（{@code PlayerList.canPlayerLogin} 字节码实测，唯一一处 {@code List.size()}）：
 * <pre>
 * if (this.players.size() &gt;= this.getMaxPlayers() &amp;&amp; !this.canBypassPlayerLimit(nameAndId))
 *     return Component.translatable("multiplayer.disconnect.server_full");
 * </pre>
 * 这里只把这一处 {@code size()} 换成「真人计数」：数的是**不在本模组名册里的**玩家。
 * 其余检查（封禁 / IP 封禁 / 白名单 / 重复登录 / OP 免限）全部原样保留，
 * 真人照样会被 max-players 挡住，{@code server.properties} 里的 {@code max-players} 也不动。
 *
 * <p>几条边界：
 * <ul>
 *   <li>只认**本模组名册**里的名字（{@link Bots#has(String)}，忽略大小写）。别的模组或玩家手动
 *       {@code /player} 出来的假人不在名册里，照样占名额 —— 刻意不越权替别人做决定；</li>
 *   <li>搬运工仍然会出现在玩家列表 / Tab / {@code /list} 里（他们确实是在线的玩家），本 Mixin 只改满员判定；</li>
 *   <li>名册在 {@code SERVER_STARTED} 时从 {@code config/warehouse-keeper/bots.json} 读入，
 *       而 {@code canPlayerLogin} 只可能在服务端启动后发生，所以不存在"名册还没加载"的窗口；</li>
 *   <li>{@code require = 0}：将来原版若改动这段代码，注入点找不到时只是**静默失效**（回到原版行为），
 *       绝不会因为注入失败而让服务端起不来。</li>
 * </ul>
 *
 * <p>本类由 {@code warehouse-keeper.mixins.json} 的 {@code mixins} 段加载 —— 单机存档跑在 client 环境，
 * 所以**不能**只放进 {@code server} 段，否则单机的局域网联机会漏掉这条修复。
 */
@Mixin(PlayerList.class)
public abstract class PlayerLimitMixin {

    /**
     * 把 {@code players.size()} 换成「真人人数」。
     *
     * @param players 原版那一次调用里的 {@code this.players}（26.2 里该方法内唯一的 {@code List.size()}）
     * @return 名册里的搬运工之外的在服玩家数
     */
    @Redirect(
            method = "canPlayerLogin",
            at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"),
            require = 0
    )
    private int warehouseKeeper$realPlayersOnly(List<?> players) {
        if (players == null) {
            return 0;
        }
        int real = 0;
        // 刻意写成 List<?> + instanceof：万一将来原版在这段代码里多出**别的** List.size()
        // 而 @Redirect 打到了那个列表上，元素不是 ServerPlayer 也只会被判成"真人"（偏保守，少放人），
        // 而不是抛 ClassCastException 把登录整个崩掉。
        for (Object o : players) {
            if (!(o instanceof ServerPlayer p)) {
                // 不是玩家的元素（理论上不会发生）也按真人算：宁可保守地拦住，也不要凭空多给名额。
                real++;
                continue;
            }
            // authlib 9.x 把 GameProfile 改成了 record，取名走 name()（全仓库都这么用）。
            GameProfile profile = p.getGameProfile();
            String name = profile == null ? null : profile.name();
            if (name == null || !Bots.has(name)) {
                real++;
            }
        }
        return real;
    }
}
