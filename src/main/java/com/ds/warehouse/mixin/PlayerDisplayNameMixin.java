package com.ds.warehouse.mixin;

import com.ds.warehouse.client.ClientSnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让搬运工**头顶名牌**显示面板里设的「显示名」，而不是 Carpet 注册名（issue #3 · 优化2）。
 *
 * <p>为什么只能这么做（26.2 jar 字节码实测）：
 * <ul>
 *   <li>{@code Player.getName()} = {@code Component.literal(gameProfile.name())} —— 永远是 Carpet 注册名
 *       （{@code WarehouseBot}、{@code WarehouseBot2}…），改档案名就等于换身份（UUID 由名字派生），不能碰；</li>
 *   <li>{@code Player.getDisplayName()} = {@code PlayerTeam.formatNameForTeam(getTeam(), getName())}
 *       + {@code decorateDisplayNameComponent(...)} —— 只受**记分板队伍**影响，而
 *       {@code Entity.setCustomName(...)} 对玩家名牌完全没有作用（服务端 {@code Body.prepare()} 里那句
 *       {@code bot.setCustomName(DISPLAY)} 其实是空招），所以服务端侧改不动名字；</li>
 *   <li>名牌是渲染时调 {@code Entity.getDisplayName()}（{@code EntityRenderer} 的字节码里就是这条调用），
 *       所以在客户端把这一处返回值换掉，就能让**所有装了本模组的客户端**看到新名字。</li>
 * </ul>
 *
 * <p>几条边界：
 * <ul>
 *   <li>只换「显示名非空」的搬运工；普通玩家、没设显示名的搬运工原样走原版逻辑；</li>
 *   <li>只在快照里找得到这个人时才换（{@link ClientSnapshot#displayNameOf(String)} 返回 null 就放行），
 *       所以断线 / 老服务端不发这段时不会有任何行为变化；</li>
 *   <li>纯客户端：不发包、不写存档、不改服务端状态，也不影响指令与任务（它们一律用注册名）。</li>
 * </ul>
 *
 * <p>本类由 {@code warehouse-keeper.mixins.json} 的 {@code client} 段加载，专用服务端不会碰到它。
 */
@Mixin(Player.class)
public abstract class PlayerDisplayNameMixin {

    /**
     * 在 {@code Player.getDisplayName()} 的开头插一刀：搬运工设了显示名就直接返回它。
     *
     * <p>不调用 {@code super} 也不做队伍装饰 —— 搬运工不进任何队伍，显示名就是最终名字。
     */
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void warehouseKeeper$botDisplayName(CallbackInfoReturnable<Component> cir) {
        Player self = (Player) (Object) this;
        // getName() 对玩家永远返回档案名（上面的字节码），拿它当查名册的键 —— 注册名就是身份。
        String display = ClientSnapshot.displayNameOf(self.getName().getString());
        if (display != null) {
            cir.setReturnValue(Component.literal(display));
        }
    }
}
