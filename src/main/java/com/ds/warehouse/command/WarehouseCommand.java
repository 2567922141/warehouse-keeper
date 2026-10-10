package com.ds.warehouse.command;

import com.ds.warehouse.WarehouseMod;
import com.ds.warehouse.config.AppConfig;
import com.ds.warehouse.config.CategoryRules;
import com.ds.warehouse.config.ContainerTags;
import com.ds.warehouse.config.Region;
import com.ds.warehouse.config.RegionStore;
import com.ds.warehouse.index.AutoScan;
import com.ds.warehouse.index.ContainerRecord;
import com.ds.warehouse.index.Containers;
import com.ds.warehouse.index.IndexStore;
import com.ds.warehouse.index.ItemIds;
import com.ds.warehouse.index.Scanner;
import com.ds.warehouse.index.WarehouseIndex;
import com.ds.warehouse.net.SnapshotSync;
import com.ds.warehouse.porter.Body;
import com.ds.warehouse.porter.Bots;
import com.ds.warehouse.porter.Homes;
import com.ds.warehouse.porter.Porter;
import com.ds.warehouse.porter.Tasks;
import com.ds.warehouse.util.Admin;
import com.ds.warehouse.util.Audit;
import com.ds.warehouse.util.PlayerPerms;
import com.ds.warehouse.util.Categories;
import com.ds.warehouse.util.CreativeOrder;
import com.ds.warehouse.util.Names;
import com.ds.warehouse.web.WebSnapshot;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /warehouse 指令树（里程碑 A：仓库索引）。
 *
 * 全部输出都是纯文本 Component.literal，避免依赖尚未验证的样式 API。
 */
public final class WarehouseCommand {

    private static final int PAGE_SIZE = 10;

    /** 每个玩家的两个选区角点 */
    private static final Map<UUID, BlockPos> POS1 = new HashMap<>();
    private static final Map<UUID, BlockPos> POS2 = new HashMap<>();

    private WarehouseCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("warehouse")
                .executes(ctx -> {
                    help(ctx.getSource());
                    return 1;
                })
                .then(Commands.literal("ping").executes(ctx -> {
                    send(ctx.getSource(), "[warehouse-keeper] 运行正常，索引条目 " + WarehouseMod.INDEX.items.size());
                    return 1;
                }))
                .then(Commands.literal("pos1").requires(WarehouseCommand::admin).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    BlockPos pos = p.blockPosition();
                    POS1.put(p.getUUID(), pos.immutable());
                    send(ctx.getSource(), "点1 已设为 " + fmt(pos) + "  维度 " + dim(p));
                    return 1;
                }))
                .then(Commands.literal("pos2").requires(WarehouseCommand::admin).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    BlockPos pos = p.blockPosition();
                    POS2.put(p.getUUID(), pos.immutable());
                    send(ctx.getSource(), "点2 已设为 " + fmt(pos) + "  维度 " + dim(p));
                    return 1;
                }))
                .then(Commands.literal("region").requires(WarehouseCommand::admin)
                        .executes(ctx -> {
                            listRegions(ctx.getSource());
                            return 1;
                        })
                        .then(Commands.literal("list").executes(ctx -> {
                            listRegions(ctx.getSource());
                            return 1;
                        }))
                        .then(Commands.literal("save")
                                .then(Commands.argument("name", NameArgument.name())
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            UUID id = p.getUUID();
                                            BlockPos a = POS1.get(id);
                                            BlockPos b = POS2.get(id);
                                            if (a == null || b == null) {
                                                send(ctx.getSource(), "尚未设点。请先在仓库的两个对角执行 /warehouse pos1 与 /warehouse pos2");
                                                return 0;
                                            }
                                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                                            Region r = new Region(name, dim(p), a, b);
                                            RegionStore.REGIONS.put(name, r);
                                            RegionStore.save();
                                            Audit.add(adminName(ctx.getSource()), "建仓库", "新建仓库「" + name + "」"
                                                    + r.describeCorners() + " " + r.describeSize());
                                            send(ctx.getSource(), "已保存仓库 " + name + "  " + r.describeCorners()
                                                    + "  " + r.blockVolume() + " 方块");
                                            return 1;
                                        })))
                        .then(Commands.literal("info")
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(REGION_SUGGEST)
                                        .executes(ctx -> {
                                            Region r = RegionStore.REGIONS.get(
                                                    StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT));
                                            if (r == null) {
                                                send(ctx.getSource(), noRegion(
                                                        StringArgumentType.getString(ctx, "name")));
                                                return 0;
                                            }
                                            send(ctx.getSource(), "仓库 " + r.name + "  维度 " + Names.dimension(Scanner.dimensionOf(r))
                                                    + "  " + r.describeCorners() + "  " + r.describeSize()
                                                    + "\n高度：" + r.heightText());
                                            return 1;
                                        })))
                        .then(Commands.literal("height")
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(REGION_SUGGEST)
                                        .then(Commands.literal("full").executes(ctx -> setHeight(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), true)))
                                        .then(Commands.literal("limited").executes(ctx -> setHeight(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), false)))))
                        .then(Commands.literal("grow").then(growNode()))
                        // 优化8：缩小 = grow 的反向，且必须主动清理索引（被缩掉的箱子会移出索引）
                        .then(Commands.literal("shrink").then(shrinkNode()))
                        .then(Commands.literal("merge")
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(REGION_SUGGEST)
                                        .executes(ctx -> mergeRegion(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(REGION_SUGGEST)
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                                            Region removed = RegionStore.REGIONS.remove(name);
                                            if (removed == null) {
                                                send(ctx.getSource(), noRegion(name));
                                                return 0;
                                            }
                                            RegionStore.save();
                                            // 仓库没了，索引里落在它范围内的容器也必须一起清掉，
                                            // 否则查询 / 下单 / 整理仍会选中这些箱子
                                            WarehouseMod.INDEX.removeContainersIn(List.of(removed));
                                            WarehouseMod.INDEX.reaggregate();
                                            // 区域内的箱子标签这里不动：ContainerTags 只有全局的 repair / pruneMissing
                                            // （只删「箱子已经不在」的标签），没有按区域清理的方法；
                                            // 而这些箱子物理上还在，标签得由玩家用 /warehouse tag clear 或 tag prune 清理
                                            Audit.add(adminName(ctx.getSource()), "删仓库", "删掉了仓库「" + name + "」");
                                            send(ctx.getSource(), "已删除仓库 " + name
                                                    + "（区域内箱子的标签没动，需要的话用 /warehouse tag prune 清一遍）");
                                            return 1;
                                        }))))
                .then(Commands.literal("scan").requires(WarehouseCommand::admin)
                        .executes(ctx -> startScan(ctx.getSource(), null))
                        .then(Commands.argument("name", NameArgument.name())
                                .suggests(REGION_SUGGEST)
                                .executes(ctx -> startScan(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT)))))
                .then(Commands.literal("status").executes(ctx -> {
                    WarehouseIndex idx = WarehouseMod.INDEX;
                    send(ctx.getSource(), "扫描器: " + Scanner.progressText());
                    if (AutoScan.pending()) {
                        send(ctx.getSource(), "自动扫描倒计时 " + AutoScan.secondsLeft() + " 秒");
                    }
                    if (idx.lastScanMillis > 0 || !idx.items.isEmpty()) {
                        send(ctx.getSource(), "上次扫描: " + idx.lastScanRegion
                                + "  容器 " + idx.scannedContainers
                                + "  物品种类 " + idx.items.size()
                                + "  总数量 " + idx.totalItems
                                + "  区块 " + idx.scannedChunks + " 扫到 / " + idx.skippedChunks + " 未加载"
                                + "  耗时 " + idx.lastScanMillis + "ms");
                    }
                    showIndexInfo(ctx.getSource());
                    // 1.1.1：把「同步给玩家」这一环的计数也放出来（组包多大、有没有人没收全、有没有跳过过大的包）
                    send(ctx.getSource(), SnapshotSync.statusText());
                    // BUG7：有未分类物品就顺手说一句，并指到已有的清单命令（口径与那条命令完全一致）
                    showUnclassifiedHint(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("save").requires(WarehouseCommand::admin).executes(ctx -> {
                    // 玩家主动要求保存：当场写完再回话，回显才不是空头支票
                    String err = IndexStore.saveNow(WarehouseMod.INDEX, ctx.getSource().getServer());
                    send(ctx.getSource(), err != null ? err : "索引已保存到磁盘");
                    showIndexInfo(ctx.getSource());
                    return err == null ? 1 : 0;
                }))
                .then(Commands.literal("load").requires(WarehouseCommand::admin).executes(ctx -> {
                    String note = IndexStore.load(WarehouseMod.INDEX, ctx.getSource().getServer());
                    WebSnapshot.markDirty();
                    send(ctx.getSource(), note != null ? note : "索引已从磁盘恢复");
                    showIndexInfo(ctx.getSource());
                    return note == null ? 1 : 0;
                }))
                .then(Commands.literal("settings").requires(WarehouseCommand::admin)
                        .executes(ctx -> {
                            showSettings(ctx.getSource());
                            return 1;
                        })
                        .then(Commands.literal("autoscan")
                                .then(Commands.literal("on").executes(ctx -> setSetting(ctx.getSource(), "autoScanOnWorldLoad", true)))
                                .then(Commands.literal("off").executes(ctx -> setSetting(ctx.getSource(), "autoScanOnWorldLoad", false))))
                        .then(Commands.literal("autoload")
                                .then(Commands.literal("on").executes(ctx -> setSetting(ctx.getSource(), "loadIndexFromDisk", true)))
                                .then(Commands.literal("off").executes(ctx -> setSetting(ctx.getSource(), "loadIndexFromDisk", false))))
                        .then(Commands.literal("autosave")
                                .then(Commands.literal("on").executes(ctx -> setSetting(ctx.getSource(), "saveIndexToDisk", true)))
                                .then(Commands.literal("off").executes(ctx -> setSetting(ctx.getSource(), "saveIndexToDisk", false))))
                        .then(Commands.literal("forcechunks")
                                .then(Commands.literal("on").executes(ctx -> setSetting(ctx.getSource(), "scanForceLoadChunks", true)))
                                .then(Commands.literal("off").executes(ctx -> setSetting(ctx.getSource(), "scanForceLoadChunks", false))))
                        .then(Commands.literal("delay")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                                        .executes(ctx -> {
                                            AppConfig cfg = AppConfig.get();
                                            cfg.autoScanDelaySeconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                            cfg.save();
                                            send(ctx.getSource(), "自动扫描延迟：" + cfg.autoScanDelaySeconds + " 秒");
                                            showSettings(ctx.getSource());
                                            return 1;
                                        }))))
                .then(Commands.literal("stats").executes(ctx -> {
                    showStats(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            showList(ctx.getSource(), 1);
                            return 1;
                        })
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    showList(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page"));
                                    return 1;
                                })))
                .then(Commands.literal("find")
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    find(ctx.getSource(), StringArgumentType.getString(ctx, "item"));
                                    return 1;
                                })))
                .then(Commands.literal("enchants")
                        .executes(ctx -> {
                            enchants(ctx.getSource());
                            return 1;
                        })
                        .then(Commands.literal("list").executes(ctx -> {
                            enchants(ctx.getSource());
                            return 1;
                        }))
                        .then(Commands.literal("find")
                                .then(Commands.argument("query", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            findEnchanted(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "query"));
                                            return 1;
                                        }))))
                .then(Commands.literal("categories")
                        .executes(ctx -> {
                            showCategories(ctx.getSource());
                            return 1;
                        })
                        .then(Commands.literal("reload").executes(ctx -> {
                            Categories.reload();
                            // 1.1.1（BUG：分类改了别的客户端跟不上）：重读覆盖表之后必须把三份缓存一起作废，
                            // 否则面板下拉、创造栏序号、网页快照可能分别停在旧值上：
                            //   ① Categories 自己的派生缓存  ② 创造栏顺序表（可能根本还没建过）
                            //   ③ WebSnapshot 里那份已经组装好的总览（categories 就是从它来的）
                            Categories.clearCaches();
                            CreativeOrder.invalidate();
                            CreativeOrder.ensure(ctx.getSource().getServer());
                            WebSnapshot.markDirty();
                            send(ctx.getSource(), "覆盖表已重读：" + Categories.loadNote());
                            showCategories(ctx.getSource());
                            return 1;
                        }))
                        .then(Commands.literal("unclassified")
                                .executes(ctx -> {
                                    showUnclassified(ctx.getSource(), 1);
                                    return 1;
                                })
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            showUnclassified(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "page"));
                                            return 1;
                                        })))
                        .then(Commands.literal("of")
                                .then(Commands.argument("item", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            showOneCategory(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "item"));
                                            return 1;
                                        })))
                        .then(Commands.literal("dump")
                                .executes(ctx -> {
                                    dumpCategories(ctx.getSource(), 1);
                                    return 1;
                                })
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            dumpCategories(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "page"));
                                            return 1;
                                        })))
                        .then(Commands.literal("dumpall")
                                .executes(ctx -> {
                                    dumpAllCategories(ctx.getSource(), 1);
                                    return 1;
                                })
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            dumpAllCategories(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "page"));
                                            return 1;
                                        }))))
                .then(tagNode())
                .then(Commands.literal("clear").requires(WarehouseCommand::admin).executes(ctx -> {
                    WarehouseMod.INDEX.clear();
                    WebSnapshot.markDirty();
                    send(ctx.getSource(), "索引已清空");
                    return 1;
                }))
                .then(Commands.literal("order").requires(WarehouseCommand::canTake)
                        .executes(ctx -> {
                            send(ctx.getSource(), "用法：/warehouse order <物品名> [数量]，例：order diamond 64");
                            return 1;
                        })
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> order(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item")))))
                .then(Commands.literal("porter")
                        .executes(ctx -> {
                            for (String line : Porter.statusText(ctx.getSource().getServer()).split("\n")) {
                                send(ctx.getSource(), line);
                            }
                            return 1;
                        })
                        .then(Commands.literal("spawn").requires(WarehouseCommand::admin).executes(ctx -> porterSpawn(ctx.getSource())))
                        .then(Commands.literal("kill").requires(WarehouseCommand::admin).executes(ctx -> porterKill(ctx.getSource())))
                        .then(Commands.literal("home").requires(WarehouseCommand::admin).executes(ctx -> porterHome(ctx.getSource())))
                        .then(Commands.literal("body").requires(WarehouseCommand::admin)
                                .then(Commands.literal("on").executes(ctx -> porterBody(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> porterBody(ctx.getSource(), false))))
                        .then(Commands.literal("add").requires(WarehouseCommand::admin)
                                .executes(ctx -> botAdd(ctx.getSource(), ""))
                                .then(Commands.argument("region", NameArgument.name())
                                        .suggests(REGION_SUGGEST)
                                        .executes(ctx -> botAdd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "region")))))
                        .then(Commands.literal("staff").requires(WarehouseCommand::admin)
                                .executes(ctx -> porterStaff(ctx.getSource(), ""))
                                .then(Commands.argument("region", NameArgument.name())
                                        .suggests(REGION_SUGGEST)
                                        .executes(ctx -> porterStaff(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "region")))))
                        .then(Commands.literal("tidy").requires(WarehouseCommand::admin)
                                .executes(ctx -> porterTask(ctx.getSource(), Tasks.TIDY, ""))
                                .then(Commands.argument("region", NameArgument.name())
                                        .suggests(REGION_SUGGEST)
                                        .executes(ctx -> porterTask(ctx.getSource(), Tasks.TIDY,
                                                StringArgumentType.getString(ctx, "region")))))
                        // 优化4：清扫（自动拾取仓库范围内的掉落物）已整条移除，只留同名入口回一句明确提示。
                        // 刻意**不带** requires(admin)：带上之后非管理员只会看到「权限不足」，那正是要避免的误解。
                        .then(Commands.literal("sweep")
                                .executes(ctx -> sweepRemoved(ctx.getSource()))
                                .then(Commands.argument("region", NameArgument.name())
                                        .suggests(REGION_SUGGEST)
                                        .executes(ctx -> sweepRemoved(ctx.getSource()))))
                        .then(Commands.literal("preview").requires(WarehouseCommand::admin)
                                .executes(ctx -> porterPreview(ctx.getSource(), ""))
                                .then(Commands.argument("region", NameArgument.name())
                                        .suggests(REGION_SUGGEST)
                                        .executes(ctx -> porterPreview(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "region")))))
                        .then(Commands.literal("stop").requires(WarehouseCommand::admin).executes(ctx -> porterStopAll(ctx.getSource()))))
                .then(Commands.literal("bot").requires(WarehouseCommand::admin)
                        .executes(ctx -> botList(ctx.getSource()))
                        .then(Commands.literal("add")
                                .executes(ctx -> botAdd(ctx.getSource(), ""))
                                .then(Commands.argument("region", NameArgument.name())
                                        .suggests(REGION_SUGGEST)
                                        .executes(ctx -> botAdd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "region")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(BOT_SUGGEST)
                                        .executes(ctx -> botRemove(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("assign")
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(BOT_SUGGEST)
                                        .then(Commands.argument("region", NameArgument.name())
                                                .suggests(REGION_SUGGEST)
                                                .executes(ctx -> botAssign(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "region"))))))
                        .then(Commands.literal("display")
                                // 优化7：只改「显示名」，注册名（= Carpet 的身份）一律不动
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(BOT_SUGGEST)
                                        .executes(ctx -> botDisplay(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), null))
                                        .then(Commands.argument("nick", StringArgumentType.greedyString())
                                                .executes(ctx -> botDisplay(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "nick"))))))
                        .then(Commands.literal("here")
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(BOT_SUGGEST)
                                        .executes(ctx -> botHere(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("spot")
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(BOT_SUGGEST)
                                        .executes(ctx -> botSpot(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"), false))
                                        .then(Commands.literal("clear")
                                                .executes(ctx -> botSpot(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"), true)))))
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(BOT_SUGGEST)
                                        .executes(ctx -> botSpawn(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("kill")
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(BOT_SUGGEST)
                                        .executes(ctx -> botKill(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("tidy")
                                .then(taskNode(Tasks.TIDY)))
                        .then(Commands.literal("sweep")
                                // 优化4：bot sweep 与 porter sweep 同命运 —— 入口在，语义没了
                                .executes(ctx -> sweepRemoved(ctx.getSource()))
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(BOT_SUGGEST)
                                        .executes(ctx -> sweepRemoved(ctx.getSource())))
                                .then(Commands.argument("region", NameArgument.name())
                                        .suggests(REGION_SUGGEST)
                                        .executes(ctx -> sweepRemoved(ctx.getSource()))))
                        .then(Commands.literal("stop")
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(BOT_SUGGEST)
                                        .executes(ctx -> botStop(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("user")
                        .then(Commands.literal("list").requires(WarehouseCommand::admin)
                                .executes(ctx -> userList(ctx.getSource())))
                        .then(Commands.literal("perm").requires(WarehouseCommand::admin)
                                .then(Commands.argument("name", NameArgument.name())
                                        .suggests(USER_SUGGEST)
                                        .then(Commands.literal("take")
                                                .then(permSwitch("take")))
                                        .then(Commands.literal("bot")
                                                .then(permSwitch("bot")))
                                        .then(Commands.literal("tidy")
                                                .then(permSwitch("tidy")))))
                        .then(Commands.literal("log").requires(WarehouseCommand::admin)
                                .executes(ctx -> userLog(ctx.getSource(), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1, 50))
                                        .executes(ctx -> userLog(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "page")))))
                        .then(Commands.literal("migrate").requires(WarehouseCommand::admin)
                                .executes(ctx -> userMigrate(ctx.getSource(), false))
                                .then(Commands.literal("force")
                                        .executes(ctx -> userMigrate(ctx.getSource(), true)))))
                .then(Commands.literal("give").requires(WarehouseCommand::canTake)
                        .executes(ctx -> give(ctx.getSource(), false))
                        .then(Commands.literal("all").executes(ctx -> give(ctx.getSource(), true)))));
    }

    /**
     * 玩家断线时忘掉他的选区。
     *
     * <p>不清的话，同一个玩家重新进服后上次会话的 pos1 / pos2 还在，
     * {@code /warehouse region save <名字>} 会拿旧选区误建一个仓库。
     *
     * <p>只清内存里的选点；PlayerPerms、RegionStore 这类持久化数据一律不碰。
     */
    public static void forgetPlayer(UUID id) {
        if (id == null) {
            return;
        }
        POS1.remove(id);
        POS2.remove(id);
    }

    /**
     * 管理权限判定：权限等级 ≥ 2、单人模式的房主、控制台/命令方块、或者在 ops.json 里的人。
     *
     * <p>注意单人存档未开启作弊时，房主的权限等级可能为 0 —— 因此不能只判定等级，
     * 具体见 {@link com.ds.warehouse.util.Admin}。
     */
    private static boolean admin(CommandSourceStack src) {
        return Admin.isAdmin(src);
    }

    /**
     * {@code /warehouse bot tidy <名字> [仓库]} 的参数节点：「名字 → 可选仓库」，
     * 不写仓库就用这个假人分配到的仓库。
     *
     * <p>{@code sweep} 在优化4 里已连同任务本体一起删除，不再复用这条节点（见 {@link #sweepRemoved}）。
     */
    private static RequiredArgumentBuilder<CommandSourceStack, String> taskNode(int kind) {
        return Commands.argument("name", NameArgument.name())
                .suggests(BOT_SUGGEST)
                .executes(ctx -> botTask(ctx.getSource(), StringArgumentType.getString(ctx, "name"), kind, ""))
                .then(Commands.argument("region", NameArgument.name())
                        .suggests(REGION_SUGGEST)
                        .executes(ctx -> botTask(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                kind, StringArgumentType.getString(ctx, "region"))));
    }

    /**
     * 优化4：{@code porter sweep} / {@code bot sweep} 的存根。
     *
     * <p>「清扫地面」＝假人自动拾取仓库范围内的掉落物，已随任务本体（{@code Tasks.SWEEP}）一起删除，
     * 命令层只剩这个同名入口。这里发一条 {@code sendFailure}（红字）并返回 {@code 0}，
     * 把「功能没了」说清楚 —— 而不是让玩家对着一条静默成功的命令猜自己是不是没权限。
     */
    private static int sweepRemoved(CommandSourceStack src) {
        src.sendFailure(Component.literal("清扫／自动拾取功能已在 0.21.0 移除：仓库范围内的掉落物不会再被自动捡起。"));
        return 0;
    }

    // ------------------------------------------------------------------

    /** {@code /warehouse porter spawn} —— 放出全部假人（各自待在值守点） */
    private static int porterSpawn(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        if (!Body.available()) {
            send(src, noCarpet());
            return 0;
        }
        if (!Body.enabled()) {
            send(src, bodyOff());
            return 0;
        }
        int n = 0;
        // 「放出全部假人」＝同时取消所有「收回」标记（否则被收回过的人放不出来）
        for (Bots.Entry e : Bots.list()) {
            Bots.setRecalled(e.name, false);
        }
        Bots.save();
        for (Bots.Entry e : Bots.list()) {
            Body.Home home = Body.standby(server, e.name);
            if (home == null) {
                continue;
            }
            if (Body.present(server, e.name)) {
                Body.moveTo(server, e.name, home.level(), home.x(), home.y(), home.z());
            } else {
                Body.ensure(server, e.name, home.level(), home.x(), home.y(), home.z());
            }
            n++;
        }
        send(src, n == 0
                ? noBotsYet("bot", false)
                : "已派出 " + n + " 个搬运工，约一两秒后出现在值守点。");
        return 1;
    }

    /** {@code /warehouse porter kill} —— 所有假人退场（物品先入库，不会丢失） */
    private static int porterKill(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        int n = 0;
        for (Bots.Entry e : Bots.list()) {
            if (Body.present(server, e.name)) {
                n++;
            }
        }
        if (n == 0) {
            send(src, "当前没有搬运工在世界中。");
            return 0;
        }
        Porter.flushBody(server);
        Body.removeAll(server);
        // 全部退场同样是持久状态：不记「收回」的话，下一 tick 待命逻辑会把它们逐个放回来
        for (Bots.Entry e : Bots.list()) {
            Bots.setRecalled(e.name, true);
        }
        Bots.save();
        send(src, "已令 " + n + " 个搬运工退场；拾取的物品已入库。");
        return 1;
    }

    /** {@code /warehouse porter home} —— 将全部假人的默认值守点设为你所在位置 */
    private static int porterHome(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        Body.setHomeHere(p);
        send(src, String.format(Locale.ROOT, "默认值守点已设为你所在位置（%.0f, %.0f, %.0f）；未分配仓库的搬运工在此待命。",
                p.getX(), p.getY(), p.getZ()));
        return 1;
    }

    /** {@code /warehouse porter body on|off} —— 人形开关 */
    private static int porterBody(CommandSourceStack src, boolean on) {
        AppConfig cfg = AppConfig.get();
        cfg.porterBody = on;
        cfg.save();
        if (on) {
            if (Body.available()) {
                send(src, "已开启人形，搬运工将在值守点待命。");
            } else {
                send(src, "已开启人形模式，但没有 Carpet，搬运工仍隐形工作。");
            }
        } else {
            Porter.flushBody(src.getServer());
            Body.removeAll(src.getServer());
            send(src, "已关闭人形，搬运工恢复隐形工作。");
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 玩家权限（/warehouse user …）

    /** {@code /warehouse user list} —— 管理员：列出全部有权限记录的玩家（含三项权限） */
    private static int userList(CommandSourceStack src) {
        List<PlayerPerms.Row> rows = PlayerPerms.rows();
        if (rows.isEmpty()) {
            send(src, "还没有任何玩家有权限记录。导入旧账号库：/warehouse user migrate");
            return 1;
        }
        send(src, "有权限的玩家 " + rows.size() + " 个（登记时间 / 权限）：");
        for (PlayerPerms.Row r : rows) {
            send(src, "  " + r.name() + "（" + Audit.timeText(r.addedAt()) + "）  "
                    + new PlayerPerms.Perm(r.take(), r.bot(), r.tidy()).text());
        }
        send(src, "改权限：/warehouse user perm <名字> take|bot|tidy on|off");
        return 1;
    }

    /** {@code /warehouse user perm <名字> <take|bot|tidy> on|off} —— 管理员：改某个玩家的权限 */
    private static int userPerm(CommandSourceStack src, String name, String kind, String onoff) {
        PlayerPerms.Perm old = PlayerPerms.perm(name);
        if (old == null) {
            send(src, "「" + name + "」还没有权限记录。用 /warehouse user list 查看现有玩家。");
            return 0;
        }
        boolean on = !onoff.equalsIgnoreCase("off") && !onoff.equals("关") && !onoff.equals("false");
        boolean take = old.take();
        boolean bot = old.bot();
        boolean tidy = old.tidy();
        switch (kind) {
            case "take" -> take = on;
            case "bot" -> bot = on;
            case "tidy" -> tidy = on;
            default -> {
                send(src, "仅支持三项权限：take（取物品）/ bot（操控搬运工）/ tidy（整理仓库）。");
                return 0;
            }
        }
        PlayerPerms.setPerm(name, take, bot, tidy);
        PlayerPerms.Perm now = new PlayerPerms.Perm(take, bot, tidy);
        Audit.add(adminName(src), "改权限", "把「" + name + "」的权限改成：" + now.text());
        send(src, "「" + name + "」现在的权限：" + now.text());
        return 1;
    }

    /** {@code /warehouse user migrate [force]} —— 管理员：把旧网页账号库的三项权限导入游戏内权限表 */
    private static int userMigrate(CommandSourceStack src, boolean force) {
        String msg = PlayerPerms.migrateFromWeb(force);
        Audit.add(adminName(src), "导入权限", msg);
        send(src, msg);
        List<String> names = PlayerPerms.names();
        send(src, names.isEmpty() ? "现在没有任何玩家有权限记录。" : "现在有权限的玩家：" + String.join("、", names));
        return 1;
    }

    /** {@code /warehouse user log [页数]} —— 管理员：查看操作日志（何人于何时取走何物） */
    private static int userLog(CommandSourceStack src, int page) {
        int per = 12;
        int offset = Math.max(0, (page - 1) * per);
        List<Audit.Entry> rows = Audit.recent(per, offset);
        if (rows.isEmpty()) {
            send(src, offset == 0 ? "暂无操作记录。" : "没有第 " + page + " 页。");
            return 1;
        }
        int total = Audit.size();
        int pages = Math.max(1, (total + per - 1) / per);
        send(src, "操作日志 " + page + "/" + pages + " 页（共 " + total + " 条）");
        for (Audit.Entry e : rows) {
            send(src, "  " + Audit.timeText(e.ts()) + "  " + e.who() + "：" + e.action()
                    + (e.detail().isEmpty() ? "" : " — " + e.detail()));
        }
        if (page < pages) {
            send(src, "下一页：/warehouse user log " + (page + 1));
        }
        return 1;
    }

    /** 操作日志里的「谁」：控制台记「控制台」，玩家记游戏名。 */
    private static String adminName(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        return p == null ? "控制台" : p.getGameProfile().name();
    }

    // ------------------------------------------------------------------
    // 假人名册 / 任务

    /**
     * {@code /warehouse porter staff [仓库]} —— 「派一名搬运工值守该仓库」。
     *
     * <p>1.1.1：一间仓库可以派多名假人（第二次执行就是「再派一名」）；先找闲置且未分配仓库的，都没有则新建一名。
     */
    private static int porterStaff(CommandSourceStack src, String region) {
        MinecraftServer server = src.getServer();
        if (region.isEmpty()) {
            send(src, "须指定仓库名，例如 /warehouse porter staff 仓库名。");
            return 0;
        }
        if (!RegionStore.REGIONS.containsKey(region)) {
            send(src, noRegion(region));
            return 0;
        }
        // 1.1.1：一个仓库可以派多名假人一起整理（以前第二个人直接被这句挡回去）。
        // 先数一下这间仓库已经有几个人，再照旧「先找闲置的、没有就新建一名」。
        int already = 0;
        for (Bots.Entry e : Bots.list()) {
            if (region.equals(e.region == null ? "" : e.region)) {
                already++;
            }
        }
        Bots.Entry spare = null;
        for (Bots.Entry e : Bots.list()) {
            if ((e.region == null || e.region.isEmpty())
                    && !Tasks.busy(e.name) && !Porter.busyWithOrder(e.name)) {
                spare = e;
                break;
            }
        }
        String head = already > 0 ? "「" + region + "」现共 " + (already + 1) + " 名搬运工" : "";
        String name;
        if (spare != null) {
            name = spare.name;
            Bots.assign(name, region);
            Bots.save();
            send(src, "已安排「" + name + "」改去值守仓库「" + region + "」。"
                    + (head.isEmpty() ? "" : "（" + head + "）"));
        } else {
            name = Bots.add(region);
            if (name == null) {
                send(src, botsFull());
                return 0;
            }
            Bots.save();
            send(src, "已新增搬运工「" + name + "」值守仓库「" + region + "」。"
                    + (head.isEmpty() ? "" : "（" + head + "）"));
        }
        Audit.add(adminName(src), "指挥搬运工", "派「" + name + "」值守仓库「" + region + "」");
        Body.Home home = Body.standby(server, name);
        if (home != null) {
            if (Body.present(server, name)) {
                Body.moveTo(server, name, home.level(), home.x(), home.y(), home.z());
            } else {
                Body.ensure(server, name, home.level(), home.x(), home.y(), home.z());
            }
        }
        return 1;
    }

    /**
     * {@code /warehouse porter tidy [仓库]} —— 让**所有**能干活的值守假人一起上。
     *
     * <p>1.1.1：以前这里只挑一名假人（{@code pickFreeBot}），一个仓库就算站了四个人也只有一个人动。
     * 现在按「值守仓库」分组，同一间仓库里的闲置假人一起开工，并给每人分配 1/N 的箱子（见
     * {@link Tasks#start(net.minecraft.server.MinecraftServer, String, int, String, int, int)}）。
     */
    private static int porterTask(CommandSourceStack src, int kind, String region) {
        MinecraftServer server = src.getServer();
        if (RegionStore.REGIONS.isEmpty()) {
            send(src, noRegions());
            return 0;
        }
        if (!region.isEmpty() && !RegionStore.REGIONS.containsKey(region)) {
            send(src, noRegion(region));
            return 0;
        }
        // 按值守仓库分组：同一间仓库里的闲置假人一起上；不同仓库各派各的，互不影响分片
        Map<String, List<Bots.Entry>> byRegion = new LinkedHashMap<>();
        for (Bots.Entry e : freeBots(region)) {
            String r = e.region == null ? "" : e.region;
            List<Bots.Entry> crew = byRegion.get(r);
            if (crew == null) {
                crew = new ArrayList<>();
                byRegion.put(r, crew);
            }
            crew.add(e);
        }
        if (byRegion.isEmpty()) {
            send(src, Bots.list().isEmpty()
                    ? noBotsYet("porter", false)
                    : "没有可用的搬运工：均忙碌，或未分配值守仓库（用 /warehouse bot assign <搬运工> <仓库> 指定）。");
            sendDispatchBlockers(src, region);
            return 0;
        }
        // 派单前先点名：此刻还没开工的人，就是这次上不了工的人。
        // （放在派单**之后**点名会把刚上工的人也算进去，回执就自相矛盾了）
        sendDispatchBlockers(src, region);
        int started = 0;
        // 同一条失败原因只说一次：清扫下线之类「全员都会失败」的节点不该按人数刷屏
        Set<String> said = new LinkedHashSet<>();
        for (Map.Entry<String, List<Bots.Entry>> grp : byRegion.entrySet()) {
            String r = grp.getKey();
            List<Bots.Entry> crew = grp.getValue();
            int parts = crew.size();
            List<String> ok = new ArrayList<>();
            for (int i = 0; i < crew.size(); i++) {
                Bots.Entry e = crew.get(i);
                String bad = Tasks.start(server, e.name, kind, r, i, parts);
                if (bad != null) {
                    if (said.add(bad)) {
                        send(src, bad);
                    }
                    continue;
                }
                started++;
                ok.add(e.name);
                if (!Body.present(server, e.name)) {
                    Body.Home home = Body.standby(server, e.name);
                    if (home != null) {
                        Body.ensure(server, e.name, home.level(), home.x(), home.y(), home.z());
                    }
                }
            }
            if (ok.isEmpty()) {
                continue;
            }
            // 有人没开成工（极端情况：派单瞬间被别的命令占走）：参战人数少了，分片得按实际人数重摊，
            // 否则「没人认领的那几片箱子」整轮都会被跳过 —— 跑完再单跑一次还会剩活。
            if (ok.size() < parts) {
                for (int i = 0; i < ok.size(); i++) {
                    Tasks.retuneParts(ok.get(i), i, ok.size());
                }
            }
            if (ok.size() > 1) {
                send(src, "仓库「" + r + "」" + Tasks.kindName(kind) + "已分给 " + ok.size() + " 名假人："
                        + String.join("、", ok) + "（各管 1/" + ok.size() + " 的箱子）。");
            } else {
                send(src, "「" + ok.get(0) + "」已开始" + Tasks.kindName(kind) + "（仓库「" + r + "」）。");
            }
        }
        if (started <= 0) {
            return 0;
        }
        Audit.add(adminName(src), Tasks.kindName(kind), "让 " + started + " 名搬运工整理各自值守的仓库");
        return 1;
    }

    /**
     * 批次 6 · D4：{@code /warehouse porter preview [仓库]} —— 干跑一遍整理，只报数、不搬东西。
     *
     * <p>不带仓库名就把每个仓库都预览一遍（预览不需要空闲假人，所以不走 {@link #freeBots(String)}）。
     */
    private static int porterPreview(CommandSourceStack src, String region) {
        if (RegionStore.REGIONS.isEmpty()) {
            send(src, noRegions());
            return 0;
        }
        MinecraftServer server = src.getServer();
        List<String> names = new ArrayList<>();
        if (region.isEmpty()) {
            names.addAll(RegionStore.REGIONS.keySet());
            names.sort(Comparator.naturalOrder());
        } else {
            if (RegionStore.REGIONS.get(region) == null) {
                send(src, noRegion(region));
                return 0;
            }
            names.add(region);
        }
        for (String name : names) {
            for (String line : Tasks.preview(server, name).split("\n")) {
                send(src, line);
            }
        }
        return 1;
    }

    /** 所有能干活的值守假人（按名册顺序）：只从「已经分配值守仓库」的假人里挑，绝不派跨仓库的活 */
    private static List<Bots.Entry> freeBots(String region) {
        List<Bots.Entry> out = new ArrayList<>();
        for (Bots.Entry e : Bots.list()) {
            if (Tasks.busy(e.name) || Porter.busyWithOrder(e.name) || Bots.isRecalled(e.name)) {
                continue;
            }
            String r = e.region == null ? "" : e.region;
            if (r.isEmpty() || RegionStore.REGIONS.get(r) == null) {
                continue;
            }
            if (!region.isEmpty() && !region.equals(r)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    /**
     * 点名「这次派单范围内、但没上工」的搬运工及原因（1.1.4 · 优化8）。
     *
     * <p>以前 {@link #freeBots(String)} 是**静默过滤**：玩家给同一间仓库派了几个假人，结果只有一个在动，
     * 甚至一个都不动，回执里也看不出为什么。这里把被过滤掉的人和原因一并报出来（只报范围内的，
     * 指定仓库时值守别的仓库的假人不算漏派）。
     */
    private static void sendDispatchBlockers(CommandSourceStack src, String region) {
        List<String> out = new ArrayList<>();
        for (Bots.Entry e : Bots.list()) {
            String r = e.region == null ? "" : e.region;
            if (!region.isEmpty() && !region.equals(r)) {
                continue;
            }
            String why = null;
            if (Tasks.busy(e.name)) {
                why = "正在执行「" + Tasks.kindOf(e.name) + "」";
            } else if (Porter.busyWithOrder(e.name)) {
                why = "正在配送订单";
            } else if (Bots.isRecalled(e.name)) {
                why = "已被收回（歇班），可在面板点「上岗」";
            } else if (r.isEmpty()) {
                why = "还没分配值守仓库";
            } else if (RegionStore.REGIONS.get(r) == null) {
                why = "值守的仓库「" + r + "」已不存在";
            }
            if (why != null) {
                out.add(e.name + "（" + why + "）");
            }
        }
        if (out.isEmpty()) {
            return;
        }
        // 人多了别刷屏：最多点 6 个名字，剩下的只说个数
        int shown = Math.min(out.size(), 6);
        send(src, "这次没上工的 " + out.size() + " 名：" + String.join("、", out.subList(0, shown))
                + (out.size() > shown ? " 等" : "") + "。");
    }

    /** {@code /warehouse porter stop} —— 令所有假人停止当前任务 */
    private static int porterStopAll(CommandSourceStack src) {
        boolean any = false;
        for (Bots.Entry e : Bots.list()) {
            if (Tasks.busy(e.name)) {
                any = true;
            }
        }
        Tasks.cancelAll();
        if (any) {
            Audit.add(adminName(src), "停下", "让所有搬运工停止当前任务");
        }
        send(src, any ? "已令所有搬运工停止。" : "当前没有搬运工在执行任务。");
        return 1;
    }

    // ------------------------------------------------------------------

    /** {@code /warehouse bot} —— 列出全部搬运工的当前状态 */
    private static int botList(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        List<Bots.Entry> bots = Bots.list();
        if (bots.isEmpty()) {
            send(src, noBotsYet("bot", true));
            return 1;
        }
        send(src, "搬运工名册 " + bots.size() + "/" + Bots.MAX + " 个");
        for (Bots.Entry e : bots) {
            StringBuilder sb = new StringBuilder("  ").append(e.name);
            String disp = Bots.displayOf(e.name);
            if (!disp.equals(e.name)) {
                // 优化7：设了昵称就把昵称跟在注册名后面，注册名始终留着（它才是身份）
                sb.append("（").append(disp).append("）");
            }
            sb.append(" 值守 ").append(e.region == null || e.region.isEmpty() ? "未派" : e.region);
            String own = Bots.spotText(e.name);
            sb.append(" 值守点 ").append(own.isEmpty() ? "自动" : own + "（自定义）");
            if (!Body.enabled() || !Body.available()) {
                // 人形没开：只报名册，不带位置
            } else if (Body.present(server, e.name)) {
                ServerPlayer p = Body.get(server, e.name);
                sb.append(String.format(Locale.ROOT, " 位置 %.0f,%.0f,%.0f",
                        p == null ? 0 : p.getX(), p == null ? 0 : p.getY(), p == null ? 0 : p.getZ()));
            } else {
                sb.append(" （不在世界中）");
            }
            if (Tasks.busy(e.name)) {
                sb.append(" 正在").append(Tasks.kindOf(e.name));
            } else if (Porter.busyWithOrder(e.name)) {
                sb.append(" 正在配送");
            }
            send(src, sb.toString());
        }
        send(src, "指令：/warehouse bot add|remove|assign|display|here|spot|spawn|kill|tidy|sweep|stop");
        return 1;
    }

    /**
     * {@code /warehouse bot display <名字> [昵称]} —— 查看 / 设置搬运工的显示名。
     *
     * <p>优化7 的核心约束：**注册名是身份**（Carpet 按名字派生 UUID，无法改名），
     * 所以昵称只做「给人看的那一层」，存在 {@code bots.json} 的 {@code display} 字段里；
     * 所有指令、任务、冷却、事件仍然只用注册名。
     */
    private static int botDisplay(CommandSourceStack src, String name, String nick) {
        Bots.Entry e = Bots.of(name);
        if (e == null) {
            send(src, "没有名为「" + name + "」的搬运工（用 /warehouse bot list 看名册）");
            return 0;
        }
        if (nick == null) {
            String now = Bots.displayOf(name);
            if (now.equals(name)) {
                send(src, "搬运工「" + name + "」没有设置显示名，面板里显示注册名。");
                send(src, "设置：/warehouse bot display " + name + " <昵称>");
            } else {
                send(src, "搬运工「" + name + "」的显示名：" + now + "（注册名仍是 " + name + "）");
            }
            return 1;
        }
        String text = nick.trim();
        // 清空：显式关键词或全空白都算「清除昵称」
        boolean clear = text.isEmpty() || text.equalsIgnoreCase("clear") || text.equalsIgnoreCase("none")
                || text.equals("清除") || text.equals("无");
        if (clear) {
            if (!Bots.setDisplay(name, null)) {
                send(src, "搬运工「" + name + "」本来就没有显示名。");
                return 1;
            }
            Bots.save();
            Audit.add(adminName(src), "指挥搬运工", "清除搬运工「" + name + "」的显示名");
            send(src, "已清除搬运工「" + name + "」的显示名，面板/网页恢复显示注册名。");
            return 1;
        }
        if (text.length() > 24) {
            send(src, "显示名最多 24 个字符（现在 " + text.length() + " 个）");
            return 0;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 换行/制表符会打乱面板排版；§ 颜色代码会让按字符截断算不准宽度
            if (c < 0x20 || c == 0x7F || c == '§') {
                send(src, "显示名不能包含换行、制表符或颜色代码（§）");
                return 0;
            }
        }
        if (!Bots.setDisplay(name, text)) {
            send(src, "搬运工「" + name + "」的显示名没有变化。");
            return 1;
        }
        Bots.save();
        Audit.add(adminName(src), "指挥搬运工", "把搬运工「" + name + "」的显示名设为「" + text + "」");
        send(src, "已把搬运工「" + name + "」的显示名设为「" + text + "」。");
        send(src, "注意：指令与任务仍然只用注册名 " + name + "；假人头顶名牌也还是注册名（原版无法改）。");
        return 1;
    }

    /** {@code /warehouse bot add [仓库]} —— 新建一名假人 */
    private static int botAdd(CommandSourceStack src, String region) {
        MinecraftServer server = src.getServer();
        if (!region.isEmpty() && !RegionStore.REGIONS.containsKey(region)) {
            send(src, noRegion(region));
            return 0;
        }
        String name = Bots.add(region);
        if (name == null) {
            send(src, botsFull());
            return 0;
        }
        Bots.save();
        Audit.add(adminName(src), "指挥搬运工", "新增搬运工「" + name + "」"
                + (region.isEmpty() ? "" : "值守仓库「" + region + "」"));
        send(src, "已新增搬运工「" + name + "」" + (region.isEmpty() ? "" : "，值守仓库「" + region + "」") + "。");
        if (Body.enabled() && Body.available()) {
            Body.Home home = Body.standby(server, name);
            if (home != null) {
                Body.ensure(server, name, home.level(), home.x(), home.y(), home.z());
            }
        } else if (!Body.available()) {
            send(src, noCarpet());
        }
        return 1;
    }

    /** {@code /warehouse bot remove <名字>} —— 移除一名假人 */
    private static int botRemove(CommandSourceStack src, String name) {
        MinecraftServer server = src.getServer();
        Bots.Entry e = Bots.of(name);
        if (e == null) {
            send(src, noBot(name));
            return 0;
        }
        Tasks.cancel(e.name);
        if (Body.present(server, e.name)) {
            Porter.flushBody(server, e.name);
            Body.remove(server, e.name);
        }
        Bots.remove(e.name);
        Bots.save();
        Audit.add(adminName(src), "指挥搬运工", "撤掉搬运工「" + e.name + "」");
        send(src, "已移除搬运工「" + e.name + "」，拾取的物品已入库。");
        return 1;
    }

    /** {@code /warehouse bot assign <名字> <仓库>} —— 将一名假人分配到指定仓库值守 */
    private static int botAssign(CommandSourceStack src, String name, String region) {
        MinecraftServer server = src.getServer();
        Bots.Entry e = Bots.of(name);
        if (e == null) {
            send(src, noBot(name));
            return 0;
        }
        if (!region.isEmpty() && !RegionStore.REGIONS.containsKey(region)) {
            send(src, noRegion(region));
            return 0;
        }
        Bots.assign(e.name, region);
        Bots.save();
        Audit.add(adminName(src), "指挥搬运工", "把「" + e.name + "」"
                + (region.isEmpty() ? "改成不指定仓库" : "分配到仓库「" + region + "」"));
        if (Body.present(server, e.name)) {
            Body.Home home = Body.standby(server, e.name);
            if (home != null) {
                Body.moveTo(server, e.name, home.level(), home.x(), home.y(), home.z());
            }
        }
        send(src, "搬运工「" + e.name + "」" + (region.isEmpty()
                ? "已改为不指定仓库，分配值守仓库前不会执行任务。"
                : "已分配到仓库「" + region + "」，将在该仓库内待命与执行任务。"));
        return 1;
    }

    /** {@code /warehouse bot here <名字>} —— 将其分配到你所站立的这个仓库 */
    private static int botHere(CommandSourceStack src, String name) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        String region = regionAt(p);
        if (region == null) {
            send(src, "你当前不在任何仓库内。请站到仓库范围内，或用 /warehouse bot assign " + name + " <仓库名>。");
            return 0;
        }
        return botAssign(src, name, region);
    }

    /**
     * {@code /warehouse bot spot <名字>} —— 将该假人的值守点设为你当前所在位置。
     *
     * <p>{@code ... clear} 表示取消，回到自动判定（仓库里箱子旁 → 仓库中心 → 全局值守点）。
     * 只影响这一个假人；{@code /warehouse porter home} 那个全局点不会被覆盖。
     */
    private static int botSpot(CommandSourceStack src, String name, boolean clear) {
        MinecraftServer server = src.getServer();
        Bots.Entry e = Bots.of(name);
        if (e == null) {
            send(src, noBot(name));
            return 0;
        }
        if (clear) {
            Bots.setSpot(e.name, "", 0, 0, 0);
            Bots.save();
            Audit.add(adminName(src), "指挥搬运工", "取消「" + e.name + "」的自定义值守点");
            send(src, "已取消「" + e.name + "」的自定义值守点，恢复自动判定。");
            if (Body.enabled() && Body.available() && Body.present(server, e.name)) {
                Body.Home home = Body.standby(server, e.name);
                if (home != null) {
                    Body.moveTo(server, e.name, home.level(), home.x(), home.y(), home.z());
                }
            }
            return 1;
        }
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            send(src, "该指令须在游戏内执行：请站到希望「" + e.name + "」待命的位置后再执行。");
            return 0;
        }
        if (!Body.setSpotHere(player, e.name)) {
            send(src, noBot(name));
            return 0;
        }
        Bots.save();
        Audit.add(adminName(src), "指挥搬运工", "设「" + e.name + "」的值守点为 "
                + Bots.spotText(e.name));
        Body.Home home = Body.standby(server, e.name);
        String pos = home == null ? ""
                : String.format(Locale.ROOT, "（%.0f, %.0f, %.0f）", home.x(), home.y(), home.z());
        send(src, "已将「" + e.name + "」的值守点设为你所在位置" + pos
                + "。取消：/warehouse bot spot " + e.name + " clear");
        if (home != null && Body.enabled() && Body.available() && Body.present(server, e.name)) {
            Body.moveTo(server, e.name, home.level(), home.x(), home.y(), home.z());
        }
        return 1;
    }

    private static String regionAt(ServerPlayer p) {
        String raw = p.level().dimension().identifier().toString();
        for (Region r : RegionStore.REGIONS.values()) {
            if (!r.dimension.equals(raw)) {
                continue;
            }
            if (r.contains(p.blockPosition())) {
                return r.name;
            }
        }
        return null;
    }

    /** {@code /warehouse bot spawn <名字>} —— 仅放出指定的假人 */
    private static int botSpawn(CommandSourceStack src, String name) {
        MinecraftServer server = src.getServer();
        Bots.Entry e = Bots.of(name);
        if (e == null) {
            send(src, noBot(name));
            return 0;
        }
        if (!Body.available()) {
            send(src, noCarpet());
            return 0;
        }
        if (!Body.enabled()) {
            send(src, bodyOff());
            return 0;
        }
        // 上岗 = 清掉「收回」标记，否则 Body.ensure 会拒绝把它放出来
        Bots.setRecalled(e.name, false);
        Bots.save();
        Body.Home home = Body.standby(server, e.name);
        if (home == null) {
            send(src, "还定不了「" + e.name + "」的值守点：先把它分配到仓库，或站到待命位置执行 /warehouse porter home。");
            return 0;
        }
        if (Body.present(server, e.name)) {
            Body.moveTo(server, e.name, home.level(), home.x(), home.y(), home.z());
            send(src, "「" + e.name + "」已在世界中，已令其返回值守点。");
            return 1;
        }
        Body.ensure(server, e.name, home.level(), home.x(), home.y(), home.z());
        send(src, "已派出「" + e.name + "」，约一两秒后出现在值守点。");
        return 1;
    }

    /** {@code /warehouse bot kill <名字>} —— 令某一名假人退场（并记成「收回」，不再自动放出） */
    private static int botKill(CommandSourceStack src, String name) {
        MinecraftServer server = src.getServer();
        Bots.Entry e = Bots.of(name);
        if (e == null) {
            send(src, noBot(name));
            return 0;
        }
        boolean was = Body.present(server, e.name);
        if (was) {
            Porter.flushBody(server, e.name);
            Body.remove(server, e.name);
        }
        // 「收回」是持久状态：不收住的话，下一 tick 的待命逻辑就会把它当场放回来
        Bots.setRecalled(e.name, true);
        Bots.save();
        send(src, was
                ? "「" + e.name + "」已退场，拾取的物品已入库。"
                : "「" + e.name + "」已记为收回。");
        return 1;
    }

    /** {@code /warehouse bot tidy|sweep <名字> [仓库]} —— 派发任务 */
    private static int botTask(CommandSourceStack src, String name, int kind, String region) {
        MinecraftServer server = src.getServer();
        Bots.Entry e = Bots.of(name);
        if (e == null) {
            send(src, noBot(name));
            return 0;
        }
        if (!region.isEmpty() && !RegionStore.REGIONS.containsKey(region)) {
            send(src, noRegion(region));
            return 0;
        }
        String bad = Tasks.start(server, e.name, kind, region);
        if (bad != null) {
            send(src, bad);
            return 0;
        }
        if (Body.enabled() && Body.available()) {
            Body.Home home = Body.standby(server, e.name);
            if (home != null) {
                Body.ensure(server, e.name, home.level(), home.x(), home.y(), home.z());
            }
        }
        send(src, "已派「" + e.name + "」执行" + Tasks.kindName(kind)
                + "（仓库「" + (e.region == null ? "" : e.region) + "」）。");
        Audit.add(adminName(src), Tasks.kindName(kind), "派「" + e.name + "」整理自己值守的仓库「"
                + (e.region == null ? "" : e.region) + "」");
        return 1;
    }

    /** {@code /warehouse bot stop <名字>} —— 令其停止当前任务 */
    private static int botStop(CommandSourceStack src, String name) {
        Bots.Entry e = Bots.of(name);
        if (e == null) {
            send(src, noBot(name));
            return 0;
        }
        if (!Tasks.busy(e.name)) {
            send(src, "「" + e.name + "」当前没有执行任务。");
            return 0;
        }
        Tasks.cancel(e.name);
        Audit.add(adminName(src), "停下", "让「" + e.name + "」停止当前任务");
        send(src, "已令「" + e.name + "」停止。");
        return 1;
    }

    /**
     * {@code /warehouse order <物品名> [数量]}。
     *
     * 物品名用 greedyString，所以「钻石 64」这种写法要把数量从末尾自己切出来 ——
     * brigadier 的 greedyString 必须是最后一个参数，没法再挂一个可选的整数参数。
     */
    private static int order(CommandSourceStack src, String raw) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        String query = raw == null ? "" : raw.trim();
        int count = 1;
        int space = query.lastIndexOf(' ');
        if (space > 0) {
            String tail = query.substring(space + 1);
            if (tail.matches("\\d{1,5}")) {
                count = Integer.parseInt(tail);
                query = query.substring(0, space).trim();
            }
        }
        if (query.isEmpty()) {
            send(src, "用法：/warehouse order <物品名> [数量]");
            return 0;
        }
        // 1.1.4 · 与严格库存语义一致：超过单次上限不再静默压成上限，而是明确拒绝
        if (count > Porter.MAX_ORDER) {
            send(src, "一次最多下单 " + Porter.MAX_ORDER + " 个（你说了 " + count + " 个），没有下单。");
            return 0;
        }
        count = Math.max(1, count);
        String err = Porter.request(player, query, count);
        if (err != null) {
            send(src, err);
            return 0;
        }
        send(src, "已下单 " + count + " 个 " + query + "，送到后用 /warehouse porter 查看进度。");
        Audit.add(player.getGameProfile().name(), "取物品", "下单要 " + count + " 个「" + query + "」");
        return 1;
    }

    /**
     * {@code /warehouse give}（手持的这一叠）/ {@code /warehouse give all}（背包内可入库的全部入库）。
     */
    private static int give(CommandSourceStack src, boolean all) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        if (WarehouseMod.INDEX.items.isEmpty() && Homes.size() == 0) {
            send(src, noIndex());
            return 0;
        }
        if (!all) {
            ItemStack held = player.getMainHandItem();
            if (held.isEmpty()) {
                send(src, "你手上没有物品：手持物品执行 /warehouse give，或用 /warehouse give all 全部入库。");
                return 0;
            }
            String msg = Porter.deposit(src.getServer(), player, held);
            send(src, msg);
            return 1;
        }
        int stacks = 0;
        int moved = 0;
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            int before = s.getCount();
            Porter.deposit(src.getServer(), player, s);
            int after = s.isEmpty() ? 0 : s.getCount();
            if (after < before) {
                stacks++;
                moved += before - after;
            }
            if (!s.isEmpty() && problems.size() < 3) {
                String id = ItemIds.of(s);
                problems.add(Names.item(id) + " x" + after);
            }
        }
        player.inventoryMenu.broadcastChanges();
        if (stacks == 0) {
            if (problems.isEmpty()) {
                send(src, "背包为空，无需入库。");
            } else {
                send(src, "未能入库。" + tail(problems));
            }
            return 0;
        }
        send(src, "已入库 " + moved + " 个（" + stacks + " 叠）。" + tail(problems));
        return 1;
    }

    private static String tail(List<String> problems) {
        if (problems.isEmpty()) {
            return "";
        }
        return " 以下物品没能入库（没有固定位置或容器已满）：" + String.join("、", problems);
    }

    // ------------------------------------------------------------------

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> REGION_SUGGEST =
            (ctx, builder) -> SharedSuggestionProvider.suggest(RegionStore.REGIONS.keySet(), builder);

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> BOT_SUGGEST =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    Bots.list().stream().map(e -> e.name).toList(), builder);

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> USER_SUGGEST =
            (ctx, builder) -> SharedSuggestionProvider.suggest(PlayerPerms.names(), builder);

    /** {@code /warehouse user perm <名字> <take|bot|tidy> on|off} 里的开关 */
    private static RequiredArgumentBuilder<CommandSourceStack, String> permSwitch(String kind) {
        // 这里是 on/off 枚举值，刻意保持原版 word()：写错时 Brigadier 的报错更直接
        return Commands.argument("onoff", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("on", "off"), builder))
                .executes(ctx -> userPerm(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name"),
                        kind,
                        StringArgumentType.getString(ctx, "onoff")));
    }

    private static final List<String> DIRECTIONS =
            List.of("north", "south", "east", "west", "up", "down", "北", "南", "东", "西", "上", "下");

    /**
     * 构建 {@code /warehouse region grow <名字> [方向] [格数]} 里的「名字」参数节点。
     *
     * <p>方向刻意用 {@link Commands#literal} 而不是 {@code word()} 参数：
     * brigadier 的 word() 只认 {@code [a-zA-Z0-9_]}，中文方向（北/南/东/西/上/下）
     * 会被判成非法字符，报 "Expected whitespace to end one argument"。
     *
     * <p>仓库名用 {@link NameArgument}：不带引号读到空白为止，所以中文名可以裸写，
     * 名字里有空格时写成 {@code "东边 仓库"}。
     */
    private static RequiredArgumentBuilder<CommandSourceStack, String> growNode() {
        RequiredArgumentBuilder<CommandSourceStack, String> nameNode =
                Commands.argument("name", NameArgument.name())
                        .suggests(REGION_SUGGEST)
                 // 不带方向 = 把仓库扩到包含「你现在站的位置」
                        .executes(ctx -> growRegion(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"), null, 0));

        for (String dir : DIRECTIONS) {
            nameNode = nameNode.then(Commands.literal(dir)
                    // 省略格数 = 扩 1 格
                    .executes(ctx -> growRegion(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"), dir, 1))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 4096))
                            .executes(ctx -> growRegion(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "name"), dir,
                                    IntegerArgumentType.getInteger(ctx, "amount")))));
        }
        return nameNode;
    }

    /**
     * 构建 {@code /warehouse region shrink <名字> [方向] [格数]} 里的「名字」参数节点。
     *
     * <p>参数结构与 {@link #growNode()} 完全对偶，只是方向列表同一份
     * {@link #DIRECTIONS}（中文方向同样必须用 literal，理由见 growNode 的注释）。
     */
    private static RequiredArgumentBuilder<CommandSourceStack, String> shrinkNode() {
        RequiredArgumentBuilder<CommandSourceStack, String> nameNode =
                Commands.argument("name", NameArgument.name())
                        .suggests(REGION_SUGGEST)
                        // 不带方向 = 缩到「我现在站的区块」（X/Z 收到该区块的 16×16，Y 不变）
                        .executes(ctx -> shrinkRegion(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"), null, 0));

        for (String dir : DIRECTIONS) {
            nameNode = nameNode.then(Commands.literal(dir)
                    // 省略格数 = 缩 1 格
                    .executes(ctx -> shrinkRegion(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"), dir, 1))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 4096))
                            .executes(ctx -> shrinkRegion(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "name"), dir,
                                    IntegerArgumentType.getInteger(ctx, "amount")))));
        }
        return nameNode;
    }

    private static void help(CommandSourceStack src) {
        send(src, "===== warehouse-keeper =====");
        send(src, "用法：/warehouse <子命令> [参数]");
        send(src, "仓库：pos1|pos2、region save/list/info/remove、region grow/shrink/merge/height");
        send(src, "索引：scan、status、list、find、enchants [find <附魔或自定义名>]、stats、save、load、clear、settings、categories");
        send(src, "搬运工：porter / bot（add/remove/assign/display/here/spot/spawn/kill/tidy/sweep/stop）");
        send(src, "取货与入库：order、give [all]、tidy、sweep");
        send(src, "标签：tag show/list/set/auto/staging/clear/mode/prune");
        send(src, "权限：user list/perm/log/migrate（仅管理员；改权限：take 取货 / bot 指挥 / tidy 整理）");
        send(src, "修改性命令限管理员（OP）；普通玩家可用 status、list、find、enchants、stats、order、give、porter、tag。");
        send(src, "用法与示例见各子命令不带参数时的提示");
    }

    /** 索引来源 / 磁盘文件，status / save / load 共用 */
    private static void showIndexInfo(CommandSourceStack src) {
        WarehouseIndex idx = WarehouseMod.INDEX;
        if (idx.restoredFromDisk) {
            send(src, "索引来源: 磁盘（保存于 " + IndexStore.fmt(idx.restoredSavedAt) + "）。刷新：/warehouse scan");
        } else if (idx.lastScanMillis > 0) {
            send(src, "索引来源: 本次扫描");
        } else {
            send(src, "索引来源: 空");
        }
        send(src, "磁盘索引: " + IndexStore.fileFor(src.getServer()) + "  上次保存: " + IndexStore.lastSavedText());
    }

    private static void showSettings(CommandSourceStack src) {
        AppConfig cfg = AppConfig.get();
        send(src, "===== warehouse-keeper 设置 =====");
        send(src, "开档自动重新扫描 autoScanOnWorldLoad = " + cfg.autoScanOnWorldLoad);
        send(src, "自动扫描延迟 autoScanDelaySeconds = " + cfg.autoScanDelaySeconds + " 秒");
        send(src, "开档读取磁盘索引 loadIndexFromDisk = " + cfg.loadIndexFromDisk);
        send(src, "扫描后保存磁盘索引 saveIndexToDisk = " + cfg.saveIndexToDisk);
        send(src, "扫描时临时加载区块 scanForceLoadChunks = " + cfg.scanForceLoadChunks
                + (cfg.scanForceLoadChunks ? "（仓库很远也能扫到）" : "（只扫已加载区块）"));
        send(src, "改设置: /warehouse settings autoscan|autoload|autosave|forcechunks on|off，delay <秒>");
        send(src, "不进索引的容器 containerExclude = "
                + (cfg.containerExclude == null || cfg.containerExclude.isBlank()
                        ? "（空 —— 所有容器都会被索引；要完全不碰某类容器就填方块 id，逗号分隔）"
                        : cfg.containerExclude));
        send(src, "（0.23.0 起书架 / 熔炉 / 漏斗这些照常进索引、能查看，只是整理时不动它们）");
        send(src, "配置文件: config/warehouse-keeper/settings.json");
    }

    private static int setSetting(CommandSourceStack src, String key, boolean value) {
        AppConfig cfg = AppConfig.get();
        String label;
        switch (key) {
            case "autoScanOnWorldLoad" -> {
                cfg.autoScanOnWorldLoad = value;
                label = "开档自动重新扫描";
                if (!value) {
                    AutoScan.cancel();
                }
            }
            case "loadIndexFromDisk" -> {
                cfg.loadIndexFromDisk = value;
                label = "开档读取磁盘索引";
            }
            case "saveIndexToDisk" -> {
                cfg.saveIndexToDisk = value;
                label = "扫描后保存磁盘索引";
            }
            case "scanForceLoadChunks" -> {
                cfg.scanForceLoadChunks = value;
                label = "扫描时临时加载区块";
            }
            default -> {
                send(src, "未知设置项 " + key);
                return 0;
            }
        }
        cfg.save();
        send(src, label + " 已设为 " + (value ? "开" : "关") + "（已生效）");
        return 1;
    }

    /**
     * /warehouse region height &lt;名字&gt; full|limited
     *
     * <p>full = 圈出来的长方形里「任何高度的箱子」都算（默认）；
     * limited = 只算当初圈到的那个 Y 范围。
     */
    private static int setHeight(CommandSourceStack src, String rawName, boolean full) {
        Region r = lookupRegion(src, rawName);
        if (r == null) {
            return 0;
        }
        r.fullHeight = full;
        RegionStore.save();
        Audit.add(adminName(src), "改仓库", "把仓库「" + r.name + "」改成" + r.heightText());
        send(src, "仓库 " + r.name + " 已设为「" + r.heightText() + "」");
        if (full) {
            send(src, "  任意高度的容器都会被计入，无需设 Y。");
        } else {
            send(src, "  只统计 Y " + r.min().getY() + " ~ " + r.max().getY()
                    + "；恢复全高度：/warehouse region height " + r.name + " full");
        }
        return 1;
    }

    private static void listRegions(CommandSourceStack src) {
        if (RegionStore.REGIONS.isEmpty()) {
            send(src, noRegions());
            return;
        }
        send(src, "共 " + RegionStore.REGIONS.size() + " 个仓库:");
        for (Region r : RegionStore.REGIONS.values()) {
            send(src, "  " + r.name + "  " + Names.dimension(Scanner.dimensionOf(r)) + "  " + r.describeCorners()
                    + "  " + r.blockVolume() + " 方块");
        }
    }

    // ------------------------------------------------------------------ 扩建

    /** /warehouse region grow <名字> [方向 格数] —— 在原有范围基础上扩大 */
    private static int growRegion(CommandSourceStack src, String rawName, String direction, int amount) {
        Region r = lookupRegion(src, rawName);
        if (r == null) {
            return 0;
        }
        String before = r.describeCorners();
        long volumeBefore = r.blockVolume();

        if (direction == null) {
            // 不带方向 = 扩到包含「你现在站的位置」
            ServerPlayer p = src.getPlayer();
            if (p == null) {
                send(src, "该用法须在游戏内执行（要用到你所在的位置）。");
                send(src, "控制台用法：/warehouse region grow " + r.name + " <方向> <格数>");
                return 0;
            }
            if (!sameDimension(r, p)) {
                send(src, "你当前在" + Names.dimension(p.level().dimension().identifier().toString())
                        + "，仓库 " + r.name + " 在" + Names.dimension(Scanner.dimensionOf(r)) + "，无法扩建。");
                return 0;
            }
            r.include(p.blockPosition());
        } else {
            String err = r.grow(direction, amount);
            if (err != null) {
                send(src, err);
                return 0;
            }
        }

        RegionStore.save();
        long volumeAfter = r.blockVolume();
        send(src, "仓库 " + r.name + " 已扩建：");
        send(src, "  原来  " + before);
        send(src, "  现在  " + r.describeCorners()
                + "（" + volumeAfter + " 方块，增加 " + Math.max(0, volumeAfter - volumeBefore) + "）");
        if (volumeAfter == volumeBefore) {
            send(src, "  （范围没变：你要扩到的位置本来就在仓库内）");
        }
        if (r.fullHeight && direction != null) {
            String d = direction.trim().toLowerCase(Locale.ROOT);
            if (d.equals("up") || d.equals("u") || d.equals("上")
                    || d.equals("down") || d.equals("d") || d.equals("下")) {
                send(src, "  （全高度仓库上下扩建无效，横向扩建才有作用）");
            }
        }
        warnIfHuge(src, r);
        send(src, "重新扫描后新容器才会进索引：/warehouse scan " + r.name);
        Audit.add(adminName(src), "改仓库", "把仓库「" + r.name + "」扩建到 " + r.describeCorners());
        return 1;
    }

    /**
     * {@code /warehouse region shrink <名字> [方向] [格数]} —— 缩小仓库（优化8）。
     *
     * <p>与 {@link #growRegion} 的关键差别：扩建时「新进来的箱子还没扫到」无所谓，缩小却必须
     * <b>主动把被缩掉的箱子移出索引</b>，否则查询 / 下单 / 整理会指向已经不属于本仓库的箱子。
     * 这里把「旧范围 − 新范围」拆成最多 6 片 1 格厚的薄板（见 {@link #removedSlabs}）交给
     * {@link WarehouseIndex#removeContainersIn(List)}，而**不用**「不在新范围里就删」的口径：
     * 索引是全局的（含别的仓库、别的维度），那种口径会把别人的记录一起删掉。
     *
     * <p>不带方向 = 缩到「我现在站的区块」：新范围 = 旧范围 ∩ 我所在区块的 16×16（X/Z，Y 不变）。
     * 取交集而不是直接把范围改成那个区块，是为了保证这个用法**只会缩小、绝不会偷偷扩大**。
     */
    private static int shrinkRegion(CommandSourceStack src, String rawName, String direction, int amount) {
        Region r = lookupRegion(src, rawName);
        if (r == null) {
            return 0;
        }
        BlockPos oldLo = r.min();
        BlockPos oldHi = r.max();
        String before = r.describeCorners();
        long volumeBefore = r.blockVolume();
        boolean chunkMode = direction == null;

        if (chunkMode) {
            ServerPlayer p = src.getPlayer();
            if (p == null) {
                send(src, "该用法须在游戏内执行（要用到你所在的位置）。");
                send(src, "控制台用法：/warehouse region shrink " + r.name + " <方向> <格数>");
                return 0;
            }
            if (!sameDimension(r, p)) {
                send(src, "你当前在" + Names.dimension(p.level().dimension().identifier().toString())
                        + "，仓库 " + r.name + " 在" + Names.dimension(Scanner.dimensionOf(r)) + "，无法缩小。");
                return 0;
            }
            BlockPos pp = p.blockPosition();
            if (pp.getX() < oldLo.getX() || pp.getX() > oldHi.getX()
                    || pp.getZ() < oldLo.getZ() || pp.getZ() > oldHi.getZ()) {
                send(src, "你现在没站在仓库 " + r.name + " 里（它的 X " + oldLo.getX() + ".." + oldHi.getX()
                        + "、Z " + oldLo.getZ() + ".." + oldHi.getZ() + "），无法「缩到我所站的区块」。");
                return 0;
            }
            int cx = pp.getX() & ~15;
            int cz = pp.getZ() & ~15;
            int tx1 = Math.max(oldLo.getX(), cx);
            int tx2 = Math.min(oldHi.getX(), cx + 15);
            int tz1 = Math.max(oldLo.getZ(), cz);
            int tz2 = Math.min(oldHi.getZ(), cz + 15);
            // 交集一定合法（玩家在里面 ⇒ 非空），逐边收即可；先下界后上界，增量按旧范围算好
            String err = shrinkEdge(r, "west", tx1 - oldLo.getX());
            if (err == null) {
                err = shrinkEdge(r, "north", tz1 - oldLo.getZ());
            }
            if (err == null) {
                err = shrinkEdge(r, "east", oldHi.getX() - tx2);
            }
            if (err == null) {
                err = shrinkEdge(r, "south", oldHi.getZ() - tz2);
            }
            if (err != null) {
                // 理论上走不到：交集必然合法。真到了这里说明前置校验漏了，落盘并如实报告
                RegionStore.save();
                send(src, err);
                send(src, "（仓库范围可能已被改动一部分，请用 /warehouse region info " + r.name + " 核对）");
                return 0;
            }
        } else {
            String err = r.shrink(direction, amount);
            if (err != null) {
                send(src, err);
                return 0;
            }
        }

        BlockPos lo = r.min();
        BlockPos hi = r.max();
        RegionStore.save();

        List<Region> slabs = removedSlabs(r, oldLo, oldHi, lo, hi);
        int removed = slabs.isEmpty() ? 0 : WarehouseMod.INDEX.removeContainersIn(slabs);
        if (removed > 0) {
            WarehouseMod.INDEX.reaggregate();
        }

        long volumeAfter = r.blockVolume();
        send(src, "仓库 " + r.name + " 已缩小" + (chunkMode ? "（缩到你所站的区块）" : "") + "：");
        send(src, "  原来  " + before);
        send(src, "  现在  " + r.describeCorners()
                + "（" + volumeAfter + " 方块，减少 " + Math.max(0, volumeBefore - volumeAfter) + "）");
        if (volumeAfter == volumeBefore) {
            send(src, "  （范围没变：要缩到的位置本来就在现在的边界上）");
        } else if (removed > 0) {
            send(src, "  有 " + removed + " 个容器被移出索引（箱子与箱内物品都还在原地，只是不再属于本仓库）。");
        } else {
            send(src, "  没有容器落在被缩掉的范围里，索引无需清理。");
        }
        if (r.fullHeight && !chunkMode) {
            String d = direction.trim().toLowerCase(Locale.ROOT);
            if (d.equals("up") || d.equals("u") || d.equals("上")
                    || d.equals("down") || d.equals("d") || d.equals("下")) {
                send(src, "  （全高度仓库的 Y 只作为记录保留、不参与判定，上下缩不影响哪些箱子算在内）");
            }
        }
        Audit.add(adminName(src), "改仓库", "把仓库「" + r.name + "」缩小到 " + r.describeCorners()
                + (chunkMode ? "（缩到所站区块）" : ""));
        return 1;
    }

    /** 按方向缩一侧；{@code n <= 0} 表示这一侧不用动。返回 null 表示成功，否则是失败原因 */
    private static String shrinkEdge(Region r, String direction, int n) {
        return n <= 0 ? null : r.shrink(direction, n);
    }

    /**
     * 把「旧范围 − 新范围」拆成最多 6 片 1 格厚的薄板（闭区间）。
     *
     * <p>拆法：X 两片用旧的 Y/Z 全幅；Z 两片用<b>新</b>的 X、旧的 Y 全幅；Y 两片用新的 X/Z。
     * 三组并起来正好覆盖「本来在、现在不在」的部分，且 X/Z/Y 三组之间不重叠。
     *
     * <p>{@code fullHeight} 的仓库 Y 不参与判定，所以薄板要继承这一位、并**不生成** Y 薄板 ——
     * 否则会把新范围正上方/正下方那些「按 fullHeight 仍属于本仓库」的箱子误删。
     */
    private static List<Region> removedSlabs(Region owner, BlockPos oldLo, BlockPos oldHi, BlockPos lo, BlockPos hi) {
        List<Region> out = new ArrayList<>();
        // X：西 / 东（用旧的 Y、Z 全幅）
        addSlab(out, owner, oldLo.getX(), lo.getX() - 1, oldLo.getY(), oldHi.getY(), oldLo.getZ(), oldHi.getZ());
        addSlab(out, owner, hi.getX() + 1, oldHi.getX(), oldLo.getY(), oldHi.getY(), oldLo.getZ(), oldHi.getZ());
        // Z：北 / 南（用新的 X、旧的 Y 全幅，避开上面两片）
        addSlab(out, owner, lo.getX(), hi.getX(), oldLo.getY(), oldHi.getY(), oldLo.getZ(), lo.getZ() - 1);
        addSlab(out, owner, lo.getX(), hi.getX(), oldLo.getY(), oldHi.getY(), hi.getZ() + 1, oldHi.getZ());
        // Y：下 / 上（用新的 X、Z；fullHeight 时整组跳过）
        if (!owner.fullHeight) {
            addSlab(out, owner, lo.getX(), hi.getX(), oldLo.getY(), lo.getY() - 1, lo.getZ(), hi.getZ());
            addSlab(out, owner, lo.getX(), hi.getX(), hi.getY() + 1, oldHi.getY(), lo.getZ(), hi.getZ());
        }
        return out;
    }

    private static void addSlab(List<Region> out, Region owner,
                                int x1, int x2, int y1, int y2, int z1, int z2) {
        if (x1 > x2 || y1 > y2 || z1 > z2) {
            return;
        }
        Region slab = new Region("tmp-slab", Scanner.dimensionOf(owner),
                new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2));
        // 判定语义必须与所属仓库一致：fullHeight 的仓库只看 X/Z
        slab.fullHeight = owner.fullHeight;
        out.add(slab);
    }

    /** /warehouse region merge <名字> —— 把当前 pos1..pos2 圈出来的范围并进已有仓库 */
    private static int mergeRegion(CommandSourceStack src, String rawName) {
        Region r = lookupRegion(src, rawName);
        if (r == null) {
            return 0;
        }
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            send(src, "该用法须在游戏内执行（要用到你设的 pos1 / pos2）。");
            return 0;
        }
        BlockPos a = POS1.get(p.getUUID());
        BlockPos b = POS2.get(p.getUUID());
        if (a == null || b == null) {
            send(src, "尚未设点。请先在新扩建范围的两个对角执行 /warehouse pos1 与 /warehouse pos2");
            return 0;
        }
        if (!sameDimension(r, p)) {
            send(src, "你当前在" + Names.dimension(p.level().dimension().identifier().toString())
                    + "，仓库 " + r.name + " 在" + Names.dimension(Scanner.dimensionOf(r)) + "，无法合并。");
            return 0;
        }

        Region add = new Region("tmp", dim(p), a, b);
        String before = r.describeCorners();
        long volumeBefore = r.blockVolume();
        r.merge(add);
        RegionStore.save();

        long volumeAfter = r.blockVolume();
        send(src, "已将新范围 " + add.describeCorners() + " 并进仓库 " + r.name + "：");
        send(src, "  原来  " + before);
        send(src, "  现在  " + r.describeCorners()
                + "（" + volumeAfter + " 方块，增加 " + Math.max(0, volumeAfter - volumeBefore) + "）");
        warnIfHuge(src, r);
        send(src, "重新扫描后新容器才会进索引：/warehouse scan " + r.name);
        Audit.add(adminName(src), "改仓库", "把范围并进仓库「" + r.name + "」，现在是 " + r.describeCorners());
        return 1;
    }

    /** 仓库被扩得太大时给一句提醒（扫描的临时加载上限是 4096 个区块）。 */
    private static void warnIfHuge(CommandSourceStack src, Region r) {
        BlockPos lo = r.min();
        BlockPos hi = r.max();
        long chunks = (long) ((hi.getX() >> 4) - (lo.getX() >> 4) + 1)
                * (long) ((hi.getZ() >> 4) - (lo.getZ() >> 4) + 1);
        if (chunks > 4096) {
            send(src, "该仓库横跨 " + chunks + " 个区块，超过一次扫描的临时加载上限（4096）。");
            send(src, "  超出的区块只在已加载时被扫到，容器可能遗漏。建议缩小仓库或拆成多个小仓库。");
        }
    }

    private static Region lookupRegion(CommandSourceStack src, String rawName) {
        String name = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT);
        Region r = RegionStore.REGIONS.get(name);
        if (r == null) {
            send(src, noRegion(rawName));
        }
        return r;
    }

    private static boolean sameDimension(Region r, ServerPlayer p) {
        return Scanner.dimensionOf(r).equals(p.level().dimension().identifier().toString());
    }

    private static int startScan(CommandSourceStack src, String regionName) {
        List<Region> targets = new ArrayList<>();
        if (regionName == null) {
            targets.addAll(RegionStore.REGIONS.values());
        } else {
            Region r = RegionStore.REGIONS.get(regionName);
            if (r == null) {
                send(src, noRegion(regionName));
                return 0;
            }
            targets.add(r);
        }
        // 指定了区域名就走「局部重扫」：只刷新这一个区域，别的仓库的索引记录必须留着。
        // 不带名字是全量重建（清空重扫），这正是「第一次扫描」想要的。
        String err = regionName == null
                ? Scanner.start(src.getServer(), targets)
                : Scanner.startPartial(src.getServer(), targets);
        if (err != null) {
            send(src, err);
            return 0;
        }
        long volume = 0;
        for (Region r : targets) {
            volume += r.blockVolume();
        }
        send(src, "开始扫描 " + targets.size() + " 个仓库（共 " + volume + " 方块），进度用 /warehouse status 查看。");
        if (AppConfig.get().scanForceLoadChunks) {
            send(src, "未加载区块会被临时加载，扫完即释放。");
        } else {
            send(src, "临时加载区块为关闭状态，未加载区块扫不到：可用 /warehouse settings forcechunks on 开启。");
        }
        WebSnapshot.markDirty();
        Audit.add(adminName(src), "刷新索引", regionName == null
                ? "扫描全部 " + targets.size() + " 个仓库"
                : "扫描仓库「" + regionName + "」");
        return 1;
    }

    private static void showList(CommandSourceStack src, int page) {
        List<WarehouseIndex.ItemEntry> all = WarehouseMod.INDEX.sortedItems();
        if (all.isEmpty()) {
            send(src, noIndex());
            return;
        }
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.min(Math.max(1, page), pages);
        send(src, "物品总览 " + p + "/" + pages + " 页（" + all.size() + " 种 / "
                + WarehouseMod.INDEX.totalItems + " 个）");
        int from = (p - 1) * PAGE_SIZE;
        int to = Math.min(all.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            WarehouseIndex.ItemEntry e = all.get(i);
            send(src, "  " + (i + 1) + ". " + e.displayName + "  x" + e.total
                    + "  [" + e.stacksText() + "]  " + catName(Categories.of(e.itemId))
                    + "  (" + e.refs.size() + " 处)  " + e.itemId);
        }
        if (p < pages) {
            send(src, "下一页: /warehouse list " + (p + 1));
        }
    }

    private static void find(CommandSourceStack src, String query) {
        WarehouseIndex idx = WarehouseMod.INDEX;
        if (idx.items.isEmpty()) {
            send(src, noIndex());
            return;
        }
        String id = ItemIds.resolve(query);
        if (id == null) {
            String q = query.trim().toLowerCase(Locale.ROOT);
            List<WarehouseIndex.ItemEntry> hits = new ArrayList<>();
            for (WarehouseIndex.ItemEntry e : idx.items.values()) {
                // displayName 是建索引时算好的：专用服务端的 Language 多半是 en_us，那时还没有客户端推来的
                // 名字表，缓存里就是英文名 —— 中文搜索必须用 Names.item() 实时再取一次。
                if (e.itemId.contains(q)
                        || e.displayName.toLowerCase(Locale.ROOT).contains(q)
                        || Names.item(e.itemId).toLowerCase(Locale.ROOT).contains(q)) {
                    hits.add(e);
                }
            }
            if (hits.isEmpty()) {
                send(src, "找不到与「" + query + "」匹配的物品");
                return;
            }
            hits.sort(Comparator.comparingLong((WarehouseIndex.ItemEntry e) -> e.total).reversed());
            if (hits.size() > 1) {
                send(src, "匹配到 " + hits.size() + " 种，显示前 10 种：");
                for (int i = 0; i < Math.min(10, hits.size()); i++) {
                    WarehouseIndex.ItemEntry e = hits.get(i);
                    send(src, "  " + e.displayName + "  x" + e.total + "  " + e.itemId);
                }
                send(src, "用完整 id 可查位置，例如 /warehouse find " + hits.get(0).itemId);
                return;
            }
            id = hits.get(0).itemId;
        }
        WarehouseIndex.ItemEntry e = idx.items.get(id);
        if (e == null) {
            send(src, "仓库中没有 " + id);
            return;
        }
        send(src, e.displayName + "  " + id);
        send(src, "总量 " + e.total + "  [" + e.stacksText() + "]  分布 " + e.refs.size() + " 槽位:");
        int shown = 0;
        for (WarehouseIndex.SlotRef ref : e.refs) {
            if (shown++ >= 20) {
                send(src, "  ...（还有 " + (e.refs.size() - 20) + " 处）");
                break;
            }
            send(src, "  " + ref.coordText() + "  槽位 " + ref.slot() + "  x" + ref.count()
                    + "  " + Names.block(ref.blockId()));
        }
    }

    // ------------------------------------------------------------------
    // 优化11：按附魔 / 自定义名搜索（索引新增的维度，需 0.22.0 重新扫描才有数据）

    /**
     * 常用中文附魔名 → 注册名。
     *
     * <p>只做「让人打得出来」这一件事：注册名永远是权威，认不出的输入直接按注册名走。
     * 专用服务端的语言多半是 en_us，服务端拿不到中文显示名，所以这张表是必要的。
     */
    private static final Map<String, String> ENCHANT_ALIASES = Map.ofEntries(
            Map.entry("保护", "minecraft:protection"),
            Map.entry("火焰保护", "minecraft:fire_protection"),
            Map.entry("摔落保护", "minecraft:feather_falling"),
            Map.entry("摔落缓冲", "minecraft:feather_falling"),
            Map.entry("爆炸保护", "minecraft:blast_protection"),
            Map.entry("弹射物保护", "minecraft:projectile_protection"),
            Map.entry("水下呼吸", "minecraft:respiration"),
            Map.entry("水下速掘", "minecraft:aqua_affinity"),
            Map.entry("荆棘", "minecraft:thorns"),
            Map.entry("深海探索者", "minecraft:depth_strider"),
            Map.entry("冰霜行者", "minecraft:frost_walker"),
            Map.entry("绑定诅咒", "minecraft:binding_curse"),
            Map.entry("灵魂疾行", "minecraft:soul_speed"),
            Map.entry("迅捷潜行", "minecraft:swift_sneak"),
            Map.entry("锋利", "minecraft:sharpness"),
            Map.entry("亡灵杀手", "minecraft:smite"),
            Map.entry("节肢杀手", "minecraft:bane_of_arthropods"),
            Map.entry("击退", "minecraft:knockback"),
            Map.entry("火焰附加", "minecraft:fire_aspect"),
            Map.entry("抢夺", "minecraft:looting"),
            Map.entry("横扫之刃", "minecraft:sweeping_edge"),
            Map.entry("效率", "minecraft:efficiency"),
            Map.entry("精准采集", "minecraft:silk_touch"),
            Map.entry("耐久", "minecraft:unbreaking"),
            Map.entry("时运", "minecraft:fortune"),
            Map.entry("力量", "minecraft:power"),
            Map.entry("冲击", "minecraft:punch"),
            Map.entry("火矢", "minecraft:flame"),
            Map.entry("无限", "minecraft:infinity"),
            Map.entry("海之眷顾", "minecraft:luck_of_the_sea"),
            Map.entry("饵钓", "minecraft:lure"),
            Map.entry("忠诚", "minecraft:loyalty"),
            Map.entry("穿刺", "minecraft:impaling"),
            Map.entry("激流", "minecraft:riptide"),
            Map.entry("引雷", "minecraft:channeling"),
            Map.entry("多重射击", "minecraft:multishot"),
            Map.entry("快速装填", "minecraft:quick_charge"),
            Map.entry("穿透", "minecraft:piercing"),
            Map.entry("经验修补", "minecraft:mending"),
            Map.entry("消失诅咒", "minecraft:vanishing_curse"),
            Map.entry("密度", "minecraft:density"),
            Map.entry("致密", "minecraft:density"),
            Map.entry("破甲", "minecraft:breach"),
            Map.entry("突进", "minecraft:lunge"),
            Map.entry("风爆", "minecraft:wind_burst"));

    /** {@code /warehouse enchants [list]} —— 索引里出现过哪些附魔 */
    private static void enchants(CommandSourceStack src) {
        WarehouseIndex idx = WarehouseMod.INDEX;
        if (idx.items.isEmpty()) {
            send(src, noIndex());
            return;
        }
        Set<String> ids = idx.enchantIds();
        if (ids.isEmpty()) {
            send(src, "索引里还没有附魔信息（0.21.0 及更早保存的索引不含这一维度，或者还没扫到带附魔的箱子）。");
            send(src, "重新执行 /warehouse scan <仓库名> 之后就能按附魔搜索。");
            return;
        }
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.comparingInt((String id) -> idx.enchantHits(id).size()).reversed()
                .thenComparing(id -> id));
        send(src, "===== 索引里的附魔（" + sorted.size() + " 种）=====");
        for (String id : sorted) {
            send(src, "  " + id + cnNameOf(id) + "  " + idx.enchantHits(id).size() + " 个容器");
        }
        send(src, "用法：/warehouse enchants find <附魔 id 或中文名>（也可以直接按箱子的自定义名找）");
    }

    /** {@code /warehouse enchants find <附魔或自定义名>} */
    private static void findEnchanted(CommandSourceStack src, String query) {
        WarehouseIndex idx = WarehouseMod.INDEX;
        if (idx.items.isEmpty()) {
            send(src, noIndex());
            return;
        }
        String raw = query == null ? "" : query.trim();
        if (raw.isEmpty()) {
            send(src, "用法：/warehouse enchants find <附魔 id 或中文名>");
            return;
        }
        List<String> ids = enchantCandidates(idx, raw);
        if (ids.size() > 1) {
            send(src, "「" + raw + "」匹配到 " + ids.size() + " 种附魔，请用完整 id：");
            for (String id : ids) {
                send(src, "  " + id + cnNameOf(id) + "  " + idx.enchantHits(id).size() + " 个容器");
            }
            return;
        }
        if (ids.isEmpty()) {
            if (findNamed(src, idx, raw)) {
                return;
            }
            send(src, "没认出附魔「" + raw + "」。可以直接用注册名（例如 minecraft:sharpness），"
                    + "或用 /warehouse enchants list 看看索引里有哪些。");
            return;
        }
        String id = ids.get(0);
        Map<String, Integer> hits = idx.enchantHits(id);
        if (hits.isEmpty()) {
            send(src, "索引里没有 " + id + cnNameOf(id) + " 的记录。");
            if (idx.enchantIds().isEmpty()) {
                send(src, "当前索引不含附魔信息（旧索引），重新 /warehouse scan <仓库名> 后再试。");
            }
            return;
        }
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(hits.entrySet());
        rows.sort(Comparator.comparingInt((Map.Entry<String, Integer> en) -> en.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));
        send(src, "===== 附魔搜索：" + id + cnNameOf(id) + " =====");
        send(src, "带这件附魔的容器 " + rows.size() + " 个：");
        int shown = 0;
        for (Map.Entry<String, Integer> en : rows) {
            if (shown++ >= 30) {
                send(src, "  ...（还有 " + (rows.size() - 30) + " 个）");
                break;
            }
            ContainerRecord rec = idx.containers.get(en.getKey());
            if (rec == null) {
                continue;
            }
            send(src, "  " + Names.dimension(rec.dimension) + "  " + rec.coordText()
                    + "  " + Names.block(rec.blockId) + "  等级 " + en.getValue()
                    + "  仓库 " + regionsAt(rec));
        }
    }

    /** 按箱子上的自定义名找（附魔搜索的姊妹功能，同一套索引） */
    private static boolean findNamed(CommandSourceStack src, WarehouseIndex idx, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        Map<String, Set<String>> matched = new LinkedHashMap<>();
        for (String name : idx.customNames()) {
            if (name == null || !name.contains(q)) {
                continue;
            }
            Set<String> keys = idx.customNameHits(name);
            if (!keys.isEmpty()) {
                matched.put(name, keys);
            }
        }
        if (matched.isEmpty()) {
            return false;
        }
        send(src, "===== 自定义名搜索：" + query + " =====");
        for (Map.Entry<String, Set<String>> en : matched.entrySet()) {
            send(src, "「" + en.getKey() + "」" + en.getValue().size() + " 个容器：");
            int shown = 0;
            for (String key : en.getValue()) {
                if (shown++ >= 10) {
                    send(src, "  ...（还有 " + (en.getValue().size() - 10) + " 个）");
                    break;
                }
                ContainerRecord rec = idx.containers.get(key);
                if (rec == null) {
                    continue;
                }
                send(src, "  " + Names.dimension(rec.dimension) + "  " + rec.coordText()
                        + "  " + Names.block(rec.blockId) + "  仓库 " + regionsAt(rec));
            }
        }
        return true;
    }

    /**
     * 把用户输入折算成候选附魔 id。
     *
     * <p>顺序：中文别名 → 带命名空间的原样 → 补 minecraft: 的精确匹配 → 按「路径」模糊匹配。
     * 返回空表表示「不是附魔」，多于一条表示有歧义（调用方负责让用户挑）。
     */
    private static List<String> enchantCandidates(WarehouseIndex idx, String input) {
        String trimmed = input.trim();
        String alias = ENCHANT_ALIASES.get(trimmed);
        if (alias != null) {
            return List.of(alias);
        }
        Set<String> known = idx.enchantIds();
        String q = trimmed.toLowerCase(Locale.ROOT);
        if (q.indexOf(':') >= 0) {
            return List.of(q);
        }
        String namespaced = "minecraft:" + q;
        if (known.contains(namespaced)) {
            return List.of(namespaced);
        }
        if (known.contains(q)) {
            return List.of(q);
        }
        List<String> fuzzy = new ArrayList<>();
        for (String id : known) {
            int sep = id.indexOf(':');
            String path = sep < 0 ? id : id.substring(sep + 1);
            // 先精确、再后缀、最后子串；同一条只加一次
            if (path.equals(q) || path.endsWith("_" + q) || path.contains(q)) {
                fuzzy.add(id);
            }
        }
        fuzzy.sort(Comparator.comparingInt((String id) -> {
            int sep = id.indexOf(':');
            String path = sep < 0 ? id : id.substring(sep + 1);
            if (path.equals(q)) {
                return 0;
            }
            return path.endsWith("_" + q) ? 1 : 2;
        }).thenComparing(id -> id));
        return fuzzy;
    }

    /** 附魔 id 对应的中文别名（「（锋利）」），没登记就返回空串 */
    private static String cnNameOf(String enchantId) {
        for (Map.Entry<String, String> en : ENCHANT_ALIASES.entrySet()) {
            if (en.getValue().equals(enchantId)) {
                return "（" + en.getKey() + "）";
            }
        }
        return "";
    }

    /** 这个容器落在哪些仓库里（一个容器可能同时被多个仓库圈住） */
    private static String regionsAt(ContainerRecord rec) {
        StringBuilder sb = new StringBuilder();
        for (Region r : RegionStore.REGIONS.values()) {
            if (rec.dimension == null || !rec.dimension.equals(r.dimension) || !r.contains(rec.pos)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(r.name);
        }
        return sb.length() == 0 ? "（不属于任何仓库）" : sb.toString();
    }

    private static void showStats(CommandSourceStack src) {
        WarehouseIndex idx = WarehouseMod.INDEX;
        if (idx.items.isEmpty()) {
            send(src, noIndex());
            return;
        }
        Map<String, long[]> byCat = new LinkedHashMap<>();
        Map<String, Integer> kinds = new LinkedHashMap<>();
        // 统计顺序 = Categories.order()（创造栏页签顺序 + 「其他」），不再是写死的 10 个类目
        for (String c : Categories.order()) {
            byCat.put(c, new long[]{0, 0});
            kinds.put(c, 0);
        }
        for (WarehouseIndex.ItemEntry e : idx.items.values()) {
            String c = Categories.of(e.itemId);
            long[] arr = byCat.computeIfAbsent(c, k -> new long[]{0, 0});
            arr[0] += e.total;
            arr[1] += e.refs.size();
            kinds.merge(c, 1, Integer::sum);
        }
        send(src, "===== 仓库分类统计 =====");
        send(src, "容器 " + idx.scannedContainers + "  物品种类 " + idx.items.size()
                + "  物品总数 " + idx.totalItems + "  占用槽位 " + idx.totalStacks);
        for (Map.Entry<String, long[]> en : byCat.entrySet()) {
            long[] v = en.getValue();
            if (v[0] == 0) {
                continue;
            }
            send(src, "  " + catName(en.getKey()) + ": " + kinds.getOrDefault(en.getKey(), 0) + " 种 / "
                    + v[0] + " 个 / " + v[1] + " 槽位");
        }
    }

    // ------------------------------------------------------------------
    // 分类 v2（D1）：查看/审计命令

    private static final String OTHER = CategoryRules.OTHER;

    /** 各类别统计 + 判定层分布 + 覆盖表状态 */
    private static void showCategories(CommandSourceStack src) {
        WarehouseIndex idx = WarehouseMod.INDEX;
        send(src, "===== 物品分类 v2 =====");
        send(src, "词元 " + CategoryRules.tokenCount() + " 条，钉死 " + CategoryRules.exactCount()
                + " 条，后缀 " + CategoryRules.suffixCount() + " 条，重复登记 "
                + CategoryRules.duplicateCount() + " 条（应为 0）");
        if (CategoryRules.duplicateCount() > 0) {
            send(src, "重复登记的词元（后写覆盖先写，要修）：" + String.join("、", CategoryRules.duplicates()));
        }
        send(src, "覆盖表：" + Categories.loadNote());
        send(src, "记忆化 " + Categories.memoSize() + " 条（随索引落盘）");
        if (idx.items.isEmpty()) {
            send(src, noIndex());
            return;
        }
        Map<String, long[]> byCat = new LinkedHashMap<>();
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (String c : Categories.order()) {
            byCat.put(c, new long[]{0, 0});
            kinds.put(c, 0);
        }
        Map<String, Integer> layers = new LinkedHashMap<>();
        int unclassified = 0;
        for (WarehouseIndex.ItemEntry e : idx.items.values()) {
            // 统计口径 = Categories.of（与 /warehouse status、网页面板、tag 同一套，未分类数才对得上）；
            // decide() 只负责「这一条是靠哪一层判出来的」这半张诊断信息
            String c = Categories.of(e.itemId);
            long[] arr = byCat.computeIfAbsent(c, k -> new long[]{0, 0});
            arr[0] += e.total;
            arr[1] += e.refs.size();
            kinds.merge(c, 1, Integer::sum);
            if (OTHER.equals(c)) {
                unclassified++;
            }
            layers.merge(Categories.decide(e.itemId).layer(), 1, Integer::sum);
        }
        for (Map.Entry<String, long[]> en : byCat.entrySet()) {
            long[] v = en.getValue();
            if (v[0] == 0) {
                continue;
            }
            send(src, "  " + catName(en.getKey()) + ": " + kinds.getOrDefault(en.getKey(), 0) + " 种 / "
                    + v[0] + " 个 / " + v[1] + " 槽位");
        }
        send(src, "判定层：");
        for (String l : List.of("L0 覆盖表", "L1 原版标签", "L2 通用标签", "L3 词元规则", "L4 兜底")) {
            int n = layers.getOrDefault(l, 0);
            if (n > 0) {
                send(src, "  " + l + "：" + n + " 种");
            }
        }
        if (unclassified > 0) {
            send(src, "未分类 " + unclassified + " 种：用 /warehouse categories unclassified 看清单，或在 categories.json 加覆盖");
        }
    }

    /** 「其他」清单（分页）：这些就是覆盖表的候选 */
    private static void showUnclassified(CommandSourceStack src, int page) {
        List<WarehouseIndex.ItemEntry> list = new ArrayList<>();
        for (WarehouseIndex.ItemEntry e : WarehouseMod.INDEX.items.values()) {
            if (OTHER.equals(Categories.of(e.itemId))) {
                list.add(e);
            }
        }
        if (list.isEmpty()) {
            send(src, "没有「其他」分类的物品。");
            return;
        }
        list.sort(Comparator.comparingLong((WarehouseIndex.ItemEntry e) -> e.total).reversed()
                .thenComparing(e -> e.itemId));
        int pages = (list.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page < 1 || page > pages) {
            send(src, "页码超出范围（1~" + pages + "）");
            return;
        }
        send(src, "===== 未分类物品（" + list.size() + " 种，第 " + page + "/" + pages + " 页）=====");
        int from = (page - 1) * PAGE_SIZE;
        for (int i = from; i < Math.min(from + PAGE_SIZE, list.size()); i++) {
            WarehouseIndex.ItemEntry e = list.get(i);
            send(src, "  " + e.itemId + "  x" + e.total + " / " + e.refs.size() + " 槽位");
        }
    }

    /**
     * BUG7：索引里有未分类物品时顺手说一句，并指向已有的清单命令。
     *
     * <p>口径**完全沿用** {@link #showUnclassified}：{@code OTHER.equals(Categories.of(itemId))}，
     * 不另造统计口径，两处数字永远对得上。
     */
    private static void showUnclassifiedHint(CommandSourceStack src) {
        int n = 0;
        for (WarehouseIndex.ItemEntry e : WarehouseMod.INDEX.items.values()) {
            if (OTHER.equals(Categories.of(e.itemId))) {
                n++;
            }
        }
        if (n > 0) {
            send(src, "未分类 " + n + " 种，可用 /warehouse categories unclassified 查看");
        }
    }

    /**
     * 单个物品的判定明细。整条只输出一行，格式固定，方便 RCON 审计脚本用正则解析：
     * {@code <id> → <分类> [<层> <证据>] reg=0|1}（reg=1 表示这个 id 在当前物品注册表里）
     */
    private static void showOneCategory(CommandSourceStack src, String input) {
        String resolved = ItemIds.resolve(input);
        boolean registered = resolved != null;
        String id = registered ? resolved : input;
        Categories.Decision d = Categories.decide(id);
        // 展示用「这个 id 实际归到哪个类目」= Categories.of（与 list / 面板 / tag 一致）的中文名；
        // [层 证据] 保留旧判定链的诊断信息，方便查它为什么落在这里
        send(src, d.key() + " → " + catName(Categories.of(id)) + " [" + d.layer() + " " + d.detail() + "] reg="
                + (registered ? 1 : 0));
    }

    /** 全量导出（分页）：id|创造栏页签键|判定层 —— 给审计脚本比对黄金表用 */
    private static void dumpCategories(CommandSourceStack src, int page) {
        List<WarehouseIndex.ItemEntry> list = new ArrayList<>(WarehouseMod.INDEX.items.values());
        if (list.isEmpty()) {
            send(src, noIndex());
            return;
        }
        list.sort(Comparator.comparing(e -> e.itemId));
        int pages = (list.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page < 1 || page > pages) {
            send(src, "页码超出范围（1~" + pages + "）");
            return;
        }
        send(src, "===== 分类导出（" + list.size() + " 种，第 " + page + "/" + pages + " 页）=====");
        int from = (page - 1) * PAGE_SIZE;
        for (int i = from; i < Math.min(from + PAGE_SIZE, list.size()); i++) {
            String id = list.get(i).itemId;
            send(src, id + "|" + Categories.of(id) + "|" + Categories.decide(id).layer());
        }
    }

    /** 全部已注册物品的分类导出（分页）：id|创造栏页签键|判定层 —— 全量审计用（不受索引影响） */
    private static void dumpAllCategories(CommandSourceStack src, int page) {
        List<String> ids = new ArrayList<>();
        for (Identifier id : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
            ids.add(id.toString());
        }
        if (ids.isEmpty()) {
            send(src, "物品注册表为空。");
            return;
        }
        ids.sort(Comparator.naturalOrder());
        int size = 24;
        int pages = (ids.size() + size - 1) / size;
        if (page < 1 || page > pages) {
            send(src, "页码超出范围（1~" + pages + "）");
            return;
        }
        send(src, "===== 注册表全量分类（" + ids.size() + " 种，第 " + page + "/" + pages + " 页）=====");
        int from = (page - 1) * size;
        for (int i = from; i < Math.min(from + size, ids.size()); i++) {
            String id = ids.get(i);
            send(src, id + "|" + Categories.of(id) + "|" + Categories.decide(id).layer());
        }
    }

    // ==================================================================
    // 箱子标签（设计规格见分类与整理方案的附录 J）
    // 标签决定「哪只箱子收哪一类东西」「哪只箱子只出不进」，贴标签本身不动任何物品。
    // ==================================================================

    /**
     * 分类的 ASCII 别名。Brigadier 的单词参数只吃 ASCII，中文名要加引号；
     * 客户端与英文输入法用别名最省事（也便于命令补全）。
     */
    private static final Map<String, String> CATEGORY_ALIAS = Map.ofEntries(
            Map.entry("mineral", CategoryRules.MINERAL), Map.entry("build", CategoryRules.BUILD),
            Map.entry("wood", CategoryRules.WOOD), Map.entry("tool", CategoryRules.TOOL),
            Map.entry("food", CategoryRules.FOOD), Map.entry("farm", CategoryRules.FARM),
            Map.entry("redstone", CategoryRules.REDSTONE), Map.entry("magic", CategoryRules.MAGIC),
            Map.entry("container", CategoryRules.CONTAINER), Map.entry("other", CategoryRules.OTHER));

    /** 分类名补全：创造栏页签键（新，如 building_blocks）+ 旧中文父类名 + ASCII 别名 */
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> CATEGORY_SUGGEST =
            (ctx, builder) -> SharedSuggestionProvider.suggest(categoryChoices(), builder);

    /** 补全候选：{@code Categories.order()} 打头，再补旧类目名与 ASCII 别名，去重保序 */
    private static List<String> categoryChoices() {
        LinkedHashSet<String> out = new LinkedHashSet<>(Categories.order());
        out.addAll(CategoryRules.LEGACY);
        out.addAll(CATEGORY_ALIAS.keySet());
        return List.copyOf(out);
    }

    /**
     * 给玩家看的「可选分类」文本。
     *
     * <p>只列**能直接打出来**的名字：旧中文父类名（建材方块…其他）与 ASCII 别名。
     * 新类目是创造栏页签键（{@code minecraft:building_blocks} / {@code building_blocks}），
     * 但它们的**中文显示名 {@code Categories.resolve} 并不接受**，混在一起列会让玩家照着打出认不出的名字。
     */
    private static String categoryListText() {
        LinkedHashSet<String> out = new LinkedHashSet<>(CategoryRules.LEGACY);
        out.addAll(CATEGORY_ALIAS.keySet());
        return String.join(" / ", out);
    }

    /** 类目键 → 给人看的中文名；认不出就把键原样还回去（{@code Categories.displayName} 不会返回 null） */
    private static String catName(String key) {
        if (key == null || key.isEmpty()) {
            return "其他";
        }
        return Categories.displayName(key);
    }

    /**
     * 把玩家输入（创造栏页签键 / 旧中文类目名 / ASCII 别名）翻成正式类目键，认不出返回 null。
     *
     * <p>顺序要紧：先过 {@link #CATEGORY_ALIAS}，因为 {@code Categories.resolve("other")} 是**不认**的
     * —— {@code "other"} 既不是页签键也不是旧中文名，只有别名表能把它翻成
     * {@code CategoryRules.OTHER}（{@code warehouse-keeper:other}）。
     */
    private static String resolveCategory(String raw) {
        if (raw == null) {
            return null;
        }
        String byAlias = CATEGORY_ALIAS.get(raw.trim().toLowerCase(Locale.ROOT));
        String target = byAlias != null ? byAlias : raw;
        String resolved = Categories.resolve(target);
        if (resolved != null) {
            return resolved;
        }
        // 兜底：ContainerTags 自己的归一化（旧中文名，大小写不敏感）
        return ContainerTags.normalizeCategory(target);
    }

    /** 谁能下单取货 / 入库：OP，或在权限表里 take 权限为开的玩家 */
    private static boolean canTake(CommandSourceStack src) {
        if (admin(src)) {
            return true;
        }
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            return false;
        }
        return PlayerPerms.perm(p.getGameProfile().name()) != null
                && PlayerPerms.perm(p.getGameProfile().name()).take();
    }

    /** 谁能贴/改/清标签：OP，或有 tidy 权限的玩家 */
    private static boolean canTag(CommandSourceStack src) {
        if (admin(src)) {
            return true;
        }
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            return false;
        }
        return PlayerPerms.perm(p.getGameProfile().name()) != null
                && PlayerPerms.perm(p.getGameProfile().name()).tidy();
    }

    /** 定位到的箱子 */
    private record Target(ServerLevel level, BlockPos pos, String key) {
    }

    /** {@code /warehouse tag …} —— 箱子标签 */
    private static LiteralArgumentBuilder<CommandSourceStack> tagNode() {
        return Commands.literal("tag")
                .executes(ctx -> tagShow(ctx.getSource(), null))
                .then(Commands.literal("show")
                        .executes(ctx -> tagShow(ctx.getSource(), null))
                        .then(coordsNode(ctx -> tagShow(ctx.getSource(), posOf(ctx)))))
                .then(Commands.literal("list")
                        .executes(ctx -> tagList(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> tagList(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("set").requires(WarehouseCommand::canTag)
                        .executes(ctx -> {
                            send(ctx.getSource(), "用法：/warehouse tag set <分类> [x y z]，可选分类：" + categoryListText()
                                    + "；也可直接给创造栏页签键（building_blocks / ingredients 之类）");
                            return 0;
                        })
                        .then(Commands.argument("category", StringArgumentType.string())
                                .suggests(CATEGORY_SUGGEST)
                                .executes(ctx -> tagSet(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "category"), null))
                                .then(coordsNode(ctx -> tagSet(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "category"), posOf(ctx))))))
                .then(Commands.literal("auto").requires(WarehouseCommand::canTag)
                        .executes(ctx -> tagAuto(ctx.getSource(), null))
                        .then(coordsNode(ctx -> tagAuto(ctx.getSource(), posOf(ctx)))))
                .then(Commands.literal("staging").requires(WarehouseCommand::canTag)
                        .executes(ctx -> tagStaging(ctx.getSource(), null))
                        .then(coordsNode(ctx -> tagStaging(ctx.getSource(), posOf(ctx)))))
                .then(Commands.literal("clear").requires(WarehouseCommand::canTag)
                        .executes(ctx -> tagClear(ctx.getSource(), null))
                        .then(coordsNode(ctx -> tagClear(ctx.getSource(), posOf(ctx)))))
                .then(Commands.literal("mode").requires(WarehouseCommand::admin)
                        .executes(ctx -> {
                            send(ctx.getSource(), "用法：/warehouse tag mode manual|auto [x y z]（仅管理员可改档位）");
                            return 0;
                        })
                        .then(Commands.literal("manual")
                                .executes(ctx -> tagMode(ctx.getSource(), ContainerTags.MANUAL, null))
                                .then(coordsNode(ctx -> tagMode(ctx.getSource(), ContainerTags.MANUAL, posOf(ctx)))))
                        .then(Commands.literal("auto")
                                .executes(ctx -> tagMode(ctx.getSource(), ContainerTags.AUTO, null))
                                .then(coordsNode(ctx -> tagMode(ctx.getSource(), ContainerTags.AUTO, posOf(ctx))))))
                .then(Commands.literal("prune").requires(WarehouseCommand::admin)
                        .executes(ctx -> {
                            int[] r = ContainerTags.repair(ctx.getSource().getServer());
                            ContainerTags.save();
                            if (r[0] == 0 && r[1] == 0) {
                                send(ctx.getSource(), "没有要修的箱子标签。");
                            } else {
                                send(ctx.getSource(), "标签整理完毕：删掉 " + r[1] + " 条（箱子不在 / 历史残留），"
                                        + "归一化坐标 " + r[0] + " 条。");
                            }
                            return 1;
                        }));
    }

    /** 「[x y z]」可选坐标节点（26.2 下项目里没用过 BlockPosArgument，这里手拼三个整数） */
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> coordsNode(Command<CommandSourceStack> action) {
        return Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(action)));
    }

    private static BlockPos posOf(CommandContext<CommandSourceStack> ctx) {
        return new BlockPos(IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    /**
     * 定位要操作的箱子：不给坐标就取玩家视线所指（射线 6 格，和原版伸手够得着的距离一致）。
     * 失败时已经提示过原因，返回 null。
     */
    private static Target tagTarget(CommandSourceStack src, BlockPos explicit) {
        ServerPlayer p = src.getPlayer();
        ServerLevel level = p != null ? (ServerLevel) p.level() : src.getServer().overworld();
        BlockPos pos = explicit;
        if (pos == null) {
            if (p == null) {
                send(src, "控制台没法看箱子，请给出坐标：/warehouse tag show <x> <y> <z>");
                return null;
            }
            net.minecraft.world.phys.Vec3 eye = p.getEyePosition();
            net.minecraft.world.phys.Vec3 end = eye.add(p.getViewVector(1.0F).scale(6.0));
            BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, end,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, p));
            if (hit.getType() != HitResult.Type.BLOCK) {
                send(src, "没有正对着方块：请站近箱子、准星对准它，或显式给出坐标。");
                return null;
            }
            pos = hit.getBlockPos();
        }
        BlockPos canon = Scanner.canonical(level, pos);
        BlockEntity be = level.getBlockEntity(canon);
        if (!(be instanceof Container)) {
            send(src, "坐标 " + fmt(canon) + " 上不是容器（箱子/木桶/潜影盒…）。");
            return null;
        }
        // 是容器、但被排除表挡掉（雕纹书架/架子…）：它不参与仓库管理，
        // 说清楚是被配置排除的，而不是含糊一句「不是容器」让玩家去猜。
        if (!Containers.isWarehouseContainer(level, canon, be)) {
            String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(canon).getBlock()).toString();
            send(src, "该方块不参与仓库管理：" + blockId
                    + (Containers.excluded(blockId)
                            ? " 已在配置 containerExclude（容器排除表）中排除。"
                            : " 不算仓库容器。"));
            return null;
        }
        if (level.getBlockState(canon).getBlock() == Blocks.ENDER_CHEST) {
            send(src, "末影箱跟着人走，不参与仓库。");
            return null;
        }
        return new Target(level, canon, ContainerTags.keyFor(level, canon));
    }

    private static String coordOf(Target t) {
        return t.level().dimension().identifier().toString() + " " + fmt(t.pos());
    }

    private static String or(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }

    /** 箱子在不在任何仓库内（区外允许贴标签，但整理不碰） */
    private static boolean inAnyRegion(ServerLevel level, BlockPos pos) {
        String dim = level.dimension().identifier().toString();
        for (Region r : RegionStore.REGIONS.values()) {
            if (Scanner.dimensionOf(r).equals(dim) && r.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** 区外提示（附一次就够，不重复刷屏） */
    private static void regionHint(CommandSourceStack src, Target t) {
        if (!inAnyRegion(t.level(), t.pos())) {
            send(src, "  提示：这只箱子不在任何仓库内，不参与整理。");
        }
    }

    /** 现读一只容器（双联箱算一个）造记录，给「按内容自动」用 */
    private static ContainerRecord liveRecord(ServerLevel level, BlockPos pos) {
        Scanner.SlotMap map = Scanner.mapOf(level, pos);
        if (map == null) {
            return null;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        ContainerRecord rec = new ContainerRecord(level.dimension().identifier().toString(), pos, blockId, map.size());
        for (int i = 0; i < map.size(); i++) {
            Scanner.Slot s = map.at(i);
            if (s == null) {
                continue;
            }
            ItemStack st = s.container().getItem(s.index());
            if (!st.isEmpty()) {
                rec.add(i, st.copy());
            }
        }
        return rec;
    }

    private static int tagShow(CommandSourceStack src, BlockPos explicit) {
        Target t = tagTarget(src, explicit);
        if (t == null) {
            return 0;
        }
        ContainerTags.Tag tag = ContainerTags.get(t.key());
        if (tag == null) {
            send(src, coordOf(t) + " 未打标签（整理时按启发式猜去处）。");
        } else {
            send(src, coordOf(t) + " 标签：" + tag.label()
                    + "  档位：" + (tag.isAutoMode() ? "自动" : "手动"));
            send(src, "  自动分类：" + or(tag.autoCategory, "（没算过）")
                    + "  手动分类：" + or(tag.manualCategory, "（没选过）")
                    + "  最后设置：" + or(tag.setBy, "?"));
        }
        regionHint(src, t);
        return 1;
    }

    private static int tagSet(CommandSourceStack src, String rawCategory, BlockPos explicit) {
        Target t = tagTarget(src, explicit);
        if (t == null) {
            return 0;
        }
        String cat = resolveCategory(rawCategory);
        if (cat == null) {
            send(src, "分类「" + rawCategory + "」不认识。可选：" + categoryListText()
                    + "（也可用别名 mineral/build/wood/tool/food/farm/redstone/magic/container/other，"
                    + "或创造栏页签键 building_blocks / ingredients 之类）");
            return 0;
        }
        ContainerTags.Tag tag = ContainerTags.get(t.key());
        if (tag != null && tag.isAutoMode()) {
            send(src, "本箱已锁定自动标签：先 /warehouse tag mode manual 拨回手动档，再选分类。");
            return 0;
        }
        ContainerTags.setManual(t.key(), cat, adminName(src));
        ContainerTags.save();
        Audit.add(adminName(src), "改标签", "把 " + coordOf(t) + " 设为「" + cat + "」(手动档)");
        send(src, coordOf(t) + " 已贴标签：" + cat + "（手动档，它就是这一类东西的目标箱）");
        tagNoTidyHint(src, t);
        regionHint(src, t);
        return 1;
    }

    /**
     * 0.23.0 · 优化5：贴标签的这只箱子要是「可查看、但不整理」的容器（书架 / 熔炉 / 漏斗……），
     * 把话说清楚 —— 否则玩家看到「贴了标签却没人往里放东西」会以为模组坏了。
     */
    private static void tagNoTidyHint(CommandSourceStack src, Target t) {
        if (t == null) {
            return;
        }
        ContainerRecord rec = WarehouseMod.INDEX.containers.get(t.key());
        if (rec != null && Containers.noTidy(rec.blockId)) {
            send(src, "  注意：这类容器（书架 / 熔炉 / 漏斗……）只供查看，整理时不会把它当目标箱，也不会被搬动。");
        }
    }

    private static int tagAuto(CommandSourceStack src, BlockPos explicit) {
        Target t = tagTarget(src, explicit);
        if (t == null) {
            return 0;
        }
        ContainerRecord rec = liveRecord(t.level(), t.pos());
        if (rec == null) {
            send(src, "读不到这只箱子的内容。");
            return 0;
        }
        String cat = ContainerTags.dominantCategory(rec);
        if (cat == null) {
            send(src, coordOf(t) + " 里没有能判定的物品，算不出分类。先放点东西。");
            return 0;
        }
        ContainerTags.setAuto(t.key(), cat, adminName(src));
        ContainerTags.save();
        Audit.add(adminName(src), "改标签", "按内容把 " + coordOf(t) + " 定案为「" + cat + "」(自动档)");
        ContainerTags.Tag tag = ContainerTags.get(t.key());
        if (tag != null && tag.isAutoMode()) {
            send(src, coordOf(t) + " 按内容定案（自动档）：" + cat + "  箱内 " + rec.contents.size() + " 种物品");
        } else {
            send(src, coordOf(t) + " 按内容算得：" + cat + "（手动档，拨到自动档才生效）");
        }
        regionHint(src, t);
        return 1;
    }

    private static int tagStaging(CommandSourceStack src, BlockPos explicit) {
        Target t = tagTarget(src, explicit);
        if (t == null) {
            return 0;
        }
        boolean now = !ContainerTags.isStaging(t.key());
        ContainerTags.setStaging(t.key(), now, adminName(src));
        if (!now) {
            ContainerTags.Tag tag = ContainerTags.get(t.key());
            if (tag != null && tag.effectiveCategory() == null && tag.autoCategory == null) {
                ContainerTags.clear(t.key());
            }
        }
        ContainerTags.save();
        Audit.add(adminName(src), "改标签", (now ? "把 " : "取消 ") + coordOf(t) + (now ? " 设为暂存箱（只出不进）" : " 的暂存"));
        send(src, now
                ? coordOf(t) + " 已设为「暂存」：整理时只从这里往外取，不往里放。"
                : coordOf(t) + " 已取消「暂存」。");
        regionHint(src, t);
        return 1;
    }

    private static int tagClear(CommandSourceStack src, BlockPos explicit) {
        Target t = tagTarget(src, explicit);
        if (t == null) {
            return 0;
        }
        if (!ContainerTags.clear(t.key())) {
            send(src, coordOf(t) + " 本来就没有标签。");
            return 0;
        }
        ContainerTags.save();
        Audit.add(adminName(src), "改标签", "清除 " + coordOf(t) + " 的标签");
        send(src, coordOf(t) + " 的标签已清除（整理时回到启发式）。");
        return 1;
    }

    private static int tagMode(CommandSourceStack src, String mode, BlockPos explicit) {
        Target t = tagTarget(src, explicit);
        if (t == null) {
            return 0;
        }
        ContainerTags.Tag before = ContainerTags.get(t.key());
        if (ContainerTags.AUTO.equals(mode) && (before == null || before.autoCategory == null || before.autoCategory.isEmpty())) {
            ContainerRecord rec = liveRecord(t.level(), t.pos());
            String cat = rec == null ? null : ContainerTags.dominantCategory(rec);
            if (cat == null) {
                send(src, "自动档要先按箱内内容算一次分类，但这只箱子里没有能判定的物品。先放点东西，或手动贴一个分类。");
                return 0;
            }
            ContainerTags.setAuto(t.key(), cat, adminName(src));
            send(src, "本箱还没有自动分类，已按当前内容算得：" + cat);
        }
        ContainerTags.setMode(t.key(), mode, adminName(src));
        ContainerTags.save();
        Audit.add(adminName(src), "改标签模式", coordOf(t) + " → " + (ContainerTags.AUTO.equals(mode) ? "自动档" : "手动档"));
        ContainerTags.Tag now = ContainerTags.get(t.key());
        send(src, coordOf(t) + " 档位已改为" + (ContainerTags.AUTO.equals(mode)
                ? "自动档（按内容定案）"
                : "手动档（用手动选的分类）")
                + "，现在：" + (now == null ? "未打标签" : now.label()));
        regionHint(src, t);
        return 1;
    }

    /** 标签键的坐标已经不是归一化坐标了（后来并成/拆成双联箱）——列表里提示一句 */
    private static boolean staleKey(CommandSourceStack src, String key) {
        ContainerTags.Spot s = ContainerTags.spot(key);
        if (s == null) {
            return false;
        }
        ServerLevel level = Scanner.levelOf(src.getServer(), s.dimension());
        if (level == null || !level.isLoaded(s.pos())) {
            return false;
        }
        if (!(level.getBlockEntity(s.pos()) instanceof Container)) {
            return false;
        }
        return !Scanner.canonical(level, s.pos()).equals(s.pos());
    }

    private static int tagList(CommandSourceStack src, int page) {        if (ContainerTags.size() == 0) {
            send(src, "还没有任何箱子标签。用 /warehouse tag set <分类> 贴一个（对着箱子执行可省坐标）。");
            return 1;
        }
        List<Map.Entry<String, ContainerTags.Tag>> list = new ArrayList<>(ContainerTags.all().entrySet());
        list.sort(Comparator.comparing(Map.Entry::getKey));
        int pages = (list.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int p = Math.max(1, Math.min(page, pages));
        send(src, "箱子标签 " + list.size() + " 条（第 " + p + "/" + pages + " 页）：");
        for (int i = (p - 1) * PAGE_SIZE; i < Math.min(list.size(), p * PAGE_SIZE); i++) {
            Map.Entry<String, ContainerTags.Tag> e = list.get(i);
            ContainerTags.Spot s = ContainerTags.spot(e.getKey());
            String where = s == null ? e.getKey() : (s.dimension() + " " + fmt(s.pos()));
            String gone = ContainerTags.loadedAndPresent(src.getServer(), e.getKey()) ? "" : "（未加载或已不存在）";
            String stale = staleKey(src, e.getKey()) ? "（坐标已变，建议重贴或 tag prune）" : "";
            send(src, "  " + (i + 1) + ". " + where + " " + e.getValue().label() + gone + stale);
        }
        return 1;
    }

    private static String fmt(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static String dim(ServerPlayer p) {
        return p.level().dimension().identifier().toString();
    }

    // ===== 统一文案：仓库 / 搬运工 / 索引 / Carpet 等反复出现的提示集中在这里 =====

    private static String noRegion(String name) {
        return "不存在名为「" + name + "」的仓库，可用 /warehouse region list 查看。";
    }

    private static String noBot(String name) {
        return "名册中不存在名为「" + name + "」的搬运工，可用 /warehouse bot 查看。";
    }

    /** 名册为空时的统一提示；{@code cmd} 为新增子命令（porter / bot），{@code withLimit} 决定是否写明上限。 */
    private static String noBotsYet(String cmd, boolean withLimit) {
        return "尚无搬运工。用 /warehouse " + cmd + " add [仓库名] 新增"
                + (withLimit ? "（上限 " + Bots.MAX + " 个）。" : "。");
    }

    private static String noRegions() {
        return "还没定义任何仓库。先站到两个对角执行 /warehouse pos1 与 /warehouse pos2，再 /warehouse region save <名字>。";
    }

    private static String noIndex() {
        return "索引为空，请先执行 /warehouse scan。";
    }

    private static String botsFull() {
        return "搬运工已达上限（" + Bots.MAX + " 个）。请先 /warehouse bot remove <名字> 移除一个。";
    }

    private static String noCarpet() {
        return "整合包未安装 Carpet，搬运工的人形无法生成（仍会隐形工作，取货送货不受影响）。";
    }

    private static String bodyOff() {
        return "人形已关闭，请先执行 /warehouse porter body on 开启。";
    }

    private static void send(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }
}
